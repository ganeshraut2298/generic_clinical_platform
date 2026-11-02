import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { RouterLink } from '@angular/router';
import { ApiError } from '../../../core/http/problem-detail';
import { ShellStore } from '../../../core/shell/shell.store';
import { addDays, formatClockLabel, istToday, nowMinutesIst } from '../../../shared/format/ist-date';
import { ComputedSlot, computeSlots } from '../../../shared/scheduling/slot.util';
import { IconComponent } from '../../../shared/icon/icon.component';
import { TeamApi } from '../../config/team/team.api';
import { TeamMember, isDoctorRole } from '../../config/team/team.model';
import {
  AppointmentDisplayStatus,
  appointmentDisplayPillClass,
  appointmentDisplayStatus,
} from '../appointment-display.util';
import { AppointmentsApi } from '../appointments.api';
import {
  Appointment,
  AppointmentVisitType,
  BookingRules,
  DoctorSlotSettings,
  defaultSlotSettings,
} from '../appointments.model';
import { canManageAppointments } from '../appointments-role.util';
import { AppointmentDetailDialogComponent, AppointmentDetailDialogData } from '../detail/appointment-detail-dialog.component';
import { BookAppointmentDialogComponent, BookAppointmentDialogData } from '../book/book-appointment-dialog.component';

type DayOffset = 0 | 1;

interface DoctorColumn {
  readonly doctor: TeamMember;
  readonly settings: DoctorSlotSettings;
  readonly slots: readonly ComputedSlot[] | null;
  readonly appointmentByTime: ReadonlyMap<string, Appointment>;
}

const DIALOG_CONFIG = { panelClass: 'sheet-panel', width: '560px', maxWidth: '100vw' } as const;

/**
 * Appointments (SAH-11), mockup `vAppointments`: Today/Tomorrow, a doctor filter, stat tiles,
 * the booking-rule chips with a link into Clinic setup, and one column per doctor. Week view
 * and drag-and-drop are out of scope (story §6); only Today and Tomorrow are built.
 */
@Component({
  selector: 'app-appointments-board',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, RouterLink, IconComponent],
  styleUrl: './appointments-board.page.scss',
  templateUrl: './appointments-board.page.html',
})
export class AppointmentsBoardPage implements OnInit {
  private readonly api = inject(AppointmentsApi);
  private readonly teamApi = inject(TeamApi);
  private readonly store = inject(ShellStore);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly formatClockLabel = formatClockLabel;
  protected readonly appointmentDisplayPillClass = appointmentDisplayPillClass;

  protected readonly dayOffset = signal<DayOffset>(0);
  protected readonly doctorFilter = signal<string>('all');

  protected readonly doctors = signal<TeamMember[]>([]);
  protected readonly doctorsError = signal<string | null>(null);
  protected readonly slotSettings = signal<DoctorSlotSettings[]>([]);
  protected readonly bookingRules = signal<BookingRules | null>(null);

