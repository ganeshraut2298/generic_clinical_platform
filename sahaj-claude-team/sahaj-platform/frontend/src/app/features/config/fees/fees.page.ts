import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { formatInr } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { addDays, formatDayMonth, istToday, problemMessage } from '../config-format';
import {
  DISCOUNT_ROLES,
  FEE_TIMINGS,
  FOLLOW_UP_FEES,
  FeeSettings,
  FeeTiming,
  FollowUpFee,
} from '../opd/opd.model';
import { OpdApi } from '../opd/opd.api';
import { TeamApi } from '../team/team.api';
import { TeamMember, isDoctorRole } from '../team/team.model';
import { ServiceListComponent } from './service-list.component';

const MIN_DAYS = 1;
const MAX_DAYS = 30;

/** The money a patient pays for a follow-up visit, as text: `Free`, or an amount in rupees. */
export function followUpAmount(fee: FollowUpFee, newVisitFee: number): string {
  if (fee === 'free') {
    return 'Free';
  }
  return formatInr(fee === 'half' ? Math.round(newVisitFee / 2) : newVisitFee);
}

/**
 * Fees and billing (SAH-7), mockup `cfgFees`: when the fee is collected, each doctor's new-visit
 * fee, the follow-up rule with a live example, the service price list, discount limits by role
 * and the UPI QR on the bill. Changes save as they are made. Non-owners see it read-only.
 */
@Component({
  selector: 'app-fees',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonToggleModule, IconComponent, ServiceListComponent],
  styleUrl: './fees.page.scss',
  templateUrl: './fees.page.html',
})
export class FeesPage implements OnInit {
  private readonly opd = inject(OpdApi);
  private readonly team = inject(TeamApi);
  private readonly store = inject(ShellStore);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly timings = FEE_TIMINGS;
  protected readonly followUpFees = FOLLOW_UP_FEES;
  protected readonly discountRoles = DISCOUNT_ROLES;
  protected readonly formatInr = formatInr;
  protected readonly followUpAmount = followUpAmount;
  protected readonly minDays = MIN_DAYS;
  protected readonly maxDays = MAX_DAYS;

  protected readonly settings = signal<FeeSettings | null>(null);
  protected readonly doctors = signal<TeamMember[]>([]);
  protected readonly doctorsError = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly savingDoctorId = signal<string | null>(null);

  private saved: FeeSettings | null = null;
  private pending = 0;

  protected readonly isOwner = computed(() => this.store.me()?.role === 'OWNER');

  protected readonly timingHint = computed(
    () => FEE_TIMINGS.find((t) => t.value === this.settings()?.feeTiming)?.hint ?? '',
  );

  /** The live example sentence uses the first doctor, as the mockup does. */
  protected readonly example = computed(() => {
    const s = this.settings();
    const doctor = this.doctors().find((d) => d.doctor?.feeRupees != null);
    if (!s || !doctor) {
      return null;
    }
    const fee = doctor.doctor?.feeRupees ?? 0;
    const today = istToday();
    return {
      doctor: doctor.name,
      today: formatDayMonth(today),
      until: formatDayMonth(addDays(today, s.followUpDays)),
      from: formatDayMonth(addDays(today, s.followUpDays + 1)),
      pays: followUpAmount(s.followUpFee, fee),
      newVisit: formatInr(fee),
    };
  });

  ngOnInit(): void {
    this.opd.getFeeSettings().subscribe({
      next: (settings) => {
        this.settings.set(settings);
        this.saved = settings;
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(problemMessage(error, 'We could not load fees and billing. Try again.'));
      },
    });
    this.team.list().subscribe({
      next: (members) =>
        this.doctors.set(
          members.filter((m) => m.status !== 'REMOVED' && (isDoctorRole(m.role) || m.doctor != null)),
        ),
      error: (error: unknown) =>
        this.doctorsError.set(problemMessage(error, "We could not load the doctors' fees.")),
    });
  }

  protected setTiming(value: FeeTiming): void {
    this.change({ feeTiming: value });
  }

  protected setFollowUpFee(value: FollowUpFee): void {
    this.change({ followUpFee: value });
  }

  protected stepDays(delta: number): void {
    const current = this.settings()?.followUpDays ?? MIN_DAYS;
    const next = Math.min(MAX_DAYS, Math.max(MIN_DAYS, current + delta));
    if (next !== current) {
      this.change({ followUpDays: next });
    }
  }

  protected toggleUpi(): void {
    this.change({ upiOnBill: !this.settings()?.upiOnBill });
  }

  protected limitOf(role: Role): number {
    return this.settings()?.discountLimits[role] ?? 0;
  }

  protected setLimit(role: Role, input: HTMLInputElement): void {
    const value = Math.round(Number(input.value));
    if (!Number.isFinite(value) || value < 0 || value > 100 || input.value.trim() === '') {
      input.value = String(this.limitOf(role));
      this.toast('Enter a discount limit from 0 to 100 percent');
      return;
    }
    const s = this.settings();
    if (s && value !== this.limitOf(role)) {
      this.change({ discountLimits: { ...s.discountLimits, [role]: value } });
    }
  }

  protected saveDoctorFee(member: TeamMember, input: HTMLInputElement): void {
    const value = Math.round(Number(input.value));
    const before = member.doctor?.feeRupees ?? 0;
    if (!Number.isFinite(value) || value < 0 || input.value.trim() === '') {
      input.value = String(before);
      this.toast('Enter a fee of ₹0 or more');
      return;
    }
    if (value === before) {
      return;
    }
    this.savingDoctorId.set(member.id);
    this.team.update(member.id, { doctor: { ...member.doctor, feeRupees: value } }).subscribe({
      next: (updated) => {
        this.doctors.update((list) => list.map((d) => (d.id === updated.id ? updated : d)));
        this.savingDoctorId.set(null);
        this.toast(`${updated.name}'s fee is now ${formatInr(value)}`);
      },
      error: (error: unknown) => {
        input.value = String(before);
        this.savingDoctorId.set(null);
        this.toast(problemMessage(error, 'We could not save that fee. Try again.'));
      },
    });
  }

  /** Shows the change at once, then saves the whole settings object. A failure puts the old values back. */
  private change(patch: Partial<FeeSettings>): void {
    const current = this.settings();
    if (!current || !this.isOwner()) {
      return;
    }
    const next = { ...current, ...patch };
    this.settings.set(next);
    this.pending++;
    this.opd.saveFeeSettings(next).subscribe({
      next: (saved) => {
        this.pending--;
        if (this.pending === 0) {
          this.settings.set(saved);
        }
        this.saved = saved;
      },
      error: (error: unknown) => {
        this.pending--;
        if (this.pending === 0 && this.saved) {
          this.settings.set(this.saved);
        }
        this.toast(problemMessage(error, 'We could not save that change. Try again.'));
      },
    });
  }

  private toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000 });
  }
}
