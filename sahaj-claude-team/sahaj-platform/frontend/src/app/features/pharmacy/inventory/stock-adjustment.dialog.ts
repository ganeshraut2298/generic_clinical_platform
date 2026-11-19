import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiError } from '../../../core/http/problem-detail';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { rateText, rupees } from '../opening-stock/opening-stock.util';
import { applyFieldErrors, loadErrorMessage, problemSlug, saveErrorMessage } from '../pharmacy-display.util';
import {
  ActiveReason,
  ProductStock,
  SecondSignerCandidate,
  StockAdjustment,
  StockBatchRow,
} from '../pharmacy-inventory.model';
import { PharmacyApi } from '../pharmacy.api';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import { differenceText, expiryMonthYear } from './inventory.util';
import { PickedProduct, ProductPickerComponent } from './product-picker.component';
import { adjustmentValuePaise, validatePhysical } from './stock-adjustment.util';

export interface StockAdjustmentData {
  storeId: string;
  storeName: string;
  /** The batch the person opened the form from; without it the form asks for a product and a batch. */
  preset: StockBatchRow | null;
  /** The signed-in member, so that a Schedule X second signature never offers the caller. */
  callerMembershipId: string;
  callerRole: string;
}

export const STOCK_ADJUSTMENT_CONFIG = {
  panelClass: 'sheet-panel',
  width: '560px',
  maxWidth: '100vw',
  ariaLabelledBy: 'adjust-title',
} as const;

/**
 * "Stock adjustment" (story 79-5, mockup 06 card "Stock adjustment with audit trail"): Batch, Book to physical,
 * Reason chips from the Stock adjustment list, an optional note and "Submit adjustment". There is no photo field
 * (OQ-79-25). The difference is shown as "+n" or "−n" with its value at the batch purchase rate, without GST.
 * Whether it posts at once or waits for approval is the server's answer (the unit's rule). A Schedule X batch needs
 * a pharmacist and a second signature; an owner cannot raise it (the server answers 403 schedule-x-pharmacist).
 */