  protected readonly appointments = signal<Appointment[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  protected readonly now = signal(new Date());

  protected readonly role = computed(() => this.store.me()?.role);
  protected readonly canManage = computed(() => canManageAppointments(this.role()));

  protected readonly date = computed(() => (this.dayOffset() === 0 ? istToday() : addDays(istToday(), 1)));
  protected readonly dayLabel = computed(() => (this.dayOffset() === 0 ? 'Today' : 'Tomorrow'));

  private readonly slotSettingsById = computed(() => new Map(this.slotSettings().map((s) => [s.doctorMembershipId, s])));

  protected readonly visibleDoctors = computed(() => {
    const filter = this.doctorFilter();
    return this.doctors().filter((doctor) => filter === 'all' || doctor.id === filter);
  });

  protected readonly columns = computed<DoctorColumn[]>(() => {
    const date = this.date();
    const byId = this.slotSettingsById();
    const appts = this.appointments();
    return this.visibleDoctors().map((doctor) => {
      const settings = byId.get(doctor.id) ?? defaultSlotSettings(doctor.id);
      const slots = computeSlots(settings, date);
      const appointmentByTime = new Map(
        appts
          .filter((a) => a.doctorMembershipId === doctor.id && a.status !== 'CANCELLED')
          .map((a) => [a.apptTime, a] as const),
      );
      return { doctor, settings, slots, appointmentByTime };
    });
  });

  protected readonly stats = computed(() => {
    let booked = 0;
    let arrived = 0;
    let noShow = 0;
    let free = 0;
    let walkIn = 0;
    for (const col of this.columns()) {
      for (const appt of col.appointmentByTime.values()) {
        if (appt.status === 'BOOKED') {
          booked += 1;
        } else if (appt.status === 'ARRIVED') {
          arrived += 1;
        } else if (appt.status === 'NOSHOW') {
          noShow += 1;
        }
      }
      for (const slot of col.slots ?? []) {
        if (slot.walkIn) {
          walkIn += 1;
        } else if (!col.appointmentByTime.has(slot.time)) {
          free += 1;
        }
      }
    }
    return { booked, arrived, noShow, free, walkIn };
  });

  protected readonly ruleChips = computed<string[]>(() => {
    const r = this.bookingRules();
    if (!r) {
      return [];
    }
    const chips = [
      `Booking closes ${r.closeBeforeHours} h before`,
      `WhatsApp reminder ${r.remindBeforeHours} h before`,
      `No-show after ${r.noShowAfterMinutes} min`,
    ];
    if (r.followPriority) {
      chips.push('Follow-ups get priority');
    }
    chips.push(r.selfBook ? 'Patients can book on WhatsApp' : 'Booking by staff only');
    if (r.overbook) {
      chips.push('Double booking allowed');
    }
    return chips;
  });

  ngOnInit(): void {
    this.loadSettings();
    this.loadAppointments();
    const timer = setInterval(() => this.now.set(new Date()), 30_000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  protected setDay(day: DayOffset): void {
    if (this.dayOffset() === day) {
      return;
    }
    this.dayOffset.set(day);
    this.loadAppointments();
  }

  protected setDoctorFilter(id: string): void {
    this.doctorFilter.set(id);
  }

  protected displayStatusOf(appointment: Appointment): AppointmentDisplayStatus {
    return appointmentDisplayStatus(
      appointment.status,
      appointment.apptTime,
      this.dayOffset() === 0,
      this.bookingRules()?.noShowAfterMinutes ?? 15,
      nowMinutesIst(this.now()),
    );
  }

  protected displayStatusLabel(appointment: Appointment): string {
    const status = this.displayStatusOf(appointment);
    if (status === 'ARRIVED') {
      return `T-${appointment.tokenNo} · Arrived`;
    }
    const labels: Record<AppointmentDisplayStatus, string> = {
      BOOKED: 'Booked',
      DUE: 'Due now',
      LATE: `Late ${nowMinutesIst(this.now()) - this.toMinutes(appointment.apptTime)} min`,
      ARRIVED: 'Arrived',
      NOSHOW: 'No-show',
      CANCELLED: 'Cancelled',
    };
    return labels[status];
  }

  protected visitTypeLabel(type: AppointmentVisitType): string {
    return type === 'FOLLOW_UP' ? 'Follow-up' : 'New';
  }

  protected canCreateTokenFor(appointment: Appointment): boolean {
    return this.canManage() && this.dayOffset() === 0 && appointment.status === 'BOOKED';
  }

  protected openBook(doctor: TeamMember, time: string): void {
    if (!this.canManage()) {
      return;
    }
    const data: BookAppointmentDialogData = {
      doctorMembershipId: doctor.id,
      doctorName: doctor.name,
      apptDate: this.date(),
      apptTime: time,
    };
    this.dialog
      .open(BookAppointmentDialogComponent, { ...DIALOG_CONFIG, data })
      .afterClosed()
      .subscribe((appointment) => {
        if (appointment) {
          this.loadAppointments();
        }
      });
  }

  protected openDetail(appointment: Appointment): void {
    const data: AppointmentDetailDialogData = {
      appointment,
      whenLabel: `${this.dayLabel()} · ${formatClockLabel(appointment.apptTime)}`,
      isToday: this.dayOffset() === 0,
      role: this.role(),
    };
    this.dialog
      .open(AppointmentDetailDialogComponent, { ...DIALOG_CONFIG, data })
      .afterClosed()
      .subscribe((result) => {
        if (result) {
          this.loadAppointments();
        }
      });
  }

  protected createTokenFrom(appointment: Appointment): void {
    if (!this.canCreateTokenFor(appointment)) {
      return;
    }
    this.api.createToken(appointment.id).subscribe({
      next: () => this.loadAppointments(),
      error: () => undefined,
    });
  }

  private toMinutes(hhmm: string): number {
    const [h, m] = hhmm.split(':').map(Number);
    return h * 60 + m;
  }

  private loadSettings(): void {
    this.teamApi.list().subscribe({
      next: (members) => this.doctors.set(members.filter((m) => isDoctorRole(m.role) && m.status === 'ACTIVE')),
      error: () => this.doctorsError.set('We could not load the doctor list.'),
    });
    this.api.getDoctorSlots().subscribe({
      next: (settings) => this.slotSettings.set(settings),
      // Columns fall back to sensible defaults (defaultSlotSettings) if this fails.
      error: () => undefined,
    });
    this.api.getBookingRules().subscribe({
      next: (rules) => this.bookingRules.set(rules),
      error: () => undefined,
    });
  }

  private loadAppointments(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.list(this.date()).subscribe({
      next: (list) => {
        this.appointments.set(list);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(
          error instanceof ApiError ? error.problem.detail ?? 'We could not load appointments. Try again.' : 'We could not load appointments. Try again.',
        );
      },
    });
  }
}
