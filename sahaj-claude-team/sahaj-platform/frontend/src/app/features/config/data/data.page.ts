import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ShellStore } from '../../../core/shell/shell.store';
import { formatWhen } from '../config-format';
import { IconComponent } from '../../../shared/icon/icon.component';
import { PatientApi } from '../../patients/patient.api';
import { PrivacyApi } from '../../privacy/privacy.api';
import {
  HFR_ID_MAX,
  PrivacySettings,
  RecordAccess,
  consentLine,
  consentLineMarathi,
} from '../../privacy/privacy.model';
import { problemMessage } from '../config-format';
import { ClinicApi } from '../platform/clinic.api';
import { TeamApi } from '../team/team.api';
import { TeamMember } from '../team/team.model';

export type PrivacySwitch = 'consentRequired' | 'promoConsent' | 'recordCopy';

/**
 * Privacy, ABDM and backup (SAH-17), mockup `cfgData`. Three owner-only switches saved at once,
 * the consent line, an HFR ID field, disabled ABDM placeholders (no ABDM call is made), static
 * record-keeping facts, and the owner-only "Who opened which record" list. Departures from the
 * mockup: no "Last: 2:00 AM today" backup line (no backup status exists) and "Export all data"
 * is disabled with a reason.
 */
@Component({
  selector: 'app-data-config',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, IconComponent],
  styleUrl: './data.page.scss',
  templateUrl: './data.page.html',
})
export class DataPage implements OnInit {
  private readonly privacyApi = inject(PrivacyApi);
  private readonly patientApi = inject(PatientApi);
  private readonly clinicApi = inject(ClinicApi);
  private readonly teamApi = inject(TeamApi);
  private readonly store = inject(ShellStore);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly hfrMax = HFR_ID_MAX;

  protected readonly settings = signal<PrivacySettings | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly localName = signal<string | null>(null);
  protected readonly doctors = signal<TeamMember[]>([]);
  protected readonly access = signal<RecordAccess[]>([]);
  protected readonly accessState = signal<'loading' | 'ready' | 'error'>('loading');
  private readonly hfrValue = signal('');

  protected readonly hfr = new FormControl('', {
    nonNullable: true,
    validators: [Validators.maxLength(HFR_ID_MAX)],
  });

  protected readonly isOwner = computed(() => this.store.me()?.role === 'OWNER');
  protected readonly line = computed(() => consentLine(this.store.me()?.clinic.name ?? 'this clinic'));
  protected readonly lineMarathi = computed(() => {
    const name = this.localName();
    return name ? consentLineMarathi(name) : null;
  });
  protected readonly hfrDirty = computed(() => this.hfrValue() !== (this.settings()?.hfrId ?? ''));

  ngOnInit(): void {
    this.hfr.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((value) => this.hfrValue.set(value.trim()));
    if (!this.isOwner()) {
      this.hfr.disable();
    }
    this.privacyApi.get().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (settings) => {
        this.settings.set(settings);
        this.hfr.setValue(settings.hfrId ?? '');
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(problemMessage(error, 'We could not load the privacy settings. Try again.'));
      },
    });
    // Optional extras: the page still works when any of these fail.
    this.clinicApi.getProfile().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (profile) => this.localName.set(profile.nameLocal?.trim() || null),
      error: () => undefined,
    });
    this.teamApi.list().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (members) =>
        this.doctors.set(members.filter((m) => m.role === 'DOCTOR' || m.role === 'VISITING_DOCTOR')),
      error: () => undefined,
    });
    if (this.isOwner()) {
      this.patientApi.recordAccess(5).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: (rows) => {
          this.access.set(rows);
          this.accessState.set('ready');
        },
        error: () => this.accessState.set('error'),
      });
    }
  }

  protected toggle(key: PrivacySwitch): void {
    const current = this.settings();
    if (!current || !this.isOwner() || this.saving()) {
      return;
    }
    this.save({ ...current, [key]: !current[key] }, 'We could not save that change. Try again.');
  }

  protected saveHfr(): void {
    const current = this.settings();
    if (!current || !this.isOwner() || this.saving() || this.hfr.invalid) {
      return;
    }
    const value = this.hfr.value.trim();
    this.save({ ...current, hfrId: value || null }, 'We could not save the HFR ID. Try again.');
  }

  protected initials(name: string): string {
    return name
      .replace(/^(Dr|Sr)\.?\s+/i, '')
      .split(/\s+/)
      .slice(0, 2)
      .map((part) => part.charAt(0).toUpperCase())
      .join('');
  }

  /** `11:05 AM` from an ISO date-time, in IST. */
  protected timeOf(iso: string): string {
    return formatWhen(iso).split(', ').slice(-1)[0];
  }

  private save(next: PrivacySettings, fallback: string): void {
    const before = this.settings();
    this.settings.set(next);
    this.saving.set(true);
    this.privacyApi.save(next).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.settings.set(saved);
        this.hfr.setValue(saved.hfrId ?? '');
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.settings.set(before);
        this.snackBar.open(problemMessage(error, fallback), 'Close', { duration: 5000 });
      },
    });
  }
}
