import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar } from '@angular/material/snack-bar';
import { addDays, istToday } from '../../../shared/format/ist-date';
import { computeSlots } from '../../../shared/scheduling/slot.util';
import { IconComponent } from '../../../shared/icon/icon.component';
import { AppointmentsApi } from '../../appointments/appointments.api';
import {
  BookingRules,
  DEFAULT_BOOKING_RULES,
  DoctorSlotSettings,
  SLOT_LENGTH_OPTIONS,
  WALK_IN_EVERY_NTH_OPTIONS,
  WORKING_DAY_OPTIONS,
  defaultSlotSettings,
} from '../../appointments/appointments.model';
import { canEditBookingRules, canEditSlotSettings } from '../../appointments/appointments-role.util';
import { ShellStore } from '../../../core/shell/shell.store';
import { problemMessage } from '../config-format';
import { TeamApi } from '../team/team.api';
import { TeamMember, isDoctorRole } from '../team/team.model';

interface DaySummary {
  readonly total: number;
  readonly walkIn: number;
  readonly bookable: number;
}

/**
 * Appointments and slots (SAH-11), mockup `cfgSlots`: each doctor's slot length, up to two OPD
 * sessions, working days, walk-in reserve and daily online cap, plus the clinic-wide booking
 * rules underneath. Front desk can change slot settings; only the owner can change booking
 * rules and overbook (story acceptance criterion 5).
 */
@Component({
  selector: 'app-slots',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styleUrl: './slots.page.scss',
  templateUrl: './slots.page.html',
})
export class SlotsPage implements OnInit {
  private readonly api = inject(AppointmentsApi);
  private readonly teamApi = inject(TeamApi);
  private readonly store = inject(ShellStore);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly slotLengthOptions = SLOT_LENGTH_OPTIONS;
  protected readonly walkInOptions = WALK_IN_EVERY_NTH_OPTIONS;
  protected readonly workingDayOptions = WORKING_DAY_OPTIONS;

  protected readonly doctors = signal<TeamMember[]>([]);
  protected readonly slotSettings = signal<DoctorSlotSettings[]>([]);
  protected readonly bookingRules = signal<BookingRules>(DEFAULT_BOOKING_RULES);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly savingDoctorId = signal<string | null>(null);

  private savedRules: BookingRules = DEFAULT_BOOKING_RULES;

  protected readonly canEditSlots = computed(() => canEditSlotSettings(this.store.me()?.role));
  protected readonly canEditRules = computed(() => canEditBookingRules(this.store.me()?.role));

  ngOnInit(): void {
    this.teamApi.list().subscribe({
      next: (members) => {
        this.doctors.set(members.filter((m) => isDoctorRole(m.role) && m.status === 'ACTIVE'));
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(problemMessage(error, 'We could not load the doctor list.'));
      },
    });
    this.api.getDoctorSlots().subscribe({
      next: (settings) => this.slotSettings.set(settings),
      error: () => undefined,
    });
    this.api.getBookingRules().subscribe({
      next: (rules) => {
        this.bookingRules.set(rules);
        this.savedRules = rules;
      },
      error: () => undefined,
    });
  }

  protected settingsFor(doctorId: string): DoctorSlotSettings {
    return this.slotSettings().find((s) => s.doctorMembershipId === doctorId) ?? defaultSlotSettings(doctorId);
  }

  /** `sl.length` slots a day, from the next day this doctor actually works, trying up to a
   * week ahead — mirrors the mockup's `slotsFor(d,0)||slotsFor(d,2)||[]` fallback. */
  protected daySummary(doctor: TeamMember): DaySummary | null {
    const settings = this.settingsFor(doctor.id);
    let date = istToday();
    for (let i = 0; i < 7; i += 1) {
      const slots = computeSlots(settings, date);
      if (slots) {
        const walkIn = slots.filter((s) => s.walkIn).length;
        return { total: slots.length, walkIn, bookable: slots.length - walkIn };
      }
      date = addDays(date, 1);
    }
    return null;
  }

  protected setSlotLength(doctor: TeamMember, slotMinutes: number): void {
    this.saveSlots(doctor, { slotMinutes });
  }