@Component({
  selector: 'app-stock-adjustment',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    IconComponent,
    ProductPickerComponent,
  ],
  styles: [
    `
      .pickrow {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .bookline {
        display: flex;
        flex-wrap: wrap;
        align-items: baseline;
        gap: 10px;
        font-weight: 600;
      }
      .diff {
        font-weight: 700;
      }
      .reasons {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
      }
      .reasons button {
        min-height: 44px;
        padding: 8px 12px;
        border: 1px solid var(--line);
        border-radius: var(--r-sm);
        background: var(--glass-hi);
        font-weight: 600;
        font-size: 0.86rem;
      }
      .reasons button[aria-pressed='true'] {
        border: 2px solid var(--pri);
        padding: 7px 11px;
        background: var(--pri-soft);
        color: var(--pri-text);
      }
      .err-text {
        color: var(--crit);
        font-size: 0.8rem;
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="adjust-title">Stock adjustment</h2>
        <p>{{ data.storeName }}</p>
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>

    <form class="sheet-b" [formGroup]="form" (ngSubmit)="submit()" novalidate aria-labelledby="adjust-title">
      @if (!data.preset && !batch()) {
        <div class="pickrow">
          <app-product-picker label="Product" (picked)="chooseProduct($event)" />
        </div>
      }
      @if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="adjust-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
      @if (product(); as p) {
        @if (!data.preset) {
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Batch</mat-label>
            <mat-select
              placeholder="Choose a batch"
              [value]="batch()?.batch?.batchId ?? null"
              (selectionChange)="chooseBatch($event.value)"
              data-testid="f-batch"
            >
              @for (row of availableBatches(); track row.batch.batchId) {
                <mat-option [value]="row.batch.batchId">
                  {{ p.product.name }} · {{ row.batch.batchNo }} · {{ month(row.batch.expiryDate) }}
                </mat-option>
              } @empty {
                <mat-option disabled>No Available batch to adjust</mat-option>
              }
            </mat-select>
          </mat-form-field>
        }
      }

      @if (batch(); as b) {
        <p data-testid="adjust-batch-line">
          <b>{{ b.product.name }} · {{ b.batch.batchNo }}</b>
          · expires {{ month(b.batch.expiryDate) }}
        </p>
        <div class="bookline" data-testid="book-line">
          <span>Book → physical</span>
          <span data-testid="book-qty">{{ b.qty === null ? '—' : book() + ' ' + b.product.issueUnit }}</span>
          <span aria-hidden="true">→</span>
        </div>
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Physical quantity</mat-label>
          <input matInput type="number" inputmode="numeric" min="0" formControlName="physical" data-testid="f-physical" />
          @if (form.controls.physical.touched && form.controls.physical.errors; as errors) {
            <mat-error>{{ errors['server'] ?? errors['message'] }}</mat-error>
          }
        </mat-form-field>
        @if (difference() !== null) {
          <p class="diff" data-testid="difference">
            {{ diffText(difference()!) }} {{ b.product.issueUnit }}
            @if (valuePaise() !== null) {
              · {{ money(valuePaise()!) }}
            }
          </p>
        }
        <p class="note">Value is the difference times the purchase rate of {{ rate(b.purchaseRate) }}, without GST.</p>

        <div role="group" aria-labelledby="reason-label">
          <span class="field-label" id="reason-label">Reason</span>
          @if (reasonsLoading()) {
            <p class="loading" role="status">Loading reasons</p>
          } @else {
            <div class="reasons" data-testid="reason-chips">
              @for (reason of reasons(); track reason.reasonKey) {
                <button
                  type="button"
                  [attr.aria-pressed]="form.controls.reasonKey.value === reason.reasonKey"
                  [attr.data-testid]="'reason-' + reason.reasonKey"
                  (click)="chooseReason(reason)"
                >
                  {{ reason.label }}
                </button>
              }
            </div>
          }
          @if (form.controls.reasonKey.touched && form.controls.reasonKey.errors; as errors) {
            <p class="err-text" role="alert" data-testid="reason-error">{{ errors['server'] ?? 'Choose a reason' }}</p>
          }
        </div>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Note (optional)</mat-label>
          <textarea matInput rows="2" maxlength="300" formControlName="note" data-testid="f-note"></textarea>
          <mat-hint>Do not add patient details.</mat-hint>
          @if (form.controls.note.touched && form.controls.note.errors; as errors) {
            <mat-error>{{ errors['server'] ?? 'Write at least 4 characters' }}</mat-error>
          }
        </mat-form-field>

        @if (scheduleX()) {
          @if (ownerBlocked()) {
            <div class="notice warn" data-testid="schedule-x-owner">
              <app-icon name="lock" [size]="16" />
              <span>Schedule X stock is adjusted by a pharmacist, with a second signature. An owner cannot raise it.</span>
            </div>
          } @else {
            <div class="notice teal" data-testid="schedule-x-note">
              <app-icon name="lock" [size]="16" />
              <span>Schedule X needs a pharmacist and a second signature by a different person.</span>
            </div>
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Second signer</mat-label>
              <mat-select formControlName="signerId" placeholder="Choose a person" data-testid="f-signer">
                @for (person of signers(); track person.membershipId) {
                  <mat-option [value]="person.membershipId">{{ person.name }} · {{ person.roleLabel }}</mat-option>
                }
              </mat-select>
              @if (form.controls.signerId.touched && form.controls.signerId.errors; as errors) {
                <mat-error>{{ errors['server'] ?? 'Choose the second signer' }}</mat-error>
              }
            </mat-form-field>
            @if (signersLoaded() && signers().length === 0) {
              <p class="notice" role="status" data-testid="no-second-signer">
                No other pharmacist or owner is available to sign.
              </p>
            }
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Their PIN</mat-label>
              <input
                matInput
                type="password"
                inputmode="numeric"
                autocomplete="off"
                maxlength="12"
                formControlName="pin"
                data-testid="f-pin"
              />
              @if (form.controls.pin.touched && form.controls.pin.errors; as errors) {
                <mat-error>{{ errors['server'] ?? 'Enter the PIN' }}</mat-error>
              }
            </mat-form-field>
          }
        }
      }

      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="adjust-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </form>

    <div class="sheet-f">
      <button mat-stroked-button type="button" (click)="close()">Cancel</button>
      @if (batch()) {
        <button
          mat-flat-button
          type="button"
          data-testid="submit-adjustment"
          [disabled]="saving() || ownerBlocked()"
          (click)="submit()"
        >
          Submit adjustment
        </button>
      }
    </div>
  `,
})
export class StockAdjustmentDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<StockAdjustmentDialog, StockAdjustment | null>>(MatDialogRef);
  protected readonly data = inject<StockAdjustmentData>(MAT_DIALOG_DATA);

  protected readonly product = signal<ProductStock | null>(null);
  protected readonly batch = signal<StockBatchRow | null>(null);
  protected readonly reasons = signal<ActiveReason[]>([]);
  protected readonly reasonsLoading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly signers = signal<SecondSignerCandidate[]>([]);
  protected readonly signersLoaded = signal(false);

  protected readonly form = new FormGroup({
    physical: new FormControl<number | null>(null),
    reasonKey: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
    signerId: new FormControl('', { nonNullable: true }),
    pin: new FormControl('', { nonNullable: true }),
  });

  /** One key per attempt: a double tap or a retry after a lost connection never adds a second adjustment. */
  private attemptKey = newClientKey();

  private readonly physicalValue = toSignal(this.form.controls.physical.valueChanges, { initialValue: null });

  protected readonly money = rupees;
  protected readonly rate = rateText;
  protected readonly month = expiryMonthYear;
  protected readonly diffText = differenceText;

  protected readonly book = computed(() => this.batch()?.qty ?? 0);
  protected readonly scheduleX = computed(() => this.batch()?.product.schedule === 'X');
  protected readonly ownerBlocked = computed(() => this.scheduleX() && this.data.callerRole === 'OWNER');
  protected readonly availableBatches = computed(() =>
    (this.product()?.batches ?? []).filter((row) => row.state === 'AVAILABLE' && row.daysToExpiry >= 0),
  );
  /** The change in whole issue units, or null until a valid physical quantity is typed. */
  protected readonly difference = computed<number | null>(() => {
    const value = this.physicalValue();
    const b = this.batch();
    if (b === null || b.qty === null || value === null || validatePhysical(value, this.book()) !== null) {
      return null;
    }
    return value - this.book();
  });
  protected readonly valuePaise = computed<number | null>(() => {
    const diff = this.difference();
    const b = this.batch();
    return diff === null || b === null ? null : adjustmentValuePaise(diff, b.purchaseRate);
  });

  ngOnInit(): void {
    this.api.activeReasons(['stock-adjustment']).subscribe({
      next: (answer) => {
        this.reasons.set(answer.lists.find((l) => l.listKey === 'stock-adjustment')?.reasons ?? []);
        this.reasonsLoading.set(false);
      },
      error: (error: unknown) => {
        this.reasonsLoading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
    if (this.data.preset) {
      this.batch.set(this.data.preset);
      this.loadSigners();
    }
  }

  protected close(): void {
    this.ref.close(null);
  }

  protected chooseProduct(picked: PickedProduct): void {
    this.loadError.set(null);
    this.batch.set(null);
    this.api.productStock(this.data.storeId, picked.productId).subscribe({
      next: (stock) => {
        this.product.set(stock);
        this.loadSigners();
      },
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
  }

  protected chooseBatch(batchId: string): void {
    const row = this.availableBatches().find((r) => r.batch.batchId === batchId) ?? null;
    this.batch.set(row);
    this.form.controls.physical.setValue(null);
  }

  protected chooseReason(reason: ActiveReason): void {
    this.form.controls.reasonKey.setValue(reason.reasonKey);
    this.form.controls.reasonKey.setErrors(null);
  }

  /** The other pharmacists and owners who may sign as the second person (contract 0.3.2; never the caller). */
  private loadSigners(): void {
    this.signersLoaded.set(false);
    this.api.secondSigners(this.data.storeId).subscribe({
      next: (people) => {
        this.signers.set(people);
        this.signersLoaded.set(true);
      },
      error: () => {
        this.signers.set([]);
        this.signersLoaded.set(true);
      },
    });
  }

  protected submit(): void {
    const b = this.batch();
    if (this.saving() || b === null || b.qty === null || this.ownerBlocked()) {
      return;
    }
    this.formError.set(null);
    const controls = this.form.controls;
    const physical = controls.physical.value;
    const problem = validatePhysical(physical, this.book());
    controls.physical.setErrors(problem ? { message: problem } : null);
    const reason = this.reasons().find((r) => r.reasonKey === controls.reasonKey.value);
    controls.reasonKey.setErrors(reason ? null : { required: true });
    const note = controls.note.value.trim();
    controls.note.setErrors(reason?.needsNote && note.length < 4 ? { note: true } : null);
    if (this.scheduleX()) {
      controls.signerId.setErrors(controls.signerId.value ? null : { required: true });
      controls.pin.setErrors(controls.pin.value ? null : { required: true });
    }
    this.form.markAllAsTouched();
    if (this.form.invalid || physical === null || reason === undefined) {
      return;
    }
    this.saving.set(true);
    this.api
      .createStockAdjustment(this.data.storeId, {
        clientKey: this.attemptKey,
        batchId: b.batch.batchId,
        bookQtySeen: this.book(),
        physicalQty: physical,
        reasonKey: reason.reasonKey,
        note: note === '' ? null : note,
        secondSigner: this.scheduleX()
          ? { membershipId: controls.signerId.value, pin: controls.pin.value }
          : null,
      })
      .subscribe({
        next: (done) => {
          this.saving.set(false);
          controls.pin.setValue('');
          this.ref.close(done);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          controls.pin.setValue('');
          this.onError(error, b);
        },
      });
  }

  private onError(error: unknown, b: StockBatchRow): void {
    const controls = this.form.controls;
    if (error instanceof ApiError && error.fieldErrors.length > 0) {
      const rest = applyFieldErrors(
        {
          physicalQty: controls.physical,
          reasonKey: controls.reasonKey,
          note: controls.note,
          'secondSigner.membershipId': controls.signerId,
          'secondSigner.pin': controls.pin,
        },
        error.fieldErrors,
      );
      this.formError.set(rest.length > 0 ? rest.join(' ') : null);
      return;
    }
    this.formError.set(saveErrorMessage(error));
    // The book quantity moved under us: the screen reloads the batch so that the person sees the new figure.
    if (problemSlug(error) === 'changed') {
      this.attemptKey = newClientKey();
      this.api.productStock(this.data.storeId, b.product.productId).subscribe({
        next: (stock) => {
          this.product.set(stock);
          const fresh = stock.batches.find((r) => r.batch.batchId === b.batch.batchId && r.state === 'AVAILABLE');
          if (fresh) {
            this.batch.set(fresh);
          }
        },
        error: () => undefined,
      });
    }
  }
}
