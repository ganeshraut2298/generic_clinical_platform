import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ShellStore } from '../../../core/shell/shell.store';
import { AdmissionsApi } from '../../ipd/admissions.api';
import { DischargeSettings, DischargeSettingsInput } from '../../ipd/discharge.model';
import { problemMessage } from '../config-format';
import { IpdAccess } from './ipd-access';
import { IpdGate } from './ipd-setup.store';

const LOAD_ERROR = 'We could not load the discharge setup. Try again.';

/**
 * State of Clinic setup → Discharge (SAH-27, key `ipdis`). Same shape as `AdmissionRulesStore`:
 * the IPD gate comes from `IpdAccess`, every write sends the whole setting and the answer replaces
 * the signal, and one write runs at a time. Only the owner edits; the server's `canEdit` and the
 * IPD state both switch the controls off, and the backend still checks.
 */
@Injectable()
export class DischargeSettingsStore {
  private readonly api = inject(AdmissionsApi);
  private readonly access = inject(IpdAccess);
  private readonly shell = inject(ShellStore);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  readonly settings = signal<DischargeSettings | null>(null);
  readonly loading = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly busy = signal(false);

  readonly isOwner = computed(() => this.shell.me()?.role === 'OWNER');
  readonly isReadonly = computed(() => this.access.state() === 'readonly');
  readonly canEdit = computed(
    () => this.isOwner() && !this.isReadonly() && (this.settings()?.canEdit ?? true),
  );
  readonly locked = computed(() => !this.canEdit() || this.busy());

  readonly gate = computed<IpdGate>(() => {
    if (this.access.available()) {
      return 'ready';
    }
    if (this.access.state() === 'off') {
      return 'off';
    }
    return this.access.failed() ? 'error' : 'checking';
  });

  constructor() {
    this.access.ensureLoaded();
    effect(() => {
      if (this.access.available() && this.settings() === null && !this.loading() && !this.loadError()) {
        this.load();
      }
    });
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api
      .dischargeSettings()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (settings) => {
          this.settings.set(settings);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(problemMessage(error, LOAD_ERROR));
        },
      });
  }

  retry(): void {
    if (this.access.failed()) {
      this.access.ensureLoaded();
    } else {
      this.load();
    }
  }

  /** Saves the whole setting. `failed` runs after the message is shown, to put the control back. */
  save(body: DischargeSettingsInput, failed?: () => void): void {
    if (this.busy() || !this.canEdit()) {
      failed?.();
      return;
    }
    this.busy.set(true);
    this.api
      .saveDischargeSettings(body)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (settings) => {
          this.settings.set(settings);
          this.busy.set(false);
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.toast(problemMessage(error, 'We could not save that change. Try again.'));
          failed?.();
        },
      });
  }

  toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000 });
  }
}