  protected toggleWorkingDay(doctor: TeamMember, day: number): void {
    const current = this.settingsFor(doctor.id).workingDays;
    const workingDays = current.includes(day) ? current.filter((d) => d !== day) : [...current, day];
    this.saveSlots(doctor, { workingDays });
  }

  protected setWalkIn(doctor: TeamMember, walkInEveryNth: number): void {
    this.saveSlots(doctor, { walkInEveryNth });
  }

  protected setMaxOnline(doctor: TeamMember, input: HTMLInputElement): void {
    const value = Math.round(Number(input.value));
    const before = this.settingsFor(doctor.id).maxOnlinePerDay;
    if (!Number.isFinite(value) || value < 0) {
      input.value = String(before);
      this.toast('Enter 0 or more online bookings a day.');
      return;
    }
    if (value !== before) {
      this.saveSlots(doctor, { maxOnlinePerDay: value });
    }
  }

  protected addSession(doctor: TeamMember): void {
    const settings = this.settingsFor(doctor.id);
    if (settings.sessions.length >= 2) {
      return;
    }
    this.saveSlots(doctor, { sessions: [...settings.sessions, { startTime: '17:00', endTime: '20:00' }] });
  }

  protected removeSession(doctor: TeamMember, index: number): void {
    const settings = this.settingsFor(doctor.id);
    if (settings.sessions.length <= 1) {
      return;
    }
    this.saveSlots(doctor, { sessions: settings.sessions.filter((_, i) => i !== index) });
  }

  protected setSessionTime(doctor: TeamMember, index: number, part: 'startTime' | 'endTime', value: string): void {
    if (!value) {
      return;
    }
    const settings = this.settingsFor(doctor.id);
    const session = { ...settings.sessions[index], [part]: value };
    if (session.startTime >= session.endTime) {
      this.toast('The session must end after it starts.');
      return;
    }
    const sessions = settings.sessions.map((s, i) => (i === index ? session : s));
    this.saveSlots(doctor, { sessions });
  }

  protected stepRule(key: keyof Pick<BookingRules, 'closeBeforeHours' | 'remindBeforeHours' | 'noShowAfterMinutes'>, delta: number, min: number, max: number): void {
    const current = this.bookingRules()[key];
    const next = Math.min(max, Math.max(min, current + delta));
    if (next !== current) {
      this.saveRules({ [key]: next } as Partial<BookingRules>);
    }
  }

  protected toggleRule(key: 'followPriority' | 'selfBook' | 'overbook'): void {
    this.saveRules({ [key]: !this.bookingRules()[key] } as Partial<BookingRules>);
  }

  private saveSlots(doctor: TeamMember, patch: Partial<DoctorSlotSettings>): void {
    if (!this.canEditSlots() || this.savingDoctorId()) {
      return;
    }
    const before = this.settingsFor(doctor.id);
    const next: DoctorSlotSettings = { ...before, ...patch };
    this.upsertSettings(next);
    this.savingDoctorId.set(doctor.id);
    this.api.saveDoctorSlots(doctor.id, next).subscribe({
      next: (saved) => {
        this.savingDoctorId.set(null);
        this.upsertSettings(saved);
      },
      error: (error: unknown) => {
        this.savingDoctorId.set(null);
        this.upsertSettings(before);
        this.toast(problemMessage(error, 'We could not save that change. Try again.'));
      },
    });
  }

  private upsertSettings(settings: DoctorSlotSettings): void {
    this.slotSettings.update((list) => {
      const others = list.filter((s) => s.doctorMembershipId !== settings.doctorMembershipId);
      return [...others, settings];
    });
  }

  private saveRules(patch: Partial<BookingRules>): void {
    if (!this.canEditRules()) {
      return;
    }
    const next = { ...this.bookingRules(), ...patch };
    this.bookingRules.set(next);
    this.api.saveBookingRules(next).subscribe({
      next: (saved) => {
        this.bookingRules.set(saved);
        this.savedRules = saved;
      },
      error: (error: unknown) => {
        this.bookingRules.set(this.savedRules);
        this.toast(problemMessage(error, 'We could not save that change. Try again.'));
      },
    });
  }

  private toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000 });
  }
}
