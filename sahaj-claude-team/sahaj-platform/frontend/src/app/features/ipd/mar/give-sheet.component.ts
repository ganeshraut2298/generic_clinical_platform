import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { sheetToast } from '../../../shared/ui/sheet-toast';
import { digitsOnly } from '../../pin/pin.model';
import { isOutsideScope } from '../ipd-refusal';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { IdentityBarComponent } from '../emr/identity-bar.component';
import { MarApi } from './mar.api';
import {
  CHECKED_BY_LABEL,
  CHOOSE_NURSE,
  CHOOSE_REASON,
  DoseInput,
  DoseRecorded,
  GIVE_FIELD_ORDER,
  GiveErrors,
  GiveField,
  HIGH_ALERT_SECOND_CHECK,
  Mar,
  MarOrder,
  MarSlot,
  PIN_PLACEHOLDER,
  REASON_LABEL,
  SAID_LABEL,
  SECOND_NURSE_SMALL,
  STEP_ONE,
  THEIR_PIN_LABEL,
  WARD_LINE,
  WRISTBAND_LABEL,
  WRISTBAND_PLACEHOLDER,
  classifyGiveError,
  giveSubtitle,
  giveTitle,
  givenInput,
  givenToast,
  highAlertStepTitle,
  isFourDigitPin,
  mismatchLine,
  mismatchToast,
  notGivenInput,
  notGivenToast,
  notGivingStepTitle,
  requestSignature,
} from './mar.model';

export interface GiveSheetData {
  /** The MAR the tab holds: the patient, the nurses for the second check and the reasons. */
  mar: Mar;
  order: MarOrder;
  slot: MarSlot;
}

/** `mar`: a dose was recorded (201 or 200) and this is the MAR now. `reload`: the MAR on screen is out of date. */
export type GiveResult = { mar: Mar } | { reload: true };

/** The id of the sheet title, for the dialog's `ariaLabelledBy`. */
export const GIVE_TITLE_ID = 'sf-gh';

const FIELD_CONTROL: Record<GiveField, string> = {
  wristband: 'sf-id',
  identityConfirmed: 'sf-said',
  secondNurseId: 'sf-wit',
  secondNursePin: 'sf-pin',
  reasonKey: 'sf-why',
};

/**
 * Give sheet (SAH-32), mockup `sfGive`, `sfgiveok`, `sfnogive`: "Give · <medicine>", the identity
 * bar, step 1 (wristband box and the statement), step 2 for a high-alert medicine (second nurse and
 * their PIN), the last step (reason), and the footer "Record not given" and "Given".
 *
 * - There is no "Scan wristband" button (OQ-32-1, DEC-018): the mockup's button copies the patient's
 *   own UHID into the box, so the check would pass by itself. The box takes typing or a keyboard-type
 *   scanner; Enter in it does nothing (the scanner's Enter must not give the dose).
 * - Every check is the server's; the sheet sends what was entered and shows the first refusal. The
 *   ticks are only the nurse's statement. The second nurse and the PIN are sent only for a high-alert
 *   medicine.
 * - clientKey (ADR-011 D8): made when the sheet opens, kept while the same request is retried, new
 *   when the outcome, the reason or the second nurse differs from the last request sent, and after
 *   409 `client-key-reused`. A slot is shown as given only after the server answered.
 * - Both buttons, the close button and Esc are off while a request runs. The PIN is cleared after
 *   every answer, success or failure. PIN errors (wrong, locked, not set, rate limit) show under
 *   "Their PIN"; the second nurse's `pin-not-set` is never the signed-in user's own PIN state.
 * - A wristband mismatch shows the toast and the line under the box, built from the MAR (the problem
 *   detail carries no name).
 * - After 409 `dose-recorded`, `order-state`, `order-not-verified`, `dose-not-due`,
 *   `dose-before-start`, `not-admitted` and `ipd-readonly` the sheet closes and the MAR is read again.
 * - After a wristband mismatch the box text is selected (not cleared), so the next scan replaces it.
 * - The dialog is opened with autoFocus off: this sheet places the focus (the box when it opens, the
 *   field that needs attention after a refusal).
 * - Names, the scanned text and the PIN are on screen and in form controls only.
 */
@Component({
  selector: 'app-give-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, IconComponent, IdentityBarComponent],
  styleUrl: './give-sheet.component.scss',
  templateUrl: './give-sheet.component.html',
})
export class GiveSheetComponent {
  private readonly api = inject(MarApi);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialogRef = inject<MatDialogRef<GiveSheetComponent, GiveResult>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly data = inject<GiveSheetData>(MAT_DIALOG_DATA);
  private readonly scopeRefusals = inject(ScopeRefusals);

