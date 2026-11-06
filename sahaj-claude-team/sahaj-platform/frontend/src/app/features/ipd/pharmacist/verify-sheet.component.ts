import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  Signal,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import { PinApi } from '../../pin/pin.api';
import {
  NotSetReason,
  PIN_RATE_LIMIT,
  PIN_STATUS_ERROR,
  PIN_UNAVAILABLE,
  PinStatus,
  digitsOnly,
  lockedNotice,
  notSetNotice,
} from '../../pin/pin.model';
import { SetPinSheetComponent } from '../../pin/set-pin-sheet.component';
import { IdentityBarComponent } from '../emr/identity-bar.component';
import { severityWord } from '../emr/emr.model';
import { timeOfInstant } from '../emergency/emergency.model';
import { PharmacistApi } from './pharmacist.api';
import {
  KEY_RELOAD,
  NO_FINDINGS,
  PIN_FORMAT,
  PharmacistActionResult,
  PharmacistCheckRow,
  LOCK_HINT,
  VERIFY_ERROR,
  verifySubtitle,
  verifyTitle,
} from './pharmacist.model';

export interface VerifySheetData {
  /** The row as the pharmacist saw it when the sheet opened: its `checkToken` is sent. */
  row: PharmacistCheckRow;
  /** `PharmacistChecks.verifierName`: the signed-in user, shown read-only. */
  verifierName: string;
  /** The same order in the panel, reloaded behind the sheet after a conflict. */
  live: Signal<PharmacistCheckRow | null>;
  /** Asks the panel to reload (called on a conflict). */
  onConflict: () => void;
}

/** What the PIN part of the sheet shows (docs/api/ipd.yaml PharmacistCheckRow, "PIN states"). */
export type PinGate =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'unavailable' }
  | { kind: 'notset'; reason: NotSetReason | null }
  | { kind: 'locked'; until: string | null }
  | { kind: 'ready' };

/** Longest wait before the status is read again once a lock should have ended. */
const RECHECK_MS = 15_000;
const SLACK_MS = 500;
const DEFAULT_RATE_WAIT_SECONDS = 60;

/** Where the focus goes for each state of the PIN part (the loading state keeps it where it is). */
const FOCUS_TARGET: Record<Exclude<PinGate['kind'], 'loading'>, string> = {
  ready: '#sf-vpin',
  notset: '[data-act=vf-setpin]',
  locked: '[data-act=vf-newpin]',
  error: '[data-act=vf-retry]',
  unavailable: '[data-testid=pin-unavailable]',
};

/**
 * Verify sheet (SAH-31), mockup `sfver` and `sfverok`: "Pharmacist check · <drug>", the identity
 * bar, the findings, "Verified by" (the signed-in user, read-only: the mockup's list of names is
 * not built), the masked "PIN" field, and the footer Cancel and "Verify order".
 *
 * - The sheet reads `GET /me/pin` when it opens. Not set (never, owner reset, switched off after too
 *   many wrong tries): a notice and "Set my PIN", which opens the Set PIN sheet on top and returns
 *   here with the PIN field after a save. Locked: a notice with the time, "Set a new PIN", and
 *   Verify off; the status is read again when the lock should have ended, and the server decides.
 *   Unavailable (503): a notice and Verify off.
 * - The PIN field is masked, numeric, never prefilled and cleared after every answer, success or
 *   failure. The PIN is never stored, logged or echoed. A wrong PIN shows the server's text under the
 *   field ("That PIN is not right. 2 tries left.") and puts the focus back in it.
 * - Verify order, Cancel and the close button are off while the request runs; Esc and a click
 *   outside do nothing then. A 429 switches Verify off for `retryAfterSeconds`.
 * - `checkToken` is the one of the row the pharmacist saw. On a conflict (order-state, order-blocked,
 *   order-checks-changed ...) the panel reloads behind the sheet, Verify stays off until the reload
 *   arrives, and the sheet then shows the order as it is now (or stays off if it is gone).
 */
