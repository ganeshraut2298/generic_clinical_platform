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
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
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
  FREQUENCY_MISSING,
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
  STOP_CONFIRM,
  STOP_ERROR,
  ShownCheck,
  WARN_HEAD,
  drugOptionLabel,
  emrSubtitle,
  orderButtonText,
  orderFieldErrors,
  orderSmallLine,
  orderedToast,
  reasonChangeNeedsNewKey,
  severityWord,
  stoppedToast,
} from './emr.model';
import { IdentityBarComponent } from './identity-bar.component';
import { lastDoseLine } from '../mar/mar.model';

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
  imports: [ReactiveFormsModule, MatButtonModule, IconComponent, IdentityBarComponent],
  styleUrls: ['../ipd-bill.scss', './emr-sheet.component.scss'],
  templateUrl: './emr-sheet.component.html',
})
export class EmrSheetComponent {
  private readonly api = inject(EmrApi);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialogRef = inject<MatDialogRef<EmrSheetComponent>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly data = inject<EmrSheetData>(MAT_DIALOG_DATA);

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
  protected readonly stopConfirm = STOP_CONFIRM;
  protected readonly hiddenText = EMR_HIDDEN;
  protected readonly pill = (o: MedicineOrderRow) => STATUS_PILL[o.status];
  protected readonly smallLine = orderSmallLine;
  /** SAH-32 (OQ-32-4): the "Last dose ..." line under an order's small line, or null when no dose was recorded. */
  protected readonly lastDoseOf = (o: MedicineOrderRow, asOf: string) => lastDoseLine(o.lastDose, asOf);
  protected readonly drugLabel = drugOptionLabel;
  protected readonly severity = severityWord;

  protected readonly sheet = signal<EmrSheet | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** The server refused the role (403 forbidden): the sheet says so and shows nothing else. */
  protected readonly hidden = signal(false);
  /** One request runs at a time (order or stop): every button is off meanwhile. */
  protected readonly busy = signal(false);
  protected readonly stopConfirmId = signal<string | null>(null);
  /** The check the form shows now (hard stop or warnings), for the medicine and frequency it was run for. */
  protected readonly shown = signal<ShownCheck | null>(null);
  protected readonly errors = signal<OrderErrors>({});

  protected readonly form = new FormGroup({
    drug: new FormControl('', { nonNullable: true }),
    freq: new FormControl('', { nonNullable: true }),
    reason: new FormControl('', { nonNullable: true }),
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
    this.load();
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
      .subscribe({ next: (sheet) => this.sheet.set(sheet), error: () => undefined });
  }

  private resetSelects(sheet: EmrSheet): void {
    this.form.setValue({
      drug: sheet.drugs[0]?.key ?? '',
      freq: sheet.frequencies[0]?.key ?? '',
      reason: '',
    });
    this.lastReason = '';
  }

  // ---- Order form ----

  /** The medicine or the frequency changed: the check on screen is about something else now. */
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
    const { drug, freq, reason } = this.form.getRawValue();
    const errors: OrderErrors = {};
    if (!sheet.drugs.some((d) => d.key === drug)) {
      errors.drugKey = DRUG_MISSING;
    }
    if (!sheet.frequencies.some((f) => f.key === freq)) {
      errors.frequencyKey = FREQUENCY_MISSING;
    }
    this.errors.set(errors);
    if (errors.drugKey) {
      this.focus('emr-drug');
      return;
    }
    if (errors.frequencyKey) {
      this.focus('emr-freq');
      return;
    }
    const shown = this.shown();
    const body: MedicineOrderInput = {
      clientKey: this.key,
      drugKey: drug,
      frequencyKey: freq,
      ...(reason !== '' && shown
        ? { overrideReasonKey: reason, checkToken: shown.check.checkToken }
        : {}),
    };
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
        this.toast(orderedToast(name));
        this.shown.set(null);
        this.errors.set({});
        this.resetSelects(answer.sheet);
        this.key = newClientKey();
        return;
      }
      case 'BLOCKED':
        this.shown.set({
          drugKey: sent.drugKey,
          frequencyKey: sent.frequencyKey,
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
          before.frequencyKey === sent.frequencyKey;
        this.shown.set({
          drugKey: sent.drugKey,
          frequencyKey: sent.frequencyKey,
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
      if (shown.drugKey) {
        this.focus('emr-drug');
      } else if (shown.frequencyKey) {
        this.focus('emr-freq');
      } else if (shown.overrideReasonKey) {
        this.focus('emr-reason');
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

  // ---- Stop ----

  protected askStop(order: MedicineOrderRow): void {
    if (this.busy() || !order.canStop) {
      return;
    }
    this.stopConfirmId.set(order.id);
    this.focusLater(`emr-keep-${order.id}`);
  }

  protected keepOrder(order: MedicineOrderRow): void {
    if (this.busy()) {
      return;
    }
    this.stopConfirmId.set(null);
    this.focusLater(`emr-stop-${order.id}`);
  }

  protected confirmStop(order: MedicineOrderRow): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.api
      .stop(order.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (sheet) => {
          this.busy.set(false);
          this.stopConfirmId.set(null);
          this.sheet.set(sheet);
          this.toast(stoppedToast(order.drugName));
          this.focusLater(MEDS_HEADING_ID);
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.stopConfirmId.set(null);
          if (error instanceof ApiError && (error.status === 409 || error.status === 404)) {
            this.reload();
          }
          this.toast(problemMessage(error, STOP_ERROR));
          this.focusLater(MEDS_HEADING_ID);
        },
      });
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
