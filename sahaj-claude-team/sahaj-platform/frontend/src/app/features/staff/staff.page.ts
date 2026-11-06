import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { ApiError } from '../../core/http/problem-detail';
import { ShellStore } from '../../core/shell/shell.store';
import {
  addDays,
  formatDisplayDate,
  formatDisplayDateTimeOfInstant,
  istToday,
} from '../../shared/format/ist-date';
import { IconComponent } from '../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { PageHeadComponent } from '../../shared/ui/page-head.component';
import { ClinicApi } from '../config/platform/clinic.api';
import { Holiday } from '../config/platform/clinic.model';
import { StaffApi } from './staff.api';
import {
  ATTENDANCE_OPTIONS,
  ATTENDANCE_PILL,
  LEAVE_PILL,
  PUBLIC_HOLIDAYS,
  SHIFT_LABEL,
  affectedText,
  dayMonth,
  inAtText,
  leaveRange,
  nextShift,
  weekdayShort,
} from './staff-display.util';
import {
  Attendance,
  AttendanceStatus,
  LeaveRequest,
  LeaveType,
  LeaveView,
  Roster,
  RosterCell,
  RosterRow,
  StaffSummary,
} from './staff.model';

type TabKey = 'roster' | 'leave' | 'att' | 'hol';

const TABS: readonly { key: TabKey; label: string }[] = [
  { key: 'roster', label: 'Staff and shifts' },
  { key: 'leave', label: 'Leave' },
  { key: 'att', label: 'Attendance' },
  { key: 'hol', label: 'Holidays' },
];

const LOAD_ERROR = 'We could not load this page. Try again.';
const SAVE_ERROR = 'We could not save that change. Try again.';

function messageOf(error: unknown, fallback: string): string {
  return error instanceof ApiError ? (error.problem.detail ?? fallback) : fallback;
}

/** To is not before From, and at most 60 days after it. */
function leaveDatesValidator(group: AbstractControl): ValidationErrors | null {
  const from = group.get('from')?.value as string;
  const to = group.get('to')?.value as string;
  if (!from || !to) {
    return null;
  }
  if (to < from) {
    return { toBeforeFrom: true };
  }
  return to > addDays(from, 60) ? { rangeTooLong: true } : null;
}

/** Staff and leave (SAH-20, SAH-21), mockup `vStaff`: tiles and the Staff and shifts, Leave,
 * Attendance and Holidays tabs. */
@Component({
  selector: 'app-staff',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    ReactiveFormsModule,
    RouterLink,
    IconComponent,
    PageHeadComponent,
    EmptyStateComponent,
  ],
  styleUrl: './staff.page.scss',
  templateUrl: './staff.page.html',
})
export class StaffPage implements OnInit {
  private readonly api = inject(StaffApi);
  private readonly clinicApi = inject(ClinicApi);
  private readonly shell = inject(ShellStore);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly tabs = TABS;
  protected readonly attendanceOptions = ATTENDANCE_OPTIONS;
  protected readonly attendancePill = ATTENDANCE_PILL;
  protected readonly publicHolidays = PUBLIC_HOLIDAYS;
  protected readonly weekdayShort = weekdayShort;
  protected readonly dayMonth = dayMonth;
  protected readonly formatDate = formatDisplayDate;
  protected readonly inAtText = inAtText;
  protected readonly leaveRange = leaveRange;
  protected readonly affectedText = affectedText;
  protected readonly leavePill = LEAVE_PILL;
  protected readonly askedAt = formatDisplayDateTimeOfInstant;
  protected readonly today = istToday();
  protected readonly maxReason = 200;