@Component({
  selector: 'app-verify-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, IconComponent, IdentityBarComponent],
  styleUrl: './verify-sheet.component.scss',
  templateUrl: './verify-sheet.component.html',
})
export class VerifySheetComponent {
  private readonly pinApi = inject(PinApi);
  private readonly api = inject(PharmacistApi);
  private readonly dialog = inject(MatDialog);
  private readonly dialogRef = inject<MatDialogRef<VerifySheetComponent, PharmacistActionResult>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly data = inject<VerifySheetData>(MAT_DIALOG_DATA);

  protected readonly verifierName = this.data.verifierName;
  protected readonly severity = severityWord;
  protected readonly noFindings = NO_FINDINGS;
  protected readonly lockHint = LOCK_HINT;
  protected readonly unavailableText = PIN_UNAVAILABLE;
  protected readonly notSet = notSetNotice;

  protected readonly current = signal<PharmacistCheckRow>(this.data.row);
  protected readonly gate = signal<PinGate>({ kind: 'loading' });
  protected readonly running = signal(false);
  /** The wrong-PIN text, shown under the PIN field. */
  protected readonly pinError = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);
  /** Seconds left of a 429 wait; Verify is off while above 0. */
  protected readonly rateWait = signal(0);
  /** The order can not be verified from this sheet any more (conflict, or the role was refused). */
  protected readonly blocked = signal(false);

  protected readonly title = computed(() => verifyTitle(this.current()));
  protected readonly subtitle = computed(() => verifySubtitle(this.current()));
  protected readonly lockedText = computed(() => {
    const g = this.gate();
    return g.kind === 'locked' ? lockedNotice(timeOfInstant(g.until)) : '';
  });
  /** Only the kind: a new status of the same kind (the lock poll) does not move the focus. */
  private readonly gateKind = computed(() => this.gate().kind);
  protected readonly gateError = computed(() => {
    const g = this.gate();
    return g.kind === 'error' ? g.message : '';
  });
  protected readonly gateReason = computed(() => {
    const g = this.gate();
    return g.kind === 'notset' ? g.reason : null;
  });
  protected readonly canVerify = computed(
    () =>
      this.gate().kind === 'ready' &&
      this.current().canVerify &&
      !this.running() &&
      !this.blocked() &&
      this.rateWait() === 0,
  );
  protected readonly lockReason = computed(() => {
    const row = this.current();
    return row.canVerify ? null : row.verifyLockReason;
  });

  protected readonly form = new FormGroup({
    pin: new FormControl('', { nonNullable: true }),
  });

  /** `undefined` = no conflict is waiting for the reload; else the live row seen when it happened. */
  private pendingBase: PharmacistCheckRow | null | undefined = undefined;
  /** The dialog has finished opening: the sheet may place the focus. */
  private opened = false;
  private unlockTimer: ReturnType<typeof setTimeout> | null = null;
  private rateTimer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    effect(() => {
      this.dialogRef.disableClose = this.running();
    });
    // The dialog is opened with autoFocus off (pharmacist-panel.component.ts), so only this sheet
    // places the focus; the focus trap of the dialog stays on. Once the dialog has opened, the focus
    // goes to the PIN field, or, when there is no PIN to type, to the first action of the notice
    // (Set my PIN, Set a new PIN, Try again) or to the notice itself. A status that answers before
    // the dialog has opened waits for afterOpened. Later changes of the state (a new PIN saved, the
    // lock ended, a 409 not-set) move it again; nothing else does.
    this.dialogRef
      .afterOpened()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.opened = true;
        afterNextRender(() => this.focusGateIfFree(), { injector: this.injector });
      });
    effect(() => {
      const kind = this.gateKind();
      if (kind !== 'loading') {
        untracked(() => {
          if (this.opened) {
            this.focusLater(FOCUS_TARGET[kind]);
          }
        });
      }
    });
    effect(() => {
      const live = this.data.live();
      const base = untracked(() => this.pendingBase);
      if (base === undefined || live === base) {
        return;
      }
      untracked(() => {
        this.pendingBase = undefined;
        if (live !== null) {
          this.current.set(live);
          this.blocked.set(!live.canVerify);
        }
      });
    });
    this.destroyRef.onDestroy(() => {
      this.stopUnlockTimer();
      this.stopRateTimer();
      this.form.reset({ pin: '' });
    });
    this.loadStatus();
  }

  // ---- PIN status ----

  protected loadStatus(): void {
    this.gate.set({ kind: 'loading' });
    this.pinApi
      .status()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (status) => this.applyStatus(status),
        error: (error: unknown) =>
          this.gate.set({ kind: 'error', message: problemMessage(error, PIN_STATUS_ERROR) }),
      });
  }

  private applyStatus(status: PinStatus): void {
    this.stopUnlockTimer();
    if (!status.available) {
      this.gate.set({ kind: 'unavailable' });
    } else if (status.state === 'NOT_SET') {
      this.gate.set({ kind: 'notset', reason: status.notSetReason });
    } else if (status.state === 'LOCKED') {
      this.gate.set({ kind: 'locked', until: status.lockedUntil });
      this.scheduleUnlockCheck(status.lockedUntil);
    } else {
      this.gate.set({ kind: 'ready' });
    }
  }

  /**
   * The lock should end at `lockedUntil`. The device clock only decides when to ask again; the
   * server's status decides whether the PIN works (a device clock that is wrong changes nothing but
   * the moment of the question).
   */
  private scheduleUnlockCheck(until: string | null): void {
    this.stopUnlockTimer();
    const ends = until ? Date.parse(until) : Number.NaN;
    const left = ends - Date.now();
    const wait = Number.isNaN(ends) || left <= 0 ? RECHECK_MS : left + SLACK_MS;
    this.unlockTimer = setTimeout(() => {
      this.unlockTimer = null;
      this.pinApi
        .status()
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          // A status that still says LOCKED schedules the next question itself (applyStatus).
          next: (status) => this.applyStatus(status),
          error: () => this.scheduleUnlockCheck(null),
        });
    }, wait);
  }

  private stopUnlockTimer(): void {
    if (this.unlockTimer !== null) {
      clearTimeout(this.unlockTimer);
      this.unlockTimer = null;
    }
  }

  /** "Set my PIN" and "Set a new PIN": the Set PIN sheet opens on top; after a save the PIN field is back. */
  protected openSetPin(): void {
    this.dialog
      .open<SetPinSheetComponent, undefined, PinStatus>(SetPinSheetComponent, {
        panelClass: 'sheet-panel',
        width: '520px',
        maxWidth: '100vw',
        ariaLabelledBy: 'sp-title',
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((status) => {
        if (status) {
          this.failure.set(null);
          this.applyStatus(status);
        }
      });
  }

  // ---- Verify ----

  protected typed(): void {
    const control = this.form.controls.pin;
    const clean = digitsOnly(control.value, 4);
    if (clean !== control.value) {
      control.setValue(clean);
    }
    this.pinError.set(null);
  }

  protected submit(): void {
    if (!this.canVerify()) {
      return;
    }
    const pin = this.form.controls.pin.value;
    this.failure.set(null);
    if (!/^[0-9]{4}$/.test(pin)) {
      this.pinError.set(PIN_FORMAT);
      this.focus('#sf-vpin');
      return;
    }
    this.pinError.set(null);
    this.running.set(true);
    const row = this.current();
    this.api
      .verify(row.orderId, { pin, checkToken: row.check.checkToken })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (answer) => {
          this.running.set(false);
          this.clearPin();
          this.dialogRef.close(answer);
        },
        error: (error: unknown) => {
          this.running.set(false);
          this.clearPin();
          this.failed(error);
        },
      });
  }

  private clearPin(): void {
    this.form.controls.pin.setValue('');
  }

  private failed(error: unknown): void {
    if (!(error instanceof ApiError)) {
      this.failure.set(VERIFY_ERROR);
      return;
    }
    const type = error.problem.type;
    const detail = error.problem.detail;
    if (error.status === 403 && type.endsWith('wrong-pin')) {
      this.pinError.set(problemMessage(error, VERIFY_ERROR));
      this.focus('#sf-vpin');
      return;
    }
    if (error.status === 403 && type.endsWith('pin-locked')) {
      const seconds = error.problem.retryAfterSeconds;
      const until = seconds ? new Date(Date.now() + seconds * 1000).toISOString() : null;
      this.gate.set({ kind: 'locked', until });
      // The server's status has the exact time; ask for it.
      this.scheduleUnlockCheck(until);
      this.pinApi
        .status()
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({ next: (status) => this.applyStatus(status), error: () => undefined });
      return;
    }
    if (error.status === 409 && type.endsWith('pin-not-set')) {
      this.readNotSetReason();
      return;
    }
    if (error.status === 429) {
      this.startRateWait(error.problem.retryAfterSeconds ?? DEFAULT_RATE_WAIT_SECONDS);
      this.failure.set(detail ?? PIN_RATE_LIMIT);
      return;
    }
    if (error.status === 503) {
      this.gate.set({ kind: 'unavailable' });
      return;
    }
    if (error.status === 400) {
      const pinField = error.fieldErrors.find((f) => f.field === 'pin');
      const tokenField = error.fieldErrors.find((f) => f.field === 'checkToken');
      if (pinField) {
        this.pinError.set(pinField.message || PIN_FORMAT);
        this.focus('#sf-vpin');
      }
      if (tokenField) {
        this.failure.set(tokenField.message || KEY_RELOAD);
        this.conflict();
      } else if (!pinField) {
        this.failure.set(problemMessage(error, VERIFY_ERROR));
      }
      return;
    }
    if (error.status === 409 || error.status === 404) {
      // order-state, order-blocked, order-checks-changed, not-admitted, ipd-readonly, not found.
      this.failure.set(problemMessage(error, VERIFY_ERROR));
      this.conflict();
      return;
    }
    if (error.status === 403) {
      // self-verification, forbidden, module-off: nothing more can be done from this sheet.
      this.failure.set(problemMessage(error, VERIFY_ERROR));
      this.blocked.set(true);
      return;
    }
    this.failure.set(problemMessage(error, VERIFY_ERROR));
  }

  /**
   * A 409 `pin-not-set`: the server's status says why (`notSetReason`), not the words of the answer.
   * If the status cannot be read, the generic "You have not set your PIN yet" text is shown.
   */
  private readNotSetReason(): void {
    this.gate.set({ kind: 'loading' });
    this.pinApi
      .status()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (status) =>
          status.available && status.state === 'NOT_SET'
            ? this.gate.set({ kind: 'notset', reason: status.notSetReason })
            : this.applyStatus(status),
        error: () => this.gate.set({ kind: 'notset', reason: null }),
      });
  }

  /** The order changed under the sheet: Verify stays off until the panel behind has reloaded. */
  private conflict(): void {
    this.blocked.set(true);
    this.pendingBase = this.data.live();
    this.data.onConflict();
  }

  private startRateWait(seconds: number): void {
    this.stopRateTimer();
    this.rateWait.set(Math.max(1, Math.ceil(seconds)));
    this.rateTimer = setInterval(() => {
      const next = this.rateWait() - 1;
      this.rateWait.set(Math.max(0, next));
      if (next <= 0) {
        this.stopRateTimer();
      }
    }, 1000);
  }

  private stopRateTimer(): void {
    if (this.rateTimer !== null) {
      clearInterval(this.rateTimer);
      this.rateTimer = null;
    }
  }

  protected cancel(): void {
    if (!this.running()) {
      this.dialogRef.close();
    }
  }

  /** Right after the dialog has opened: the state's target takes the focus unless the pharmacist is typing. */
  private focusGateIfFree(): void {
    const kind = this.gateKind();
    if (kind === 'loading') {
      return;
    }
    const active = this.host.nativeElement.ownerDocument.activeElement;
    const typing = active instanceof HTMLInputElement && this.host.nativeElement.contains(active);
    if (!typing) {
      this.focus(FOCUS_TARGET[kind]);
    }
  }

  private focusLater(selector: string): void {
    setTimeout(() => this.focus(selector), 0);
  }

  private focus(selector: string): void {
    this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus();
  }
}
