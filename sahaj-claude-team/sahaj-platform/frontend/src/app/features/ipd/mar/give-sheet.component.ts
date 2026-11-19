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
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { map } from 'rxjs';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { sheetToast } from '../../../shared/ui/sheet-toast';
import { digitsOnly } from '../../pin/pin.model';
import { isOutsideScope } from '../ipd-refusal';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { IdentityBarComponent } from '../emr/identity-bar.component';
import { GiveSheetPresence } from './give-sheet-presence';
import { layoutHeight } from './layout-height';
import { MarApi } from './mar.api';
import { UndoToast } from './undo-toast.component';
import {
  CHECKED_BY_LABEL,
  CHECKED_BY_SYSTEM,
  CHOOSE_NURSE,
  CHOOSE_REASON,
  CODE_LABEL,
  CODE_PLACEHOLDER,
  CheckLine,
  DoseInput,
  DoseRecorded,
  GIVE_FIELD_ORDER,
  GiveErrors,
  GiveField,
  HIGH_ALERT_SECOND_CHECK,
  MISMATCH_LINE,
  Mar,
  MarOrder,
  MarSlot,
  NO_CODE_LOADED,
  OTHER_NOTE_LABEL,
  OTHER_REASON_KEY,
  OVERRIDE_CANCEL,
  OVERRIDE_INPUT_KEYS,
  OVERRIDE_LINE,
  OVERRIDE_NOTE_LABEL,
  OVERRIDE_OPEN,
  OVERRIDE_REASON_LEGEND,
  PIN_PLACEHOLDER,
  PrnView,
  REASON_LABEL,
  SAID_LABEL,
  SECOND_NURSE_SMALL,
  STAT_NOTICE,
  STEP_CODE,
  STEP_ONE,
  THEIR_PIN_LABEL,
  WARD_LINE,
  WRISTBAND_LABEL,
  WRISTBAND_PLACEHOLDER,
  checkLines,
  classifyGiveError,
  giveSubtitle,
  giveTitle,
  givenInput,
  highAlertStepTitle,
  isFourDigitPin,
  notGivenInput,
  notGivenToast,
  notGivingStepTitle,
  prnView,
  requestSignature,
} from './mar.model';

export interface GiveSheetData {
  /** The MAR the tab holds: the patient, the nurses for the second check and the reasons. */
  mar: Mar;
  order: MarOrder;
  /** The slot to give; null for a PRN line (no slot: the dose is recorded now). */
  slot: MarSlot | null;
}

/** `mar`: a dose was recorded (201 or 200) and this is the MAR now. `reload`: the MAR on screen is out of date. */
export type GiveResult = { mar: Mar } | { reload: true };

/** The id of the sheet title, for the dialog's `ariaLabelledBy`. */
export const GIVE_TITLE_ID = 'sf-gh';

const FIELD_CONTROL: Record<GiveField, string> = {
  wristband: 'sf-id',
  medicineCode: 'sf-code',
  overrideReason: 'sf-ov-reason',
  overrideNote: 'sf-ov-note',
  identityConfirmed: 'sf-said',
  secondNurseId: 'sf-wit',
  secondNursePin: 'sf-pin',
  reasonKey: 'sf-why',
  note: 'sf-why-note',
};

