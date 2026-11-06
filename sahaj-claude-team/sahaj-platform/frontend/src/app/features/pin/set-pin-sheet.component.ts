import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiError } from '../../core/http/problem-detail';
import { IconComponent } from '../../shared/icon/icon.component';
import { problemMessage } from '../config/config-format';
import { PinApi } from './pin.api';
import {
  PIN_CHANGED_TOAST,
  PIN_SET_TOAST,
  PIN_STATUS_ERROR,
  PIN_UNAVAILABLE,
  PinSetInput,
  PinStatus,
  SAVE_PIN_ERROR,
  SEND_CODE_ERROR,
  SetPinErrors,
  SetPinField,
  countdownText,
  digitsOnly,
  setSheetTitle,
  validateSetPin,
} from './pin.model';

/** Status 0 (no connection), a server error or anything that is not an API answer: the save may have happened. */
function isLostAnswer(error: unknown): boolean {
  return !(error instanceof ApiError) || error.status === 0 || (error.status >= 500 && error.status !== 503);
}

const DEFAULT_RESEND_SECONDS = 30;

/**
 * Set PIN sheet (SAH-31, ADR-010 D7): "Set my PIN" or "Change my PIN". Not in the mockup, which
 * shows only a "Send PIN link" button; the layout follows the sign-in code step and the other sheets.
 *
 * - The sheet reads `GET /me/pin` when it opens (the title, the masked mobile, whether PIN checks
 *   are available). "Send code" sends a 6-digit code to the person's own mobile; the number comes
 *   from the server, never from this sheet. Then the fields code, new PIN and confirm appear.
 * - The two PIN fields are masked, numeric, kept in the form only until the request is sent, and
 *   cleared after every answer that is not a success. They are never stored, logged or echoed.
 * - Send code, Resend code, Save PIN, Cancel and the close button are off while a request runs; Esc
 *   and a click outside do nothing then. Resend shows a countdown from the server's wait.
 * - After a lost answer (no connection or a server error) the sheet reads `GET /me/pin` again and
 *   shows success when `setAt` changed; a used code cannot be sent twice.
 * - The dialog closes with the new `PinStatus` on success and with nothing on Cancel.
 */
@Component({
  selector: 'app-set-pin-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, IconComponent],
  styleUrl: './set-pin-sheet.component.scss',
  templateUrl: './set-pin-sheet.component.html',
})
export class SetPinSheetComponent {
  private readonly api = inject(PinApi);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialogRef = inject<MatDialogRef<SetPinSheetComponent, PinStatus>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly status = signal<PinStatus | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly sending = signal(false);
  protected readonly saving = signal(false);
  protected readonly codeSent = signal(false);
  /** The masked number the last code went to (from the send answer, else from the status). */
  protected readonly mobile = signal('');
  protected readonly resendSeconds = signal(0);
  protected readonly errors = signal<SetPinErrors>({});
  protected readonly failure = signal<string | null>(null);
  /** The server cannot check or save PINs right now (503 or `available` false). */
  protected readonly unavailable = signal(false);

  protected readonly unavailableText = PIN_UNAVAILABLE;
  protected readonly busy = computed(() => this.sending() || this.saving());
  protected readonly title = computed(() => {
    const s = this.status();
    return s ? setSheetTitle(s.state) : 'Set my PIN';
  });
  protected readonly resendLabel = computed(() => countdownText(this.resendSeconds()));

  protected readonly form = new FormGroup({
    code: new FormControl('', { nonNullable: true }),
    newPin: new FormControl('', { nonNullable: true }),
    confirmPin: new FormControl('', { nonNullable: true }),
  });

