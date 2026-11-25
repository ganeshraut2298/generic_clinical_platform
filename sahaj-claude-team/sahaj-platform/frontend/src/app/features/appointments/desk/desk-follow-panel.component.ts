import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { formatClockLabel, formatDisplayDate, isoWeekday } from '../../../shared/format/ist-date';
import { DeskApi } from './desk.api';
import {
  ACTION_NETWORK_MESSAGE,
  DeskDaySlots,
  DeskDoctor,
  DeskIntervalKey,
  DeskPanelMode,
  DeskProblem,
  FOLLOW_INTERVALS,
  dayLongLabel,
  deskProblem,
  followUpDate,
  inrOrFree,
  shortDoctorName,
} from './desk.model';

type FollowMode = Extract<DeskPanelMode, { kind: 'follow' }>;

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const SLOTS_SHOWN = 6;

/**
 * Follow-up panel (SAH-93-1): "Come back after" +7 days, +15 days or +1 month, the resulting date, the earliest
 * free slots of the doctor that day (or the next free day), the fee from the clinic rule, "Book follow-up" and
 * "No follow-up needed". Walk-in visits use the same panel.
 */
@Component({
  selector: 'app-desk-follow-panel',
  imports: [MatButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './desk-follow-panel.component.scss',
  templateUrl: './desk-follow-panel.component.html',
})
export class DeskFollowPanelComponent {
  private readonly api = inject(DeskApi);

  readonly mode = input.required<FollowMode>();
  readonly doctor = input<DeskDoctor | null>(null);
  readonly today = input.required<string>();
  /** Another write is running on the page: one write at a time (SAH-92). */
  readonly busy = input(false);

  readonly closePanel = output<void>();
  /** A follow-up was booked or marked not needed. */
  readonly saved = output<void>();
  readonly failed = output<DeskProblem>();
  /** This panel starts or ends a write; the page keeps the one shared busy state. */
  readonly busyChange = output<boolean>();

  protected readonly intervals = FOLLOW_INTERVALS;
  protected readonly inr = inrOrFree;
  protected readonly shortName = shortDoctorName;

  protected readonly interval = signal<DeskIntervalKey>('D7');
  protected readonly chosenTime = signal<string | null>(null);
  protected readonly slots = signal<DeskDaySlots | null>(null);
  protected readonly loading = signal(false);
  protected readonly slotsError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly date = computed(() => followUpDate(this.today(), this.interval()));
  protected readonly dateLine = computed(() => {
    const d = this.date();
    return `Date: ${WEEKDAYS[isoWeekday(d)]}, ${formatDisplayDate(d)}`;
  });

  /** The day whose slots are offered: the chosen date, or the next free day when that date has none. */
  protected readonly offerDate = signal<string | null>(null);
  protected readonly offered = computed(() => {
    const date = this.offerDate();
    if (!date) {
      return [];
    }
    return (this.slots()?.groups ?? []).flatMap((g) => g.slots).slice(0, SLOTS_SHOWN);
  });
  protected readonly noSlotsText = signal<string | null>(null);
  protected readonly selectedTime = computed(() => this.chosenTime() ?? this.offered()[0]?.time ?? null);
  protected readonly feeLabel = computed(() => inrOrFree(this.doctor()?.followUpFeePaise ?? 0));
  protected readonly canBook = computed(() => this.selectedTime() !== null && this.offerDate() !== null && !this.saving() && !this.busy());
  protected readonly offerDateLabel = computed(() => {
    const date = this.offerDate();
    return date && date !== this.date() ? dayLongLabel(date) : null;
  });

  private seq = 0;

  constructor() {
    effect(() => {
      const doctorId = this.mode().doctorMembershipId;
      const date = this.date();
      untracked(() => this.load(doctorId, date));
    });
  }

  protected chooseInterval(key: DeskIntervalKey): void {
    if (key === this.interval()) {
      return;
    }
    // A new interval resets the slot choice (SAH-93-1).
    this.chosenTime.set(null);
    this.error.set(null);
    this.interval.set(key);
  }

  protected chooseTime(time: string): void {
    this.chosenTime.set(time);
  }

  protected clock(time: string): string {
    return formatClockLabel(time);
  }

  protected retry(): void {
    this.load(this.mode().doctorMembershipId, this.date());
  }

  protected book(): void {
    const time = this.selectedTime();
    const date = this.offerDate();
    if (!time || !date || this.saving() || this.busy()) {
      return;
    }
    this.run(
      this.api.bookVisitFollowUp(this.mode().visitId, {
        intervalKey: this.interval(),
        apptDate: date,
        apptTime: time,
        visitType: 'FOLLOW_UP',
      }),
      true,
    );
  }

  protected notNeeded(): void {
    if (this.saving() || this.busy()) {
      return;
    }
    this.run(this.api.markFollowUpNotNeeded(this.mode().visitId), false);
  }

  private run(call: ReturnType<DeskApi['bookVisitFollowUp']>, booking: boolean): void {
    this.saving.set(true);
    this.busyChange.emit(true);
    this.error.set(null);
    call.subscribe({
      next: () => {
        this.saving.set(false);
        this.busyChange.emit(false);
        this.saved.emit();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.busyChange.emit(false);
        const problem = deskProblem(error, ACTION_NETWORK_MESSAGE);
        this.error.set(problem.message);
        if (booking && problem.slotTaken) {
          // Someone booked the slot first: show the slots again and keep the panel open.
          this.chosenTime.set(null);
          this.load(this.mode().doctorMembershipId, this.date());
        }
        this.failed.emit(problem);
      },
    });
  }

  private load(doctorId: string, date: string): void {
    const seq = ++this.seq;
    this.loading.set(true);
    this.slotsError.set(null);
    this.noSlotsText.set(null);
    this.offerDate.set(null);
    this.slots.set(null);
    this.api.getDaySlots(doctorId, date).subscribe({
      next: (first) => {
        if (seq !== this.seq) {
          return;
        }
        if (first.freeCount > 0) {
          this.show(first, date);
          return;
        }
        const next = first.nextFreeDate;
        if (!next) {
          this.noSlotsText.set(`No slots on ${dayLongLabel(date)}. No free day in the next two weeks.`);
          this.loading.set(false);
          return;
        }
        this.noSlotsText.set(`No slots on ${dayLongLabel(date)}. Next free day: ${dayLongLabel(next)}.`);
        this.api.getDaySlots(doctorId, next).subscribe({
          next: (second) => {
            if (seq === this.seq) {
              this.show(second, next);
            }
          },
          error: () => this.failLoad(seq),
        });
      },
      error: () => this.failLoad(seq),
    });
  }

  private show(result: DeskDaySlots, date: string): void {
    this.slots.set(result);
    this.offerDate.set(result.freeCount > 0 ? date : null);
    this.loading.set(false);
  }

  private failLoad(seq: number): void {
    if (seq !== this.seq) {
      return;
    }
    this.loading.set(false);
    this.slotsError.set('We could not load the slots. Try again.');
  }
}