  protected readonly titleId = GIVE_TITLE_ID;
  protected readonly order = this.data.order;
  protected readonly patient = this.data.mar.patient;
  protected readonly nurses = this.data.mar.secondNurses;
  protected readonly reasons = this.data.mar.notGivenReasons;
  protected readonly highAlert = !!this.data.order.highAlert;
  protected readonly title = giveTitle(this.data.order);
  protected readonly subtitle = giveSubtitle(this.data.order, this.data.slot);
  protected readonly stepOne = STEP_ONE;
  protected readonly stepTwo = highAlertStepTitle(this.data.order);
  protected readonly stepLast = notGivingStepTitle(!!this.data.order.highAlert);
  protected readonly wristbandLabel = WRISTBAND_LABEL;
  protected readonly wristbandPlaceholder = WRISTBAND_PLACEHOLDER;
  protected readonly saidLabel = SAID_LABEL;
  protected readonly secondSmall = SECOND_NURSE_SMALL;
  protected readonly checkedByLabel = CHECKED_BY_LABEL;
  protected readonly chooseNurse = CHOOSE_NURSE;
  protected readonly pinLabel = THEIR_PIN_LABEL;
  protected readonly pinPlaceholder = PIN_PLACEHOLDER;
  protected readonly reasonLabel = REASON_LABEL;
  protected readonly chooseReason = CHOOSE_REASON;
  protected readonly mismatchText = mismatchLine(this.data.mar.patient.name);
  protected readonly wardText = WARD_LINE;

  protected readonly running = signal(false);
  /** Refusals that belong to one control, shown under it. */
  protected readonly errors = signal<GiveErrors>({});
  /** The wristband does not match: the line under the box. */
  protected readonly mismatch = signal(false);
  /** A failure that is not about one control. */
  protected readonly failure = signal<string | null>(null);
  /** The last answer was one after which the ward line (T6) is shown. */
  private readonly wardAnswer = signal(false);
  /**
   * SAH-38 (ADR-014 D10, T6): for a high-alert medicine, after pin-locked, pin-not-set, rate-limit or
   * colleague-pin-limit, and always when there is no other nurse to choose. Never after wrong-pin or
   * either 503. There is no bypass behind it.
   */
  protected readonly showWardLine = computed(
    () => this.highAlert && (this.nurses.length === 0 || this.wardAnswer()),
  );

  protected readonly form = new FormGroup({
    wristband: new FormControl('', { nonNullable: true }),
    said: new FormControl(false, { nonNullable: true }),
    nurse: new FormControl('', { nonNullable: true }),
    pin: new FormControl('', { nonNullable: true }),
    reason: new FormControl('', { nonNullable: true }),
  });

  private key = newClientKey();
  /** What the last request sent for the server's replay comparison; null before the first request. */
  private sentSignature: string | null = null;

