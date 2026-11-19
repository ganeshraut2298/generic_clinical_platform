import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormArray, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiError } from '../../../core/http/problem-detail';
import { newClientKey } from '../../../shared/client-key';
import { provideIsoDateAdapter } from '../../../shared/format/iso-date-adapter';
import { IconComponent } from '../../../shared/icon/icon.component';
import { PickedProduct, ProductPickerComponent } from '../inventory/product-picker.component';
import { rupees } from '../opening-stock/opening-stock.util';
import { applyFieldErrors, controlMessage, loadErrorMessage, saveErrorMessage } from '../pharmacy-display.util';
import { PurchaseOrder, PurchaseOrderLineInput } from '../pharmacy-buying.model';
import { PharmacyApi } from '../pharmacy.api';
import { Vendor } from '../pharmacy.model';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import {
  PO_LINE_LIMIT,
  PO_MESSAGES,
  lineAmountPaise,
  notBeforeToday,
  positiveRate,
  trimRate,
  wholeQty,
} from './purchase.util';

export interface PoFormData {
  storeId: string;
  /** The draft being changed; absent for "+ Manual PO". */
  po?: PurchaseOrder;
}

export const PO_FORM_CONFIG = {
  panelClass: 'sheet-panel',
  width: '760px',
  maxWidth: '100vw',
  ariaLabelledBy: 'po-form-title',
} as const;

interface LineMeta {
  productId: string;
  name: string;
  detail: string;
  purchaseUnit: string;
}

function lineGroup(qty = '', rate = '') {
  return new FormGroup({
    qty: new FormControl(qty, { nonNullable: true, validators: [wholeQty(1, 999999)] }),
    rate: new FormControl(rate, { nonNullable: true, validators: [positiveRate] }),
  });
}

/**
 * "+ Manual PO" and "Edit draft" (story 79-6, mockup 07): Vendor (active, unblocked, supplies the Central Store),
 * Delivery expected (today or later; NEW, OQ-79-32), Credit days (default from the vendor; NEW) and the lines
 * (product, quantity in whole purchase units, rate per purchase unit). Amounts are shown as typed; the server works
 * the amounts and the total again. A failed save keeps what was typed; one key per opened sheet keeps a double tap
 * to one purchase order.
 */