/**
 * Give sheet (SAH-32, SAH-76-4), mockup `sfGive`, `sfgiveok`, `sfnogive` and the reference give flow:
 * "Give · <medicine>", the identity bar, step 1 (wristband box and the statement), step 2 (medicine
 * code), the panel "Checked by the system", step 3 for a high-alert medicine (second nurse and their
 * PIN), the last step (reason), "Cannot scan the medicine? Manual override" and the footer "Record not given" and
 * "Given".
 *
 * - There is no "Scan wristband" button (OQ-32-1, DEC-018): the mockup's button copies the patient's
 *   own UHID into the box, so the check would pass by itself. The boxes take typing or a
 *   keyboard-type scanner; Enter in them does nothing (the scanner's Enter must not give the dose).
 * - Every check is the server's, in the order of the contract; the sheet sends what was entered and
 *   shows the first refusal. The patient and the medicine are compared by the server when Given is
 *   tapped, so the panel does not show a tick for them. A wrong patient or a wrong medicine is a
 *   red STOP, Given stays off, and the override is not offered for that item until the box is
 *   changed (OQ-76-17).
 * - A PRN line opens this sheet without a slot; a STAT dose of an order still waiting for the
 *   pharmacist shows the STAT notice (B2).
 * - clientKey (ADR-011 D8): made when the sheet opens, kept while the same request is retried, new
 *   when the outcome, the reason or the second nurse differs from the last request sent, and after
 *   409 `client-key-reused`. A slot is shown as given only after the server answered.
 * - Both buttons, the close button and Esc are off while a request runs. The PIN is cleared after
 *   every answer, success or failure.
 * - After 409 `dose-recorded`, `order-state`, `order-not-verified`, `order-on-hold`, `order-changed`,
 *   `dose-not-due`, `dose-before-start`, `not-admitted` and `ipd-readonly` the sheet closes and the MAR
 *   is read again. After `prn-too-soon` and `prn-max-reached` the sheet stays, says when, and Given is off.
 * - After 409 `no-code-loaded` (ipd.yaml 0.4.4 H1, DEC-065 A) the sheet stays and nothing is read again: the
 *   server's line shows under the code box in the polite region, in the warn style (not a STOP), the code box
 *   is cleared and the manual override is offered at once, closed and empty.
 * - After Given the toast "<medicine> recorded as given" carries Undo for the seconds the server
 *   sent (UndoToast).
 * - Rule (DEC-068): no toast while this sheet is open; refusals show in the sheet. Every refusal shows in the
 *   sheet (the field's own line, the line under the wristband or code box, `give-prn-lock` or `give-failure`),
 *   because a toast covered the sheet's Close button on a phone (QA D8). A toast is shown only after the sheet
 *   has closed, and the sheet closes first. This is a deliberate deviation from the mockup, which toasts the
 *   validation refusals (accepted by the human, DEC-068).
 * - `give-prn-lock` and `give-failure` sit at the bottom of the scrolling sheet and are alerts, read when they
 *   show. They are not focused (that would read them twice); they are scrolled into view, clear of the sticky
 *   footer (the sheet's `scroll-padding-bottom` is the footer's height). The lines under a box are polite
 *   live regions, and the focus goes to their box.
 * - Names, the scanned text, the medicine code, the override note and the PIN are on screen and in
 *   form controls only.
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
  private readonly undoToast = inject(UndoToast);
  private readonly dialogRef = inject<MatDialogRef<GiveSheetComponent, GiveResult>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly data = inject<GiveSheetData>(MAT_DIALOG_DATA);
  private readonly scopeRefusals = inject(ScopeRefusals);

  protected readonly titleId = GIVE_TITLE_ID;
  protected readonly order = this.data.order;
  protected readonly slot = this.data.slot;
  protected readonly patient = this.data.mar.patient;
  protected readonly nurses = this.data.mar.secondNurses;
  protected readonly reasons = this.data.mar.notGivenReasons;
  // 0.4.3: only the three input reasons are offered; WRISTBAND_DAMAGED is read only (never sent).
  protected readonly overrideReasons = this.data.mar.overrideReasons.filter((r) =>
    (OVERRIDE_INPUT_KEYS as readonly string[]).includes(r.key),
  );
  protected readonly highAlert = !!this.data.order.highAlert;
  protected readonly title = giveTitle(this.data.order);
  protected readonly subtitle = giveSubtitle(this.data.order, this.data.slot);
  protected readonly prn: PrnView | null =
    this.data.slot === null && this.data.order.prn ? prnView(this.data.order.prn, this.data.order) : null;
  protected readonly statNotice = this.data.slot?.beforeVerification ? STAT_NOTICE : null;
  protected readonly noCodeLine = this.data.order.hasMedicineCode ? null : NO_CODE_LOADED;
  protected readonly stepOne = STEP_ONE;
  protected readonly stepCode = STEP_CODE;
  protected readonly stepSecond = highAlertStepTitle(this.data.order);
  protected readonly stepLast = notGivingStepTitle(!!this.data.order.highAlert);
  protected readonly wristbandLabel = WRISTBAND_LABEL;
  protected readonly wristbandPlaceholder = WRISTBAND_PLACEHOLDER;
  protected readonly codeLabel = CODE_LABEL;
  protected readonly codePlaceholder = CODE_PLACEHOLDER;
  protected readonly saidLabel = SAID_LABEL;
  protected readonly secondSmall = SECOND_NURSE_SMALL;
  protected readonly checkedByLabel = CHECKED_BY_LABEL;
  protected readonly checkedBySystem = CHECKED_BY_SYSTEM;
  protected readonly chooseNurse = CHOOSE_NURSE;
  protected readonly pinLabel = THEIR_PIN_LABEL;
  protected readonly pinPlaceholder = PIN_PLACEHOLDER;
  protected readonly reasonLabel = REASON_LABEL;
  protected readonly chooseReason = CHOOSE_REASON;
  protected readonly otherNoteLabel = OTHER_NOTE_LABEL;
  protected readonly mismatchText = MISMATCH_LINE;
  protected readonly wardText = WARD_LINE;
  protected readonly overrideLine = OVERRIDE_LINE;
  protected readonly overrideNoteLabel = OVERRIDE_NOTE_LABEL;
  protected readonly overrideLegend = OVERRIDE_REASON_LEGEND;

  protected readonly running = signal(false);
  /** Refusals that belong to one control, shown under it. */
  protected readonly errors = signal<GiveErrors>({});
  /** The wristband does not match: the line under the box. */
  protected readonly mismatch = signal(false);
  /** The medicine code is not the ordered medicine: the server's line under the box. */
  protected readonly codeMismatch = signal<string | null>(null);
  /**
   * 409 `no-code-loaded` (0.4.4 H1): the server's line under the code box, warn style. Not a STOP: it sets
   * neither `stopped` nor `codeMismatch`, so Given and the override stay on. It leaves when the nurse types or
   * scans in the code box again, or when another answer arrives.
   */
  protected readonly noCodeNotice = signal<string | null>(null);
  /** The hint is not shown a second time while the notice carries the same words. */
  protected readonly showNoCodeHint = computed(() => this.noCodeLine !== null && this.noCodeNotice() !== this.noCodeLine);
  /** The PRN rule refused the dose: the line says when the next dose is allowed. */
  protected readonly prnLock = signal<string | null>(null);
  /** A failure that is not about one control. */
  protected readonly failure = signal<string | null>(null);
  /** The manual override is open (D4). */
  protected readonly overrideOpen = signal(false);
  protected readonly overrideReason = signal('');
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
    code: new FormControl('', { nonNullable: true }),
    overrideNote: new FormControl('', { nonNullable: true }),
    nurse: new FormControl('', { nonNullable: true }),
    pin: new FormControl('', { nonNullable: true }),
    reason: new FormControl('', { nonNullable: true }),
    reasonNote: new FormControl('', { nonNullable: true }),
  });

  private readonly values = toSignal(this.form.valueChanges.pipe(map(() => this.form.getRawValue())), {
    initialValue: this.form.getRawValue(),
  });

  /** A STOP on a box: Given stays off and the override is not offered until the box is changed. */
  protected readonly stopped = computed(() => this.mismatch() || this.codeMismatch() !== null);
  /** 0.4.3: the override covers the medicine code only, so it is offered only while the code box is empty. */
  private readonly codeEmpty = computed(() => this.values().code.trim() === '');
  protected readonly overrideOffered = computed(() => !this.stopped() && this.codeEmpty());
  /** The override that will be sent: open, and the code box empty. */
  private readonly overrideActive = computed(() => this.overrideOpen() && this.codeEmpty());
  protected readonly givenOff = computed(() => this.running() || this.stopped() || this.prnLock() !== null);
  /** "Checked by the system" shows once both boxes hold something, or the override is complete. */
  protected readonly checks = computed<CheckLine[] | null>(() => {
    const v = this.values();
    // The wristband is always scanned, also with an override (0.4.3).
    const scanned = v.wristband.trim() !== '' && v.code.trim() !== '';
    const overridden =
      v.wristband.trim() !== '' && this.overrideActive() && this.overrideReason() !== '' && v.overrideNote.trim().length >= 4;
    return scanned || overridden ? checkLines(this.data.mar, this.order, this.slot) : null;
  });
  protected readonly reasonIsOther = computed(() => this.values().reason === OTHER_REASON_KEY);
  protected readonly overrideOpenLabel = computed(() => (this.overrideOpen() ? OVERRIDE_CANCEL : OVERRIDE_OPEN));

  private key = newClientKey();
  /** What the last request sent for the server's replay comparison; null before the first request. */
  private sentSignature: string | null = null;
  /** After a PRN refusal the MAR on screen is out of date: closing the sheet reads it again. */
  private reloadOnClose = false;

  constructor() {
    // DEC-068: a late Undo answer shows no toast while this sheet is open; it waits for the sheet to go.
    this.destroyRef.onDestroy(inject(GiveSheetPresence).enter());
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
      .subscribe(() =>
        afterNextRender(
          () => {
            this.watchFooter();
            this.focus('sf-id');
          },
          { injector: this.injector },
        ),
      );
    this.destroyRef.onDestroy(() => this.form.reset());
  }

  // ---- Editing ----

  /** The box changed: the old mismatch line and any message under it are about the old text. */
  protected boxEdited(): void {
    this.mismatch.set(false);
    this.dropError('wristband');
  }

  protected codeEdited(): void {
    this.codeMismatch.set(null);
    this.noCodeNotice.set(null);
    this.dropError('medicineCode');
  }

  /** Enter in a scan box must not give the dose: a barcode scanner ends its scan with Enter. */
  protected boxKey(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault();
    }
  }

  /** "Cannot scan the medicine? Manual override" opens the reasons and the note; "Cancel override" closes them. */
  protected toggleOverride(): void {
    const open = !this.overrideOpen();
    this.overrideOpen.set(open);
    this.overrideReason.set('');
    this.form.controls.overrideNote.setValue('');
    this.dropError('overrideReason');
    this.dropError('overrideNote');
    if (open) {
      this.focusLater('sf-ov-reason');
    }
  }

  protected pickOverrideReason(key: string): void {
    this.overrideReason.set(key);
    this.dropError('overrideReason');
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
    if (this.givenOff()) {
      return;
    }
    const entered = this.form.getRawValue();
    if (this.highAlert && !this.secondCheckComplete(entered)) {
      return;
    }
    const override = this.overrideActive() ? { reasonKey: this.overrideReason(), note: entered.overrideNote } : null;
    this.send(
      (key) =>
        givenInput(key, this.order, this.slot, this.highAlert, {
          wristband: entered.wristband,
          said: entered.said,
          code: entered.code,
          override,
          nurse: entered.nurse,
          pin: entered.pin,
        }),
      true,
    );
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
    const { reason, reasonNote } = this.form.getRawValue();
    this.send((key) => notGivenInput(key, this.order, this.slot, reason, reasonNote), false);
  }

  protected cancel(): void {
    if (!this.running()) {
      this.dialogRef.close(this.reloadOnClose ? { reload: true } : undefined);
    }
  }

  // ---- Request ----

  /**
   * `clearStops`: the request is a GIVEN one. A STOP on a box stays through a Record not given (that request says
   * nothing about the wristband or the code); it leaves when the box is edited or a GIVEN request is sent.
   */
  private send(build: (clientKey: string) => DoseInput, clearStops: boolean): void {
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
    if (clearStops) {
      this.mismatch.set(false);
      this.codeMismatch.set(null);
    }
    // Not a STOP, so it goes on every request: a repeat `no-code-loaded` then draws again and is announced again.
    this.noCodeNotice.set(null);
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
    // The sheet closes first, then the toast: no toast is shown while the sheet is open (DEC-068).
    this.dialogRef.close({ mar: answer.mar });
    if (sent.outcome === 'GIVEN') {
      this.undoToast.showGiven({
        admissionId: this.data.mar.admissionId,
        drugName: name,
        dose: answer.dose,
        override: answer.dose.method === 'OVERRIDE',
      });
    } else {
      const label =
        answer.dose.reason?.label ?? this.reasons.find((r) => r.key === sent.reasonKey)?.label ?? '';
      this.toast(notGivenToast(name, label));
    }
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
        this.closeOverride();
        this.focusLater('sf-id', true);
        this.reveal('wristband-line');
        return;
      case 'override-refused':
        // 409 override-after-mismatch: the sheet stays open, the override closes, the line sits under the code box.
        this.codeMismatch.set(failure.message);
        this.closeOverride();
        this.focusLater('sf-code', true);
        this.reveal('code-line');
        return;
      case 'code-mismatch':
        this.codeMismatch.set(failure.message);
        this.closeOverride();
        this.focusLater('sf-code', true);
        this.reveal('code-line');
        return;
      case 'no-code-loaded':
        // 0.4.4 (H1): the sheet stays open, the MAR is not read again, no STOP and no toast (the line under the box
        // is the one place the text shows). The code box is cleared (an override is read only with an empty code) and
        // the toggle is offered at once; it is neither opened nor filled in. Wristband, identity and second nurse
        // stay, the PIN is already cleared, and the clientKey is kept (nothing was recorded).
        this.form.controls.code.setValue('');
        this.closeOverride();
        this.noCodeNotice.set(failure.message);
        this.focusLater('sf-code');
        this.reveal('code-line');
        return;
      case 'prn-lock':
        this.prnLock.set(failure.message);
        this.reloadOnClose = true;
        this.reveal('give-prn-lock');
        return;
      case 'fields': {
        // A key whose control is not drawn (a second nurse on a sheet that does not need one, a stale MAR) shows
        // nowhere: when no key has a control, the message goes to the line at the bottom.
        const drawn = GIVE_FIELD_ORDER.filter((f) => failure.errors[f] && this.controlDrawn(f));
        if (drawn.length === 0) {
          this.failure.set(failure.message);
          this.reveal('give-failure');
          return;
        }
        this.errors.set(failure.errors);
        this.focusLater(FIELD_CONTROL[drawn[0]]);
        return;
      }
      case 'pin':
        if (this.highAlert) {
          this.errors.set({ secondNursePin: failure.message });
          this.focusLater(failure.focus === 'pin' ? 'sf-pin' : 'sf-wit');
        } else {
          this.failure.set(failure.message);
          this.reveal('give-failure');
        }
        return;
      case 'reload':
        // The sheet closes, so there is no line in it: this is the one error that is said by a toast.
        this.dialogRef.close({ reload: true });
        this.toast(failure.message);
        return;
      case 'key-reused':
        this.key = newClientKey();
        this.sentSignature = null;
        this.failure.set(failure.message);
        this.reveal('give-failure');
        return;
      case 'notice':
        this.failure.set(failure.message);
        this.reveal('give-failure');
        return;
    }
  }

  /** Whether the template draws this field's control (and so its line); mirrors the `@if`s of the sheet. */
  private controlDrawn(field: GiveField): boolean {
    switch (field) {
      case 'secondNurseId':
      case 'secondNursePin':
        return this.highAlert;
      case 'overrideReason':
      case 'overrideNote':
        return this.overrideOffered() && this.overrideOpen();
      case 'note':
        return this.reasonIsOther();
      default:
        return true;
    }
  }

  /**
   * Brings a line into view once it is drawn, without moving the focus: `give-prn-lock` and `give-failure` are
   * alerts at the bottom of the scrolling sheet, and focusing them would make a screen reader read them twice.
   * `nearest` scrolls only as far as needed, and the sheet's `scroll-padding-bottom` keeps the line above the
   * sticky footer.
   */
  private reveal(testId: string): void {
    afterNextRender(
      () => {
        this.host.nativeElement.querySelector<HTMLElement>(`[data-testid="${testId}"]`)?.scrollIntoView?.({ block: 'nearest' });
      },
      { injector: this.injector },
    );
  }

  /**
   * The header and the footer are sticky, and both can wrap on a phone, so their heights are measured: the sheet
   * keeps them as `--sheet-head-h` and `--sheet-foot-h`, which `scroll-padding-top` and `scroll-padding-bottom`
   * read (`_console.scss`). Without them a scroll to a line or a box could park it under the footer, or push the
   * title and the Close button off the screen. The height is the layout height, rounded up: the dialog opens with a
   * scale animation, and `getBoundingClientRect` returns the scaled size while it runs (QA saw 67 px for a 77 px
   * footer, and the observer does not fire again because the layout size never changed). A whole number that rounds
   * down would leave a line a fraction of a pixel under the footer. The bars are measured again when the open
   * animation or transition ends.
   */
  private watchFooter(): void {
    const sheet = this.host.nativeElement.querySelector<HTMLElement>('.sheet');
    const head = sheet?.querySelector<HTMLElement>('.sheet-h');
    const foot = sheet?.querySelector<HTMLElement>('.sheet-f');
    if (!sheet || !foot || typeof ResizeObserver === 'undefined') {
      return;
    }
    const measure = (name: string, bar: HTMLElement | null | undefined) => {
      if (bar) {
        sheet.style.setProperty(name, `${Math.ceil(layoutHeight(bar))}px`);
      }
    };
    const sync = () => {
      measure('--sheet-head-h', head);
      measure('--sheet-foot-h', foot);
    };
    sync();
    const observer = new ResizeObserver(sync);
    observer.observe(foot);
    if (head) {
      observer.observe(head);
    }
    // animationend and transitionend bubble: the dialog's pane (or the sheet itself) hears the open animation end.
    const pane = sheet.closest<HTMLElement>('.cdk-overlay-pane') ?? sheet;
    pane.addEventListener('animationend', sync);
    pane.addEventListener('transitionend', sync);
    this.destroyRef.onDestroy(() => {
      observer.disconnect();
      pane.removeEventListener('animationend', sync);
      pane.removeEventListener('transitionend', sync);
    });
  }

  /** After a STOP the override is not offered: it closes with what was typed in it. */
  private closeOverride(): void {
    this.overrideOpen.set(false);
    this.overrideReason.set('');
    this.form.controls.overrideNote.setValue('');
  }

  /** The PIN is cleared after every answer, success or failure. */
  private clearPin(): void {
    this.form.controls.pin.setValue('');
  }

  private toast(message: string): void {
    // Rule: no toast while the sheet is open; refusals show in the sheet (DEC-068). So this runs only after
    // `dialogRef.close(...)` (Record not given done; a reload answer). While the sheet is open every refusal
    // shows in the sheet itself (field alert, give-failure, PRN lock, or the line under the box): a toast there
    // covered the sheet's Close button on a phone (QA D8).
    // On the MAR page it goes to the top on any width, clear of the bottom of the screen (QA D1). Nothing in
    // a closed sheet says it, so it is announced.
    this.snackBar.open(message, 'Close', sheetToast(8000, { top: true }));
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