  private timer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    effect(() => {
      this.dialogRef.disableClose = this.busy();
    });
    this.destroyRef.onDestroy(() => {
      this.stopCountdown();
      this.form.reset({ code: '', newPin: '', confirmPin: '' });
    });
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api
      .status()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (status) => {
          this.loading.set(false);
          this.status.set(status);
          this.mobile.set(status.mobileMasked);
          this.unavailable.set(!status.available);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(problemMessage(error, PIN_STATUS_ERROR));
        },
      });
  }

  /** Keeps digits only, at most `max`. */
  protected typed(field: SetPinField, max: number): void {
    const control = this.form.controls[field];
    const clean = digitsOnly(control.value, max);
    if (clean !== control.value) {
      control.setValue(clean);
    }
    if (this.errors()[field]) {
      const rest = { ...this.errors() };
      delete rest[field];
      this.errors.set(rest);
    }
  }

  protected sendCode(): void {
    if (this.busy() || this.resendSeconds() > 0 || this.unavailable()) {
      return;
    }
    this.sending.set(true);
    this.failure.set(null);
    this.api
      .sendCode()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (sent) => {
          this.sending.set(false);
          this.codeSent.set(true);
          this.mobile.set(sent.mobileMasked);
          this.startCountdown(sent.resendAfterSeconds ?? DEFAULT_RESEND_SECONDS);
          this.form.controls.code.setValue('');
          this.focusLater('sp-code');
        },
        error: (error: unknown) => {
          this.sending.set(false);
          this.sendFailed(error);
        },
      });
  }

  private sendFailed(error: unknown): void {
    if (error instanceof ApiError) {
      if (error.status === 503) {
        this.unavailable.set(true);
        return;
      }
      if (error.status === 429) {
        this.startCountdown(error.problem.retryAfterSeconds ?? DEFAULT_RESEND_SECONDS);
      }
    }
    this.failure.set(problemMessage(error, SEND_CODE_ERROR));
  }

  protected save(): void {
    if (this.busy() || this.unavailable() || !this.codeSent()) {
      return;
    }
    const draft: PinSetInput = this.form.getRawValue();
    const errors = validateSetPin(draft);
    this.errors.set(errors);
    this.failure.set(null);
    const first = (['code', 'newPin', 'confirmPin'] as const).find((f) => errors[f]);
    if (first) {
      this.focus(first === 'code' ? 'sp-code' : first === 'newPin' ? 'sp-new' : 'sp-confirm');
      return;
    }
    this.saving.set(true);
    const before = this.status();
    this.api
      .set(draft)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (status) => {
          this.saving.set(false);
          this.done(status, before);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.clearPins();
          this.saveFailed(error, before);
        },
      });
  }

  private done(status: PinStatus, before: PinStatus | null): void {
    this.form.reset({ code: '', newPin: '', confirmPin: '' });
    this.snackBar.open(before?.state === 'NOT_SET' ? PIN_SET_TOAST : PIN_CHANGED_TOAST, 'Close', {
      duration: 6000,
    });
    this.dialogRef.close(status);
  }

  private saveFailed(error: unknown, before: PinStatus | null): void {
    if (isLostAnswer(error)) {
      this.recheck(before);
      return;
    }
    if (!(error instanceof ApiError)) {
      this.failure.set(SAVE_PIN_ERROR);
      return;
    }
    if (error.status === 503) {
      this.unavailable.set(true);
      return;
    }
    if (error.status === 400) {
      const shown: SetPinErrors = {};
      let other: string | null = null;
      for (const item of error.fieldErrors) {
        if (item.field === 'code' || item.field === 'newPin' || item.field === 'confirmPin') {
          shown[item.field] ??= item.message;
        } else {
          other ??= item.message;
        }
      }
      this.errors.set(shown);
      if (shown.code) {
        this.form.controls.code.setValue('');
        this.focus('sp-code');
      } else if (shown.newPin) {
        this.focus('sp-new');
      } else if (shown.confirmPin) {
        this.focus('sp-confirm');
      }
      if (other !== null || Object.keys(shown).length === 0) {
        this.failure.set(other ?? problemMessage(error, SAVE_PIN_ERROR));
      }
      return;
    }
    // 423 (code locked), 429 (rate limit), 409 (concurrent change) and the rest: the server's text.
    this.failure.set(problemMessage(error, SAVE_PIN_ERROR));
  }

  /** The answer was lost: read the status; a new `setAt` means the PIN was saved. */
  private recheck(before: PinStatus | null): void {
    this.saving.set(true);
    this.api
      .status()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (status) => {
          this.saving.set(false);
          if (status.state !== 'NOT_SET' && status.setAt !== null && status.setAt !== (before?.setAt ?? null)) {
            this.done(status, before);
          } else {
            this.status.set(status);
            this.failure.set(SAVE_PIN_ERROR);
          }
        },
        error: () => {
          this.saving.set(false);
          this.failure.set(SAVE_PIN_ERROR);
        },
      });
  }

  protected cancel(): void {
    if (!this.busy()) {
      this.dialogRef.close();
    }
  }

  private clearPins(): void {
    this.form.patchValue({ newPin: '', confirmPin: '' });
  }

  private startCountdown(seconds: number): void {
    this.stopCountdown();
    this.resendSeconds.set(Math.max(0, seconds));
    if (this.resendSeconds() === 0) {
      return;
    }
    this.timer = setInterval(() => {
      const next = this.resendSeconds() - 1;
      this.resendSeconds.set(Math.max(0, next));
      if (next <= 0) {
        this.stopCountdown();
      }
    }, 1000);
  }

  private stopCountdown(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  private focusLater(id: string): void {
    setTimeout(() => this.focus(id), 0);
  }

  private focus(id: string): void {
    this.host.nativeElement.querySelector<HTMLElement>(`#${id}`)?.focus();
  }
}