@Component({
  selector: 'app-po-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDatepickerModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    IconComponent,
    ProductPickerComponent,
  ],
  providers: [provideIsoDateAdapter()],
  styles: [
    `
      .linegrid {
        display: grid;
        grid-template-columns: minmax(180px, 2fr) minmax(90px, 1fr) minmax(110px, 1fr) minmax(100px, 1fr) 48px;
        gap: 8px;
        align-items: start;
        min-width: 640px;
      }
      .linehead {
        font-size: 0.78rem;
        font-weight: 600;
        color: var(--ink-3);
      }
      .scroll {
        overflow-x: auto;
      }
      .sub {
        font-size: 0.78rem;
        color: var(--ink-3);
      }
      .num {
        text-align: right;
        white-space: nowrap;
      }
      .err-text {
        color: var(--crit);
        font-size: 0.8rem;
      }
      .total {
        font-weight: 700;
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="po-form-title">{{ editing ? 'Edit draft purchase order' : 'New purchase order' }}</h2>
        <p>Central Store</p>
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>

    <form class="sheet-b" [formGroup]="form" (ngSubmit)="save()" novalidate aria-labelledby="po-form-title">
      @if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="po-form-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }

      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>Vendor</mat-label>
        <mat-select formControlName="vendorId" data-testid="f-po-vendor" (selectionChange)="vendorChosen($event.value)">
          @for (vendor of vendors(); track vendor.id) {
            <mat-option [value]="vendor.id">{{ vendor.name }}</mat-option>
          } @empty {
            <mat-option disabled>No active vendor supplies the Central Store</mat-option>
          }
        </mat-select>
        @if (message(form.controls.vendorId, vendorMessages); as text) {
          <mat-error>{{ text }}</mat-error>
        }
      </mat-form-field>

      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>Delivery expected</mat-label>
        <input
          matInput
          [matDatepicker]="deliveryPicker"
          formControlName="deliveryExpected"
          placeholder="24 Oct 2026"
          autocomplete="off"
          data-testid="f-po-delivery"
        />
        <mat-datepicker-toggle matIconSuffix [for]="deliveryPicker" aria-label="Choose the delivery date" />
        <mat-datepicker #deliveryPicker />
        @if (message(form.controls.deliveryExpected, deliveryMessages); as text) {
          <mat-error>{{ text }}</mat-error>
        }
      </mat-form-field>

      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>Credit days</mat-label>
        <input
          matInput
          type="text"
          inputmode="numeric"
          formControlName="creditDays"
          autocomplete="off"
          data-testid="f-po-credit"
        />
        @if (form.controls.creditDays.value.trim() === '') {
          <mat-hint data-testid="po-credit-hint">From the vendor</mat-hint>
        }
        @if (message(form.controls.creditDays, creditMessages); as text) {
          <mat-error>{{ text }}</mat-error>
        }
      </mat-form-field>

      <h3>Items</h3>
      @if (lines.length < lineLimit) {
        <app-product-picker label="Add a product" (picked)="addProduct($event)" />
      }
      @if (duplicateNote(); as text) {
        <p class="err-text" role="alert" data-testid="po-duplicate">{{ text }}</p>
      }
      @if (lines.length === 0) {
        <p class="sub" data-testid="po-no-lines">
          @if (showLinesError()) {
            {{ messages.lines }}
          } @else {
            No items yet. Search for a product to add it.
          }
        </p>
      } @else {
        <div class="scroll">
          <div class="linegrid linehead" aria-hidden="true">
            <span>Item</span>
            <span>Qty (purchase units)</span>
            <span>Rate per purchase unit</span>
            <span class="num">Amount</span>
            <span></span>
          </div>
          <div formArrayName="lines">
            @for (group of lines.controls; track group; let i = $index) {
              <div class="linegrid" [formGroupName]="i" [attr.data-testid]="'po-line-' + i">
                <div>
                  <b>{{ meta()[i].name }}</b>
                  <div class="sub">{{ meta()[i].detail }}</div>
                </div>
                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                  <mat-label>Qty</mat-label>
                  <input
                    matInput
                    type="text"
                    inputmode="numeric"
                    formControlName="qty"
                    autocomplete="off"
                    [attr.data-testid]="'po-qty-' + i"
                  />
                  @if (message(group.controls.qty, qtyMessages); as text) {
                    <mat-error>{{ text }}</mat-error>
                  }
                </mat-form-field>
                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                  <mat-label>Rate</mat-label>
                  <input
                    matInput
                    type="text"
                    inputmode="decimal"
                    formControlName="rate"
                    autocomplete="off"
                    [attr.data-testid]="'po-rate-' + i"
                  />
                  @if (message(group.controls.rate, rateMessages); as text) {
                    <mat-error>{{ text }}</mat-error>
                  }
                </mat-form-field>
                <span class="num" [attr.data-testid]="'po-amount-' + i">{{ amountText(i) }}</span>
                <button
                  mat-icon-button
                  type="button"
                  [attr.aria-label]="'Remove ' + meta()[i].name"
                  [attr.data-testid]="'po-remove-' + i"
                  (click)="removeLine(i)"
                >
                  <app-icon name="x" />
                </button>
              </div>
            }
          </div>
        </div>
        <p class="total" data-testid="po-total">Subtotal {{ totalText() }}</p>
        <p class="sub">GST is worked on each line when you save; the purchase order then shows the total including GST.</p>
      }

      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="po-form-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </form>

    <div class="sheet-f">
      <button mat-stroked-button type="button" (click)="close()">Cancel</button>
      <button mat-flat-button type="button" data-testid="save-po" [disabled]="saving()" (click)="save()">
        Save draft
      </button>
    </div>
  `,
})
export class PoFormDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<PoFormDialog, PurchaseOrder | null>>(MatDialogRef);
  protected readonly data = inject<PoFormData>(MAT_DIALOG_DATA);
  protected readonly editing = this.data.po !== undefined;

  /** One key for this opened sheet, so that a double tap or a retry makes one purchase order (rule 10). */
  private readonly clientKey = newClientKey();

  protected readonly lineLimit = PO_LINE_LIMIT;
  protected readonly messages = PO_MESSAGES;
  protected readonly vendors = signal<Vendor[]>([]);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly duplicateNote = signal<string | null>(null);
  protected readonly showLinesError = signal(false);
  protected readonly meta = signal<LineMeta[]>([]);

  protected readonly form = new FormGroup({
    vendorId: new FormControl('', { nonNullable: true }),
    deliveryExpected: new FormControl<string | null>(null, { validators: [notBeforeToday()] }),
    creditDays: new FormControl('30', { nonNullable: true }),
    lines: new FormArray<ReturnType<typeof lineGroup>>([]),
  });

  protected get lines(): FormArray<ReturnType<typeof lineGroup>> {
    return this.form.controls.lines;
  }

  protected readonly message = controlMessage;
  protected readonly vendorMessages = { required: PO_MESSAGES.vendor };
  protected readonly deliveryMessages = { required: PO_MESSAGES.deliveryRequired, past: PO_MESSAGES.deliveryPast };
  protected readonly creditMessages = { credit: PO_MESSAGES.creditDays };
  protected readonly qtyMessages = { qty: PO_MESSAGES.qty };
  protected readonly rateMessages = { rate: PO_MESSAGES.rate };

  /** Bumped when a line changes, so that the computed totals read the form again. */
  private readonly tick = signal(0);

  protected readonly totalPaise = computed(() => {
    this.tick();
    let total = 0;
    for (let i = 0; i < this.lines.length; i++) {
      total += this.linePaise(i) ?? 0;
    }
    return total;
  });
  protected readonly totalText = computed(() => rupees(this.totalPaise()));

  ngOnInit(): void {
    this.api.vendors({ status: 'ACTIVE', suppliesStoreId: this.data.storeId, size: 100 }).subscribe({
      next: (page) => {
        this.vendors.set(
          page.items.filter((v) => !v.blocked),
        );
        this.fillFromPo();
      },
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
    this.form.valueChanges.subscribe(() => this.tick.update((n) => n + 1));
  }

  private fillFromPo(): void {
    const po = this.data.po;
    if (!po) {
      return;
    }
    this.form.patchValue({
      vendorId: po.vendor.vendorId,
      deliveryExpected: po.deliveryExpected,
      creditDays: String(po.creditDays),
    });
    this.meta.set([]);
    this.lines.clear();
    for (const line of po.lines) {
      this.meta.update((m) => [
        ...m,
        {
          productId: line.product.productId,
          name: line.product.name,
          detail: `${line.product.strength} · ${line.product.form} · per ${line.product.purchaseUnit}`,
          purchaseUnit: line.product.purchaseUnit,
        },
      ]);
      this.lines.push(lineGroup(String(line.qty), trimRate(line.rate)));
    }
  }

  protected vendorChosen(vendorId: string): void {
    const vendor = this.vendors().find((v) => v.id === vendorId);
    if (vendor && !this.editing) {
      this.form.controls.creditDays.setValue(vendor.creditDays === null ? '' : String(vendor.creditDays));
    }
  }

  protected addProduct(picked: PickedProduct): void {
    this.duplicateNote.set(null);
    if (this.meta().some((m) => m.productId === picked.productId)) {
      this.duplicateNote.set(PO_MESSAGES.duplicate);
      return;
    }
    if (this.lines.length >= PO_LINE_LIMIT) {
      this.duplicateNote.set(PO_MESSAGES.tooMany);
      return;
    }
    this.meta.update((m) => [
      ...m,
      {
        productId: picked.productId,
        name: picked.name,
        detail: `${picked.strength} · ${picked.form}`,
        purchaseUnit: '',
      },
    ]);
    this.lines.push(lineGroup('', ''));
    this.showLinesError.set(false);
  }

  protected removeLine(index: number): void {
    this.lines.removeAt(index);
    this.meta.update((m) => m.filter((_, i) => i !== index));
  }

  private linePaise(index: number): number | null {
    const group = this.lines.at(index);
    if (!group || group.controls.qty.invalid) {
      return null;
    }
    return lineAmountPaise(Number(group.controls.qty.value), group.controls.rate.value);
  }

  protected amountText(index: number): string {
    this.tick();
    const line = this.linePaise(index);
    return line === null ? '—' : rupees(line);
  }

  protected close(): void {
    this.ref.close(null);
  }

  protected save(): void {
    if (this.saving()) {
      return;
    }
    this.formError.set(null);
    const controls = this.form.controls;
    this.form.markAllAsTouched();
    controls.vendorId.setErrors(controls.vendorId.value ? null : { required: true });
    const credit = controls.creditDays.value.trim();
    // Empty is allowed: a person who cannot see the vendor's credit terms leaves it for the server to fill.
    controls.creditDays.setErrors(
      credit === '' || (/^\d+$/.test(credit) && Number(credit) <= 365) ? null : { credit: true },
    );
    this.showLinesError.set(this.lines.length === 0);
    if (this.form.invalid || this.lines.length === 0) {
      return;
    }

    const lines: PurchaseOrderLineInput[] = this.lines.controls.map((group, i) => ({
      productId: this.meta()[i].productId,
      qty: Number(group.controls.qty.value),
      rate: group.controls.rate.value.trim(),
    }));
    this.saving.set(true);
    const po = this.data.po;
    const request = po
      ? this.api.updatePurchaseOrder(this.data.storeId, po.id, {
          version: po.version,
          vendorId: controls.vendorId.value,
          deliveryExpected: controls.deliveryExpected.value,
          ...(credit === '' ? {} : { creditDays: Number(credit) }),
          lines,
        })
      : this.api.createPurchaseOrder(this.data.storeId, {
          clientKey: this.clientKey,
          vendorId: controls.vendorId.value,
          deliveryExpected: controls.deliveryExpected.value,
          ...(credit === '' ? {} : { creditDays: Number(credit) }),
          lines,
        });
    request.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.ref.close(saved);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.onError(error);
      },
    });
  }

  private onError(error: unknown): void {
    if (error instanceof ApiError && error.fieldErrors.length > 0) {
      const controls = this.form.controls;
      const rest = applyFieldErrors(
        { vendorId: controls.vendorId, deliveryExpected: controls.deliveryExpected, creditDays: controls.creditDays },
        error.fieldErrors,
      );
      this.formError.set(rest.length > 0 ? rest.join(' ') : null);
      return;
    }
    this.formError.set(saveErrorMessage(error));
  }
}
