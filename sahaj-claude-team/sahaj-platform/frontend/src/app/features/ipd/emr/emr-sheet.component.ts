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
import { ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiError } from '../../../core/http/problem-detail';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import { EmrApi } from './emr.api';
import {
  CHECK_AND_ORDER,
  CHOOSE_REASON_TOAST,
  DRUG_MISSING,
  EMR_HIDDEN,
  EMR_LOAD_ERROR,
  EmrSheet,
  HARD_STOP_HEAD,
  LOGGED_LINE,
  MEDS_SUBTITLE,
  MEDS_TITLE,
  MedicineOrderAnswer,
  MedicineOrderInput,
  MedicineOrderRow,
  NO_ORDERS,
  ORDER_ERROR,
  ORDER_HINT,
  OrderErrors,
  REASON_LABEL,
  REASON_PLACEHOLDER,
  ORDER_LOCK_LINES,
  STATUS_PILL,
  OrderActionResult,
  ShownCheck,
  WARN_HEAD,
  changedLines,
  drugOptionLabel,
  holdLine,
  orderTags,
  prnLine,
  resumedToast,
  stoppedLine,
  emrSubtitle,
  orderButtonText,
  orderFieldErrors,
  orderSmallLine,
  orderedToast,
  reasonChangeNeedsNewKey,
  severityWord,
} from './emr.model';
import { IdentityBarComponent } from './identity-bar.component';
import { isOutsideScope } from '../ipd-refusal';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { ChangeSheetComponent, ChangeSheetData } from './change-sheet.component';
import { HoldSheetComponent, HoldSheetData } from './hold-sheet.component';
import { OrderFieldsComponent } from './order-fields.component';
import { QueryReplyComponent } from './query-reply.component';
import { StopSheetComponent, StopSheetData } from './stop-sheet.component';
import { OrderForm, blankDraft, drugDefaults, newOrderForm, toOrderInput, validateDraft } from './order-form.logic';
import { executionLines } from '../orders/order.model';

const ACTION_ERROR = 'We could not do that. Try again.';

/** First field with an error, in screen order, and the control that takes the focus. */
const FOCUS_ORDER: { field: keyof OrderErrors; id: string }[] = [
  { field: 'drugKey', id: 'emr-drug' },
  { field: 'frequencyKey', id: 'ord-freq' },
  { field: 'doseAmount', id: 'ord-dose' },
  { field: 'doseUnit', id: 'ord-unit' },
  { field: 'routeKey', id: 'ord-route' },
  { field: 'startAt', id: 'ord-start' },
  { field: 'durationDays', id: 'ord-duration' },
  { field: 'indication', id: 'ord-indication' },
  { field: 'prnMinIntervalMinutes', id: 'ord-interval' },
  { field: 'prnMaxPer24h', id: 'ord-max' },
  { field: 'overrideReasonKey', id: 'emr-reason' },
];

export interface EmrSheetData {
  admissionId: string;
}

/** The id of the "Medicines on chart" heading: focus goes here when a Stop button disappears. */
export const MEDS_HEADING_ID = 'emr-meds-h';

/**
 * EMR sheet (SAH-31), mockup `pcEmr` with `sfIdBar` and `sfOrdBlock`: the identity bar with the
 * allergy chip, the Diagnosis and Day strip, "Medicines on chart" with status pills and Stop, the
 * order form with the server's safety check, and the line that opening the record is logged.
 *
 * - Every rule is the server's. The sheet switches on `outcome` (PLACED, BLOCKED, NEEDS_REASON), not
 *   on the status code, and a medicine is never shown as ordered until the server says PLACED.
 * - Client key (ADR-009 D4): made when the sheet opens, kept while the same medicine, frequency and
 *   reason are retried (also from NEEDS_REASON to "Order with this reason"), replaced after PLACED,
 *   when the medicine or frequency changes, when a chosen reason changes, and after a 409
 *   `client-key-reused`.
 * - "Order with this reason" sends the `checkToken` of the warnings the doctor saw. If the server
 *   shows other warnings, the reason is cleared so it is chosen again against what is now on screen.
 * - Check and order, Order with this reason, Stop, Done and the close button are all off while a
 *   request runs; Esc and a click outside do nothing then.
 * - Findings, names and the allergy text are clinical data: on screen only, never logged or stored.
 */
