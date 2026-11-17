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
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { map } from 'rxjs';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { changeLineText } from '../orders/order.model';
import { EmrApi } from './emr.api';
import { EmrSheet, MedicineOrderRow, OrderActionResult, OrderErrors, changedToast } from './emr.model';
import { classifyFailure } from './order-action.logic';
import { OrderFieldsComponent } from './order-fields.component';
import {
  NOTHING_CHANGED,
  OrderDraft,
  REASON_CHANGE_MISSING,
  REASON_MAX,
  draftOf,
  newOrderForm,
  previewLines,
  reasonProblem,
  toChangeInput,
  validateDraft,
} from './order-form.logic';

export interface ChangeSheetData {
  admissionId: string;
  order: MedicineOrderRow;
  /** The sheet the doctor is on: the lists of routes, frequencies and priorities. */
  sheet: EmrSheet;
}

const CHANGE_ERROR = 'We could not change that order. Try again.';

/** Control ids the first error moves the focus to, in screen order. */
const FOCUS_ORDER: { field: keyof OrderErrors; id: string }[] = [
  { field: 'frequencyKey', id: 'chg-freq' },
  { field: 'doseAmount', id: 'chg-dose' },
  { field: 'doseUnit', id: 'chg-unit' },
  { field: 'routeKey', id: 'chg-route' },
  { field: 'durationDays', id: 'chg-duration' },
  { field: 'indication', id: 'chg-indication' },
  { field: 'prnMinIntervalMinutes', id: 'chg-interval' },
  { field: 'prnMaxPer24h', id: 'chg-max' },
  { field: 'reason', id: 'chg-reason' },
];

/**
 * Change sheet (SAH-76-1): the form of the current version (dose, route, how often, duration, indication,
 * priority; not the medicine), the "Changed: <old> -> <new>" lines for what was edited (NEW), and the required
 * "Reason for the change" (4 to 200 characters, NEW). A change that repeats the current values is refused here
 * and by the server ("Nothing was changed."). The server writes version n+1; if the order moved on (409
 * `order-changed`, `order-state`), the sheet closes and the EMR sheet reads again.
 */
@Component({
  selector: 'app-change-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, IconComponent, OrderFieldsComponent],
  styles: `
    .lines {
      display: flex;
      flex-direction: column;
      gap: 2px;
      font-size: 0.84rem;
    }
    .sheet-f button,
    .notice button {
      min-height: 44px;
    }
  `,
  template: `
    <form class="sheet" (submit)="$event.preventDefault(); submit()" novalidate data-testid="change-sheet">
      <div class="sheet-h">
        <div>
          <h2 id="ord-change-title">Change · {{ order.drugName }}</h2>
          <p class="muted">Current version v{{ order.orderVersion }}. The medicine cannot change: order a different one instead.</p>
        </div>
        <button mat-icon-button type="button" aria-label="Close" [disabled]="running()" (click)="cancel()">
          <app-icon name="x" [size]="18" />
        </button>
      </div>
      <div class="sheet-b">
        <app-order-fields
          prefix="chg"
          [form]="form"
          [errors]="errors()"
          [frequencies]="data.sheet.frequencies"
          [units]="units"
          [routes]="data.sheet.routes"
          [priorities]="data.sheet.priorities"
          [statWindowMinutes]="data.sheet.statWindowMinutes"
          [showStart]="false"
          (selection)="errors.set({})"
          (edit)="dropError($event)"
        />

        @if (lines().length > 0) {
          <div class="lines" data-testid="change-lines" aria-live="polite">
            @for (l of lines(); track l.field) {
              <span>{{ lineText(l) }}</span>
            }
          </div>
        }

        <div class="field">
          <label for="chg-reason">Reason for the change</label>
          <textarea
            id="chg-reason"
            class="input"
            rows="3"
            [maxlength]="max"
            [formControl]="reason"
            (input)="dropError('reason')"
            [attr.aria-invalid]="errors().reason ? 'true' : null"
            [attr.aria-describedby]="errors().reason ? 'chg-reason-err' : null"
          ></textarea>
          @if (errors().reason; as message) {
            <span class="err-text" role="alert" id="chg-reason-err">{{ message }}</span>
          }
        </div>

        @if (failure(); as message) {
          <div class="notice err" role="alert" data-testid="change-failure">
            <app-icon name="alert" [size]="16" />
            <span>{{ message }}</span>
          </div>
        }
      </div>
      <div class="sheet-f">
        <button mat-stroked-button type="button" data-act="chg-cancel" [disabled]="running()" (click)="cancel()">
          Cancel
        </button>
        <button mat-flat-button type="submit" data-act="chg-ok" [disabled]="running()" [attr.aria-busy]="running()">
          Save change
        </button>
      </div>
    </form>
  `,
})
export class ChangeSheetComponent {
  private readonly api = inject(EmrApi);
  private readonly dialogRef = inject<MatDialogRef<ChangeSheetComponent, OrderActionResult>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly data = inject<ChangeSheetData>(MAT_DIALOG_DATA);
  private readonly scopeRefusals = inject(ScopeRefusals);

