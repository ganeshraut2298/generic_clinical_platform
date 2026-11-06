import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable } from 'rxjs';
import { ShellStore } from '../../../core/shell/shell.store';
import { AdmissionsApi } from '../../ipd/admissions.api';
import { AdmissionRules, TransferRules } from '../../ipd/ipd.model';
import { problemMessage } from '../config-format';
import { IpdAccess } from './ipd-access';
import { IpdGate } from './ipd-setup.store';

/**
 * State of Clinic setup → Admission and deposits (SAH-23). Same shape as `IpdSetupStore`: the IPD
 * gate comes from `IpdAccess`, every write returns the whole rules which replace the signal, and
 * one write runs at a time. Only the owner edits; the backend still checks.
 */
@Injectable()
export class AdmissionRulesStore {
  private readonly api = inject(AdmissionsApi);
  private readonly access = inject(IpdAccess);
  private readonly shell = inject(ShellStore);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  readonly rules = signal<AdmissionRules | null>(null);
  readonly loading = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly busy = signal(false);
  /** SAH-26 transfer rules, loaded with the deposit rules; read by every IPD role. */
  readonly transfer = signal<TransferRules | null>(null);
  readonly transferLoadError = signal<string | null>(null);

  readonly isOwner = computed(() => this.shell.me()?.role === 'OWNER');
  readonly isReadonly = computed(() => this.access.state() === 'readonly');
  readonly canEdit = computed(() => this.isOwner() && !this.isReadonly());
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
      if (this.access.available() && this.rules() === null && !this.loading() && !this.loadError()) {
        this.load();
      }
      if (this.access.available() && this.transfer() === null && !this.transferLoading && !this.transferLoadError()) {
        this.loadTransfer();
      }
    });
  }

  private transferLoading = false;

  loadTransfer(): void {
    this.transferLoading = true;
    this.transferLoadError.set(null);
    this.api
      .transferRules()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rules) => {
          this.transferLoading = false;
          this.transfer.set(rules);
        },
        error: (error: unknown) => {
          this.transferLoading = false;
          this.transferLoadError.set(problemMessage(error, 'We could not load the bed transfer rules. Try again.'));
        },
      });
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api
      .rules()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rules) => {
          this.rules.set(rules);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(problemMessage(error, 'We could not load the deposit rules. Try again.'));
        },
      });
  }

  retryTransfer(): void {
    this.loadTransfer();
  }

  retry(): void {
    if (this.access.failed()) {
      this.access.ensureLoaded();
    } else {
      this.load();
    }
  }

  /** Runs one write. `done` runs after the new rules are in; `failed` after the message is shown. */
  run(
    call: Observable<AdmissionRules>,
    fallback: string,
    hooks: { done?: (rules: AdmissionRules) => void; failed?: () => void } = {},
  ): void {
    if (this.busy() || !this.canEdit()) {
      hooks.failed?.();
      return;
    }
    this.busy.set(true);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (rules) => {
        this.rules.set(rules);
        this.busy.set(false);
        hooks.done?.(rules);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.toast(problemMessage(error, fallback));
        hooks.failed?.();
      },
    });
  }

  /** Saves the transfer rules (SAH-26). `failed` runs after the message is shown, to put the choice back. */
  runTransfer(call: Observable<TransferRules>, fallback: string, failed?: () => void): void {
    if (this.busy() || !this.canEdit()) {
      failed?.();
      return;
    }
    this.busy.set(true);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (rules) => {
        this.transfer.set(rules);
        this.busy.set(false);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.toast(problemMessage(error, fallback));
        failed?.();
      },
    });
  }

  toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000 });
  }
}