@Component({
  selector: 'app-emr-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    IconComponent,
    IdentityBarComponent,
    OrderFieldsComponent,
    QueryReplyComponent,
  ],
  styleUrls: ['../ipd-bill.scss', './emr-sheet.component.scss'],
  templateUrl: './emr-sheet.component.html',
})
export class EmrSheetComponent {
  private readonly api = inject(EmrApi);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);
  private readonly dialogRef = inject<MatDialogRef<EmrSheetComponent>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly data = inject<EmrSheetData>(MAT_DIALOG_DATA);
  private readonly scopeRefusals = inject(ScopeRefusals);

  protected readonly medsTitle = MEDS_TITLE;
  protected readonly medsSubtitle = MEDS_SUBTITLE;
  protected readonly medsHeadingId = MEDS_HEADING_ID;
  protected readonly noOrders = NO_ORDERS;
  protected readonly orderLockLines = ORDER_LOCK_LINES;
  protected readonly orderHint = ORDER_HINT;
  protected readonly loggedLine = LOGGED_LINE;
  protected readonly hardStopHead = HARD_STOP_HEAD;
  protected readonly warnHead = WARN_HEAD;
  protected readonly reasonLabel = REASON_LABEL;
  protected readonly reasonPlaceholder = REASON_PLACEHOLDER;
  protected readonly hiddenText = EMR_HIDDEN;
  protected readonly pill = (o: MedicineOrderRow) => STATUS_PILL[o.status];
  protected readonly smallLine = orderSmallLine;
  protected readonly tags = orderTags;
  protected readonly prn = prnLine;
  protected readonly changes = changedLines;
  protected readonly holdText = holdLine;
  protected readonly stoppedText = stoppedLine;
  /** SAH-76-5: what the nurses did with the order (supersedes the SAH-32 "Last dose" line). */
  protected readonly execution = (o: MedicineOrderRow) => executionLines(o.execution, o.lastDose, o.details.kind);
  protected readonly drugLabel = drugOptionLabel;
  protected readonly severity = severityWord;

  protected readonly sheet = signal<EmrSheet | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** The server refused the role (403 forbidden): the sheet says so and shows nothing else. */
  protected readonly hidden = signal(false);
  /** One request runs at a time (order or stop): every button is off meanwhile. */
  protected readonly busy = signal(false);
  /** A refusal of a Change, Hold, Resume, Stop or Reply that needs the sheet read again: shown until dismissed. */
  protected readonly actionNotice = signal<string | null>(null);
  /** The medicine chosen in the form: its units follow it. */
  protected readonly drugKey = signal('');
  /** The check the form shows now (hard stop or warnings), for the medicine and frequency it was run for. */
  protected readonly shown = signal<ShownCheck | null>(null);
  protected readonly errors = signal<OrderErrors>({});

  protected readonly form: OrderForm = newOrderForm();
  /** The units of the chosen medicine, labelled (first preselected). */
  protected readonly unitOptions = computed(() => {
    const s = this.sheet();
    const drug = s?.drugs.find((d) => d.key === this.drugKey());
    return (drug?.doseUnits ?? []).map((u) => ({
      key: u as string,
      label: s?.doseUnits.find((x) => x.key === u)?.label ?? u,
    }));
  });

  protected readonly title = computed(() => {
    const s = this.sheet();
    return s ? `EMR · ${s.patient.name}` : 'EMR';
  });
  protected readonly subtitle = computed(() => {
    const s = this.sheet();
    return s ? emrSubtitle(s) : '';
  });
  protected readonly buttonText = computed(() => orderButtonText(this.shown()));
  protected readonly checkText = CHECK_AND_ORDER;

  private key = newClientKey();
  /** The reason the key was last used with (empty before a reason is chosen). */
  private lastReason = '';

  constructor() {
    effect(() => {
      this.dialogRef.disableClose = this.busy();
    });
    // SAH-34 AC 38, 39: 403 outside-scope on the read, an order or a stop. The chart, the orders and the typed form
    // leave the screen and the sheet closes; the page says so ("Nothing was saved." after a save).
    this.scopeRefusals.watch(this.data.admissionId, this.destroyRef, () => this.leaveOutsideScope());
    this.load();
  }

  private leaveOutsideScope(): void {
    this.sheet.set(null);
    this.shown.set(null);
    this.errors.set({});
    this.form.reset();
    this.dialogRef.close();
  }

  private outsideScope(error: unknown, write: boolean): boolean {
    if (!isOutsideScope(error)) {
      return false;
    }
    this.scopeRefusals.report(this.data.admissionId, write);
    return true;
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.hidden.set(false);
    this.api
      .sheet(this.data.admissionId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (sheet) => {
          this.loading.set(false);
          this.sheet.set(sheet);
          this.resetSelects(sheet);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          if (this.outsideScope(error, false)) {
            return;
          }
          if (error instanceof ApiError && error.status === 403 && !this.isModuleOff(error)) {
            this.hidden.set(true);
            return;
          }
          this.loadError.set(problemMessage(error, EMR_LOAD_ERROR));
        },
      });
  }

  private isModuleOff(error: ApiError): boolean {
    return error.problem.type.endsWith('module-off');
  }

  /** The sheet again, after a refused Stop or order: the form and the notices stay as they are. */
  private reload(): void {
    this.api
      .sheet(this.data.admissionId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (sheet) => this.sheet.set(sheet),
        error: (error: unknown) => {
          this.outsideScope(error, false);
        },
      });
  }

  private resetSelects(sheet: EmrSheet): void {
    const draft = blankDraft(sheet);
    this.form.setValue(draft);
    this.drugKey.set(draft.drug);
    this.lastReason = '';
  }

  // ---- Order form ----

  /** The medicine changed: its default route and unit are taken, and the check on screen is about something else. */
  protected medicineChanged(): void {
    const drug = this.sheet()?.drugs.find((d) => d.key === this.form.controls.drug.value);
    this.drugKey.set(drug?.key ?? '');
    const defaults = drugDefaults(drug);
    if (defaults) {
      this.form.patchValue(defaults);
    }
    this.selectionChanged();
  }

  protected fieldEdited(field: keyof OrderErrors): void {
    this.dropError(field);
  }

  /** The medicine, How often or Priority changed: the check on screen is about something else now. */
  protected selectionChanged(): void {
    this.shown.set(null);
    this.errors.set({});
    this.form.controls.reason.setValue('');
    this.lastReason = '';
    this.key = newClientKey();
  }

  protected reasonChanged(): void {
    const next = this.form.controls.reason.value;
    if (reasonChangeNeedsNewKey(this.lastReason, next)) {
      this.key = newClientKey();
    }
    this.lastReason = next;
    this.dropError('overrideReasonKey');
  }

  /** Clear: the notice and the reason go; no request. */
  protected clear(): void {
    if (this.busy()) {
      return;
    }
    this.shown.set(null);
    this.errors.set({});
    this.form.controls.reason.setValue('');
    this.reasonChanged();
  }

  private dropError(field: keyof OrderErrors): void {
    if (this.errors()[field]) {
      const rest = { ...this.errors() };
      delete rest[field];
      this.errors.set(rest);
    }
  }

  protected submit(): void {
    const sheet = this.sheet();
    if (this.busy() || !sheet || !sheet.canOrder) {
      return;
    }
    const draft = this.form.getRawValue();
    const errors: OrderErrors = {};
    if (!sheet.drugs.some((d) => d.key === draft.drug)) {
      errors.drugKey = DRUG_MISSING;
    }
    Object.assign(
      errors,
      validateDraft(draft, this.unitOptions().map((u) => u.key), sheet.routes),
    );
    this.errors.set(errors);
    const first = FOCUS_ORDER.find((f) => errors[f.field]);
    if (first) {
      this.focus(first.id);
      return;
    }
    const shown = this.shown();
    const body: MedicineOrderInput = toOrderInput(
      draft,
      this.key,
      draft.reason !== '' && shown
        ? { overrideReasonKey: draft.reason, checkToken: shown.check.checkToken }
        : null,
    );
    this.busy.set(true);
    this.api
      .order(sheet.admissionId, body)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (answer) => {
          this.busy.set(false);
          this.answered(answer, body);
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.orderFailed(error);
        },
      });
  }

  /** Switch on `outcome`: the status code (200 or 201) is not what decides. */
  private answered(answer: MedicineOrderAnswer, sent: MedicineOrderInput): void {
    this.sheet.set(answer.sheet);
    switch (answer.outcome) {
      case 'PLACED': {
        const name =
          answer.order?.drugName ??
          answer.sheet.drugs.find((d) => d.key === sent.drugKey)?.name ??
          'Medicine';
        this.toast(orderedToast(name, answer.order?.details.pharmacistChecked ?? null));
        this.shown.set(null);
        this.errors.set({});
        this.resetSelects(answer.sheet);
        this.key = newClientKey();
        return;
      }
      case 'BLOCKED':
        this.shown.set({
          drugKey: sent.drugKey,
          frequencyKey: sent.frequencyKey ?? '',
          outcome: 'BLOCKED',
          check: answer.check,
        });
        this.form.controls.reason.setValue('');
        this.lastReason = '';
        return;
      case 'NEEDS_REASON': {
        const before = this.shown();
        const sameAsShown =
          before !== null &&
          before.outcome === 'NEEDS_REASON' &&
          before.drugKey === sent.drugKey &&
          before.frequencyKey === (sent.frequencyKey ?? '');
        this.shown.set({
          drugKey: sent.drugKey,
          frequencyKey: sent.frequencyKey ?? '',
          outcome: 'NEEDS_REASON',
          check: answer.check,
        });
        if (sent.overrideReasonKey) {
          // The warnings are not the ones the reason was chosen for: choose it again. Nothing is
          // stored under the key by this answer, so the key stays.
          this.form.controls.reason.setValue('');
          this.lastReason = '';
          this.focusLater('emr-reason');
        } else if (sameAsShown) {
          this.toast(CHOOSE_REASON_TOAST);
        }
        return;
      }
    }
  }

  private orderFailed(error: unknown): void {
    if (this.outsideScope(error, true)) {
      return;
    }
    if (!(error instanceof ApiError)) {
      this.toast(ORDER_ERROR);
      return;
    }
    if (error.status === 400) {
      const { shown, other, newKey } = orderFieldErrors(error.fieldErrors);
      this.errors.set(shown);
      if (newKey) {
        this.key = newClientKey();
      }
      const first = FOCUS_ORDER.find((f) => shown[f.field]);
      if (first) {
        this.focus(first.id);
      }
      if (other !== null || Object.keys(shown).length === 0) {
        this.toast(other ?? problemMessage(error, ORDER_ERROR));
      }
      return;
    }
    if (error.status === 409) {
      if (error.problem.type.endsWith('client-key-reused')) {
        this.key = newClientKey();
        this.lastReason = this.form.controls.reason.value;
      } else {
        // not-admitted or ipd-readonly: the form must go, so read the sheet again.
        this.reload();
      }
    }
    this.toast(problemMessage(error, ORDER_ERROR));
  }

  // ---- Change, Hold, Resume, Stop, Reply (SAH-76-1, 76-2) ----

  protected openChange(order: MedicineOrderRow): void {
    const sheet = this.sheet();
    if (this.busy() || !sheet || !order.canChange) {
      return;
    }
    this.dialog
      .open<ChangeSheetComponent, ChangeSheetData, OrderActionResult>(ChangeSheetComponent, {
        panelClass: 'sheet-panel',
        width: '640px',
        maxWidth: '100vw',
        ariaLabelledBy: 'ord-change-title',
        data: { admissionId: sheet.admissionId, order, sheet },
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => this.actionDone(result));
  }

  protected openHold(order: MedicineOrderRow): void {
    if (this.busy() || !order.canHold) {
      return;
    }
    this.dialog
      .open<HoldSheetComponent, HoldSheetData, OrderActionResult>(HoldSheetComponent, {
        panelClass: 'sheet-panel',
        width: '520px',
        maxWidth: '100vw',
        ariaLabelledBy: 'ord-hold-title',
        data: { admissionId: this.data.admissionId, order },
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => this.actionDone(result));
  }

  protected openStop(order: MedicineOrderRow): void {
    if (this.busy() || !order.canStop) {
      return;
    }
    this.dialog
      .open<StopSheetComponent, StopSheetData, OrderActionResult>(StopSheetComponent, {
        panelClass: 'sheet-panel',
        width: '520px',
        maxWidth: '100vw',
        ariaLabelledBy: 'ord-stop-title',
        data: { admissionId: this.data.admissionId, order },
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => this.actionDone(result));
  }

  protected resume(order: MedicineOrderRow): void {
    if (this.busy() || !order.canResume) {
      return;
    }
    this.busy.set(true);
    this.actionNotice.set(null);
    this.api
      .resume(order.id, { clientKey: newClientKey(), orderVersion: order.orderVersion })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (sheet) => {
          this.busy.set(false);
          this.actionDone({ kind: 'done', sheet, toast: resumedToast(order.drugName) });
        },
        error: (error: unknown) => {
          this.busy.set(false);
          if (this.outsideScope(error, true)) {
            return;
          }
          this.actionDone({ kind: 'conflict', message: problemMessage(error, ACTION_ERROR) });
        },
      });
  }

  /** The reply box answered: the sheet now, or a refusal that needs a reload. */
  protected replied(result: OrderActionResult): void {
    this.actionDone(result);
  }

  private actionDone(result: OrderActionResult | undefined): void {
    if (!result) {
      return;
    }
    if (result.kind === 'done') {
      this.actionNotice.set(null);
      this.sheet.set(result.sheet);
      this.toast(result.toast);
    } else {
      this.actionNotice.set(result.message);
      this.reload();
    }
    this.focusLater(MEDS_HEADING_ID);
  }

  protected dismissNotice(): void {
    this.actionNotice.set(null);
  }

  // ---- Helpers ----

  protected close(): void {
    if (!this.busy()) {
      this.dialogRef.close();
    }
  }

  private toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 6000 });
  }

  /** For a control that is drawn only after the next change detection. */
  private focusLater(id: string): void {
    setTimeout(() => this.focus(id), 0);
  }

  private focus(id: string): void {
    this.host.nativeElement.querySelector<HTMLElement>(`#${id}`)?.focus();
  }
}