  protected readonly tab = signal<TabKey>('roster');
  protected readonly loading = signal(true);
  protected readonly locked = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);

  protected readonly summary = signal<StaffSummary | null>(null);
  protected readonly roster = signal<Roster | null>(null);
  protected readonly attendance = signal<Attendance | null>(null);
  protected readonly holidays = signal<Holiday[] | null>(null);
  protected readonly holidaysError = signal<string | null>(null);

  protected readonly leave = signal<LeaveView | null>(null);
  protected readonly leaveError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly sending = signal(false);

  protected readonly leaveForm = new FormGroup(
    {
      who: new FormControl('', { nonNullable: true }),
      type: new FormControl<LeaveType>('CL', { nonNullable: true }),
      from: new FormControl(istToday(), { nonNullable: true, validators: [Validators.required] }),
      to: new FormControl(istToday(), { nonNullable: true, validators: [Validators.required] }),
      reason: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
    },
    { validators: [leaveDatesValidator] },
  );
  private readonly formValue = signal(this.leaveForm.getRawValue());

  protected readonly busy = signal<string | null>(null);

  protected readonly isOwner = computed(() => this.shell.me()?.role === 'OWNER');
  /** Pending requests, shown to the owner only. */
  protected readonly pendingLeave = computed(() =>
    this.isOwner() ? (this.leave()?.pendingCount ?? this.summary()?.leaveRequests ?? 0) : 0,
  );

  protected readonly multipleApplicants = computed(
    () => (this.leave()?.applicants.length ?? 0) > 1,
  );

  protected readonly fixedApplicantName = computed(
    () => this.leave()?.applicants[0]?.name ?? this.shell.me()?.name ?? '',
  );

  protected readonly fromMax = computed(() => addDays(this.today, 60));
  protected readonly toMax = computed(() => addDays(this.formValue().from || this.today, 60));

  /** Type options with "N left" from the chosen person's balance (yearly allowance if unknown). */
  protected readonly typeOptions = computed(() => {
    const view = this.leave();
    if (!view) {
      return [];
    }
    const balance = view.balances.find((b) => b.membershipId === this.formValue().who);
    return view.types.map((t) => ({
      key: t.key,
      label: `${t.name} · ${balance ? balance.remaining[t.key] : t.days} left`,
    }));
  });

  /** Upcoming holidays first (nearest date first), then past ones (latest first). */
  protected readonly clinicHolidays = computed(() => {
    const today = istToday();
    const all = this.holidays() ?? [];
    const upcoming = all
      .filter((h) => h.date >= today)
      .sort((a, b) => a.date.localeCompare(b.date));
    const past = all.filter((h) => h.date < today).sort((a, b) => b.date.localeCompare(a.date));
    return [...upcoming, ...past];
  });

  protected readonly nextHoliday = computed(() => {
    const today = istToday();
    return this.clinicHolidays().find((h) => h.date >= today) ?? null;
  });

  constructor() {
    this.leaveForm.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.formValue.set(this.leaveForm.getRawValue()));
  }

  ngOnInit(): void {
    this.loadAll();
  }

  protected selectTab(key: TabKey): void {
    this.tab.set(key);
    this.actionError.set(null);
  }

  protected onTabKeydown(event: KeyboardEvent): void {
    const order = this.tabs.map((t) => t.key);
    const at = order.indexOf(this.tab());
    let next: number;
    switch (event.key) {
      case 'ArrowRight':
        next = (at + 1) % order.length;
        break;
      case 'ArrowLeft':
        next = (at - 1 + order.length) % order.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = order.length - 1;
        break;
      default:
        return;
    }
    event.preventDefault();
    this.selectTab(order[next]);
    document.getElementById('st-tab-' + order[next])?.focus();
  }

  protected loadAll(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api
      .summary()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (summary) => {
          this.summary.set(summary);
          this.loading.set(false);
          this.loadParts();
        },
        error: (error: unknown) => {
          if (
            error instanceof ApiError &&
            error.status === 403 &&
            error.problem.type.endsWith('module-off')
          ) {
            this.locked.set(true);
          } else {
            this.loadError.set(messageOf(error, LOAD_ERROR));
          }
          this.loading.set(false);
        },
      });
  }

  private loadRoster(): void {
    this.api
      .roster()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (roster) => this.roster.set(roster),
        error: (error: unknown) => this.loadError.set(messageOf(error, LOAD_ERROR)),
      });
  }

  private loadAttendance(): void {
    this.api
      .attendance()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (attendance) => this.attendance.set(attendance),
        error: (error: unknown) => this.loadError.set(messageOf(error, LOAD_ERROR)),
      });
  }

  /** Approved leave shows in the roster and attendance, so those reload with the summary. */
  private refreshAfterLeave(): void {
    this.loadRoster();
    this.loadAttendance();
    this.refreshSummary();
  }

  private loadParts(): void {
    this.loadRoster();
    this.loadAttendance();
    this.loadLeave();
    this.loadHolidays();
  }

  protected loadLeave(): void {
    this.leaveError.set(null);
    this.api
      .leave()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (view) => this.setLeave(view),
        error: (error: unknown) => this.leaveError.set(messageOf(error, LOAD_ERROR)),
      });
  }

  private setLeave(view: LeaveView): void {
    this.leave.set(view);
    const who = this.leaveForm.controls.who;
    if (!view.applicants.some((a) => a.membershipId === who.value)) {
      const mine = view.applicants.find((a) => a.membershipId === this.shell.me()?.membershipId);
      who.setValue((mine ?? view.applicants[0])?.membershipId ?? '');
    }
  }

  private loadHolidays(): void {
    this.holidaysError.set(null);
    this.clinicApi
      .listHolidays()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => this.holidays.set(list),
        error: (error: unknown) => this.holidaysError.set(messageOf(error, LOAD_ERROR)),
      });
  }

  private refreshSummary(): void {
    this.api
      .summary()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (summary) => this.summary.set(summary), error: () => undefined });
  }

  protected cellText(cell: RosterCell): string {
    if (cell.kind === 'SHIFT') {
      return SHIFT_LABEL[cell.shift ?? 'X'];
    }
    if (cell.kind === 'LEAVE') {
      return 'Leave';
    }
    return cell.kind === 'OPD' ? 'OPD' : 'Off';
  }

  protected cellTitle(cell: RosterCell): string {
    return cell.kind === 'LEAVE' ? 'Approved leave' : 'From Appointments and slots';
  }

  protected cellClass(cell: RosterCell): string {
    if (cell.kind === 'SHIFT') {
      return 'sh-' + (cell.shift ?? 'X');
    }
    if (cell.kind === 'LEAVE') {
      return 'lv';
    }
    return cell.kind === 'OPD' ? 'op' : 'off';
  }

  protected cellLabel(row: RosterRow, cell: RosterCell): string {
    const base = `${row.name}, ${weekdayShort(cell.date)} ${dayMonth(cell.date)}: ${this.cellText(cell)}`;
    return this.isOwner() && cell.kind === 'SHIFT' ? `${base}. Tap to change` : base;
  }

  protected cycleCell(row: RosterRow, cell: RosterCell): void {
    if (!this.isOwner() || row.doctor || cell.kind !== 'SHIFT') {
      return;
    }
    this.busy.set(`cell|${row.membershipId}|${cell.date}`);
    this.actionError.set(null);
    this.api
      .setCell({ membershipId: row.membershipId, date: cell.date, shift: nextShift(cell.shift) })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(this.saved((roster) => this.roster.set(roster)));
  }

  protected publish(): void {
    this.busy.set('publish');
    this.actionError.set(null);
    this.api
      .publish()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(this.saved((roster) => this.roster.set(roster)));
  }

  protected mark(membershipId: string, status: AttendanceStatus): void {
    this.busy.set('att|' + membershipId);
    this.actionError.set(null);
    this.api
      .mark(membershipId, status)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(
        this.saved((attendance) => {
          this.attendance.set(attendance);
          this.refreshSummary();
        }),
      );
  }

  protected openLeaveTab(): void {
    this.selectTab('leave');
    document.getElementById('st-tab-leave')?.focus();
  }

  protected sendRequest(): void {
    this.leaveForm.markAllAsTouched();
    if (this.leaveForm.invalid || this.sending() || !this.leave()) {
      return;
    }
    const v = this.leaveForm.getRawValue();
    const reason = v.reason.trim();
    this.sending.set(true);
    this.formError.set(null);
    this.api
      .applyLeave({
        ...(this.multipleApplicants() ? { membershipId: v.who } : {}),
        type: v.type,
        from: v.from,
        to: v.to,
        ...(reason ? { reason } : {}),
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (view) => {
          this.setLeave(view);
          this.leaveForm.controls.reason.reset('');
          this.sending.set(false);
          this.refreshAfterLeave();
        },
        error: (error: unknown) => {
          this.formError.set(messageOf(error, SAVE_ERROR));
          this.sending.set(false);
        },
      });
  }

  protected onFromChange(): void {
    const { from, to } = this.leaveForm.controls;
    if (from.value && to.value < from.value) {
      to.setValue(from.value);
    }
  }

  protected decide(request: LeaveRequest, approve: boolean): void {
    if (!this.leave()?.canApprove) {
      return;
    }
    this.busy.set('lv|' + request.id);
    this.actionError.set(null);
    const call = approve ? this.api.approveLeave(request.id) : this.api.declineLeave(request.id);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (view) => {
        this.setLeave(view);
        this.busy.set(null);
        this.refreshAfterLeave();
      },
      error: (error: unknown) => {
        this.actionError.set(messageOf(error, SAVE_ERROR));
        this.busy.set(null);
        if (error instanceof ApiError && error.status === 409) {
          this.loadLeave();
        }
      },
    });
  }

  /** The existing clinic holiday of that date, if any. The switch state comes from this. */
  protected holidayOn(date: string): Holiday | undefined {
    return (this.holidays() ?? []).find((h) => h.date === date);
  }

  protected togglePublicHoliday(date: string, name: string): void {
    if (!this.isOwner() || this.holidays() === null) {
      return;
    }
    const existing = this.holidayOn(date);
    this.busy.set('hol|' + date);
    this.actionError.set(null);
    const observer = this.saved(() => {
      this.loadHolidays();
      this.loadRoster();
    });
    if (existing?.id) {
      this.clinicApi
        .removeHoliday(existing.id)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(observer);
    } else {
      this.clinicApi
        .addHoliday({ date, name, type: 'CLOSED' })
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(observer);
    }
  }

  private saved<T>(apply: (value: T) => void): {
    next: (value: T) => void;
    error: (error: unknown) => void;
  } {
    return {
      next: (value) => {
        apply(value);
        this.busy.set(null);
      },
      error: (error: unknown) => {
        this.actionError.set(messageOf(error, SAVE_ERROR));
        this.busy.set(null);
      },
    };
  }
}