  constructor() {
    // SAH-34 AC 38: a refusal for this patient (here, or from the tab behind) closes this sheet; no dose is shown as given.
    this.scopeRefusals.watch(this.data.mar.admissionId, this.destroyRef, () => {
      this.clearPin();
      this.dialogRef.close();
    });
    effect(() => {
      this.dialogRef.disableClose = this.running();
    });
    // autoFocus is off (mar-tab.component.ts): once the dialog has opened, the focus goes to the box.
    this.dialogRef
      .afterOpened()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => afterNextRender(() => this.focus('sf-id'), { injector: this.injector }));
    this.destroyRef.onDestroy(() => this.form.reset());
  }

  // ---- Editing ----

  /** The box changed: the old mismatch line and any message under it are about the old text. */
  protected boxEdited(): void {
    this.mismatch.set(false);
    this.dropError('wristband');
  }

  /** Enter in the box must not give the dose: a barcode scanner ends its scan with Enter. */
  protected boxKey(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault();
    }
  }

  /** Another nurse in "Checked by": the ward line was about the nurse before. */
  protected nurseChosen(): void {
    this.dropError('secondNurseId');
    this.wardAnswer.set(false);
  }

  /** The PIN box is described by its reason and, when shown, the ward line. */
  protected pinDescribedBy(): string | null {
    const ids = [this.errors().secondNursePin ? 'sf-pin-err' : '', this.showWardLine() ? 'sf-ward' : ''].filter(Boolean);
    return ids.length > 0 ? ids.join(' ') : null;
  }

  protected pinEdited(): void {
    const control = this.form.controls.pin;
    const clean = digitsOnly(control.value, 4);
    if (clean !== control.value) {
      control.setValue(clean);
    }
    this.dropError('secondNursePin');
  }

  protected dropError(field: GiveField): void {
    if (this.errors()[field]) {
      const rest = { ...this.errors() };
      delete rest[field];
      this.errors.set(rest);
    }
  }

  // ---- Footer ----

  protected given(): void {
    if (this.running()) {
      return;
    }
    const entered = this.form.getRawValue();
    if (this.highAlert && !this.secondCheckComplete(entered)) {
      return;
    }
    this.send((key) => givenInput(key, this.data.slot, this.highAlert, entered));
  }

  /**
   * DEC-025 G-9: a high-alert GIVEN needs a second nurse and a 4-digit PIN. When one is missing the
   * contract's text shows under that field (under the PIN when both are missing), the focus goes there
   * and nothing is sent. The server stays
   * the authority and checks again.
   */
  private secondCheckComplete(entered: { nurse: string; pin: string }): boolean {
    // Like the server's answer for both missing: the one text goes under the PIN.
    const field: GiveField | null = !isFourDigitPin(entered.pin)
      ? 'secondNursePin'
      : !entered.nurse
        ? 'secondNurseId'
        : null;
    if (field === null) {
      return true;
    }
    if (field === 'secondNurseId') {
      // No second nurse: the PIN typed so far is not kept while the nurse is chosen.
      this.clearPin();
    }
    this.errors.set({ [field]: HIGH_ALERT_SECOND_CHECK });
    this.mismatch.set(false);
    this.failure.set(null);
    this.focusLater(FIELD_CONTROL[field]);
    return false;
  }

  protected notGiven(): void {
    if (this.running()) {
      return;
    }
    const { reason } = this.form.getRawValue();
    this.send((key) => notGivenInput(key, this.data.slot, reason));
  }

  protected cancel(): void {
    if (!this.running()) {
      this.dialogRef.close();
    }
  }

  // ---- Request ----

  private send(build: (clientKey: string) => DoseInput): void {
    let input = build(this.key);
    const signature = requestSignature(input);
    if (this.sentSignature !== null && this.sentSignature !== signature) {
      // The server compares outcome, reason and second nurse under one key: other values need a new key.
      this.key = newClientKey();
      input = build(this.key);
    }
    this.sentSignature = signature;
    this.running.set(true);
    this.errors.set({});
    this.mismatch.set(false);
    this.failure.set(null);
    this.api
      .give(this.order.orderId, input)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (answer) => {
          this.running.set(false);
          this.clearPin();
          this.recorded(answer, input);
        },
        error: (error: unknown) => {
          this.running.set(false);
          this.clearPin();
          this.failed(error);
        },
      });
  }

  /** 201, or 200 for a repeat of the clientKey: the dose is on record; the sheet closes with the MAR now. */
  private recorded(answer: DoseRecorded, sent: DoseInput): void {
    const name = this.order.drugName;
    if (sent.outcome === 'GIVEN') {
      this.toast(givenToast(name, answer.mar.patient.name));
    } else {
      const label =
        answer.dose.reason?.label ?? this.reasons.find((r) => r.key === sent.reasonKey)?.label ?? '';
      this.toast(notGivenToast(name, label));
    }
    this.dialogRef.close({ mar: answer.mar });
  }

  private failed(error: unknown): void {
    if (isOutsideScope(error)) {
      // SAH-34 AC 39: nothing was recorded. This sheet closes (it watches the same event), the page says so.
      this.scopeRefusals.report(this.data.mar.admissionId, true);
      return;
    }
    const failure = classifyGiveError(error);
    this.wardAnswer.set(failure.kind === 'pin' && failure.ward);
    switch (failure.kind) {
      case 'mismatch':
        this.mismatch.set(true);
        this.toast(mismatchToast(this.patient.name));
        this.focusLater('sf-id', true);
        return;
      case 'fields': {
        this.errors.set(failure.errors);
        this.toast(failure.message);
        const first = GIVE_FIELD_ORDER.find((f) => failure.errors[f]);
        if (first) {
          this.focusLater(FIELD_CONTROL[first]);
        }
        return;
      }
      case 'pin':
        if (this.highAlert) {
          this.errors.set({ secondNursePin: failure.message });
          this.focusLater(failure.focus === 'pin' ? 'sf-pin' : 'sf-wit');
        } else {
          this.failure.set(failure.message);
        }
        this.toast(failure.message);
        return;
      case 'reload':
        this.toast(failure.message);
        this.dialogRef.close({ reload: true });
        return;
      case 'key-reused':
        this.key = newClientKey();
        this.sentSignature = null;
        this.failure.set(failure.message);
        this.toast(failure.message);
        return;
      case 'notice':
        this.failure.set(failure.message);
        this.toast(failure.message);
        return;
    }
  }

  /** The PIN is cleared after every answer, success or failure. */
  private clearPin(): void {
    this.form.controls.pin.setValue('');
  }

  private toast(message: string): void {
    // On a phone the toast goes to the top: at the bottom it would sit over Given and Record not given.
    // It is not announced: the field alert or the live line in the sheet already says the same thing.
    this.snackBar.open(message, 'Close', sheetToast(8000, { announce: false }));
  }

  private focusLater(id: string, select = false): void {
    setTimeout(() => this.focus(id, select), 0);
  }

  /**
   * `select`: select the text too, so the next scan of a keyboard-wedge scanner replaces it instead
   * of being appended to it. The text stays visible for the nurse. Where the element cannot select
   * its text, the box is cleared for the same reason.
   */
  private focus(id: string, select = false): void {
    const target = this.host.nativeElement.querySelector<HTMLElement>(`#${id}`);
    if (!target) {
      return;
    }
    target.focus();
    if (!select) {
      return;
    }
    if (target instanceof HTMLInputElement) {
      target.select();
    } else {
      this.form.controls.wristband.setValue('');
    }
  }
}
