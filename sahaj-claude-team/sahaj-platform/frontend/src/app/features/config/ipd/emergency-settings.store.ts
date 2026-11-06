import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiError } from '../../../core/http/problem-detail';
import { ShellStore } from '../../../core/shell/shell.store';
import { EmergencyApi } from '../../ipd/emergency/emergency.api';
import { EmergencySettings, EmergencySettingsInput } from '../../ipd/emergency/emergency.model';
import { problemMessage } from '../config-format';
import { IpdAccess } from './ipd-access';
import { IpdGate } from './ipd-setup.store';

const LOAD_ERROR = 'We could not load the emergency setup. Try again.';
const SAVE_ERROR = 'We could not save that change. Try again.';

/**
 * State of Clinic setup → Emergency and triage (SAH-28, key `iper`). Same shape as
 * `DischargeSettingsStore`: the IPD gate comes from `IpdAccess`, every write sends the whole
 * setting and the answer replaces the signal, and one write runs at a time. Only the owner edits;
 * the server's `canEdit`, the IPD state and a `409 ipd-readonly` answer all switch the controls
 * off, and the backend still checks.
 */
@Injectable()
export class EmergencySettingsStore {
  private readonly api = inject(EmergencyApi);
  private readonly access = inject(IpdAccess);
  private readonly shell = inject(ShellStore);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  readonly settings = signal<EmergencySettings | null>(null);
  readonly loading = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly busy = signal(false);
  /** Set when a save answered 409 `ipd-readonly` while the page still thought IPD was writable. */
  private readonly serverReadonly = signal(false);

  readonly isOwner = computed(() => this.shell.me()?.role === 'OWNER');
  readonly isReadonly = computed(() => this.access.state() === 'readonly' || this.serverReadonly());
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
      .settings()
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

  /** The whole setting as the API takes it, for the page to change one part of. */
  input(): EmergencySettingsInput | null {
    const s = this.settings();
    return s
      ? {
          scale: s.scale,
          targets3: [...s.targets3],
          targets5: [...s.targets5],
          bays: s.bays,
          unknownPatientRegistration: s.unknownPatientRegistration,
        }
      : null;
  }

  /** Saves the whole setting. `failed` runs after the message is shown. */
  save(body: EmergencySettingsInput, failed?: () => void): void {
    if (this.busy() || !this.canEdit()) {
      failed?.();
      return;
    }
    this.busy.set(true);
    this.api
      .saveSettings(body)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (settings) => {
          this.settings.set(settings);
          this.busy.set(false);
        },
        error: (error: unknown) => {
          this.busy.set(false);
          if (error instanceof ApiError && error.problem.type.endsWith('ipd-readonly')) {
            this.serverReadonly.set(true);
          }
          this.toast(problemMessage(error, SAVE_ERROR));
          failed?.();
        },
      });
  }

  toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000 });
  }
}