  protected readonly order = this.data.order;
  protected readonly max = REASON_MAX;
  protected readonly lineText = changeLineText;
  protected readonly form = newOrderForm();
  protected readonly reason = new FormControl('', { nonNullable: true });
  protected readonly running = signal(false);
  protected readonly errors = signal<OrderErrors>({});
  protected readonly failure = signal<string | null>(null);
  /** The units of this medicine, labelled. */
  protected readonly units = (() => {
    const drug = this.data.sheet.drugs.find((d) => d.key === this.order.drugKey);
    return (drug?.doseUnits ?? []).map((u) => ({
      key: u as string,
      label: this.data.sheet.doseUnits.find((x) => x.key === u)?.label ?? u,
    }));
  })();

  private readonly edits = toSignal(this.form.valueChanges.pipe(map(() => this.form.getRawValue())), {
    initialValue: this.form.getRawValue(),
  });
  protected readonly lines = computed(() => {
    return previewLines(this.edits() as OrderDraft, this.order.details, {
      routes: this.data.sheet.routes,
      frequencies: this.data.sheet.frequencies,
      priorities: this.data.sheet.priorities,
    });
  });

  private key = newClientKey();

  constructor() {
    this.form.setValue(
      draftOf(this.order.details, this.data.sheet.drugs.find((d) => d.key === this.order.drugKey)),
    );
    effect(() => {
      this.dialogRef.disableClose = this.running();
    });
  }

  protected dropError(field: keyof OrderErrors): void {
    if (this.errors()[field]) {
      const rest = { ...this.errors() };
      delete rest[field];
      this.errors.set(rest);
    }
  }

  protected submit(): void {
    if (this.running()) {
      return;
    }
    const draft = this.form.getRawValue();
    const errors: OrderErrors = validateDraft(
      draft,
      this.units.map((u) => u.key),
      this.data.sheet.routes,
    );
    const bad = reasonProblem(this.reason.value, REASON_CHANGE_MISSING);
    if (bad) {
      errors.reason = bad;
    }
    this.errors.set(errors);
    this.failure.set(null);
    const first = FOCUS_ORDER.find((f) => errors[f.field]);
    if (first) {
      this.host.nativeElement.querySelector<HTMLElement>(`#${first.id}`)?.focus();
      return;
    }
    if (this.lines().length === 0) {
      this.failure.set(NOTHING_CHANGED);
      return;
    }
    this.running.set(true);
    this.api
      .change(this.order.id, toChangeInput(draft, this.key, this.order.orderVersion, this.reason.value, false))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (sheet) => {
          this.running.set(false);
          this.dialogRef.close({ kind: 'done', sheet, toast: changedToast(this.order.drugName) });
        },
        error: (error: unknown) => {
          this.running.set(false);
          this.failed(error);
        },
      });
  }

  private failed(error: unknown): void {
    const f = classifyFailure(error, CHANGE_ERROR);
    switch (f.kind) {
      case 'scope':
        this.scopeRefusals.report(this.data.admissionId, true);
        this.dialogRef.close();
        return;
      case 'conflict':
        this.dialogRef.close(f.result);
        return;
      case 'fields':
        this.errors.set(f.errors);
        this.failure.set(f.other);
        if (f.newKey) {
          this.key = newClientKey();
        }
        return;
      case 'stay':
        this.failure.set(f.message);
        if (f.newKey) {
          this.key = newClientKey();
        }
    }
  }

  protected cancel(): void {
    if (!this.running()) {
      this.dialogRef.close();
    }
  }
}
