import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { rateText, rupees } from '../opening-stock/opening-stock.util';
import { loadErrorMessage, saveErrorMessage } from '../pharmacy-display.util';
import { DebitNote, DebitNoteLineInput, EligibleReturnItem, VendorReturnEligible } from '../pharmacy-buying.model';
import { PharmacyApi } from '../pharmacy.api';
import { Vendor } from '../pharmacy.model';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import {
  CreditLevels,
  creditPaise,
  monthYearText,
  percentRange,
  policyLine,
  sourceChipText,
  startingCreditPercent,
  trimPercent,
  wholeQty,
} from './purchase.util';

export interface VendorReturnData {
  storeId: string;
  /** From a batch row's "Return" (story 79-3): the vendor and the batch are chosen when the sheet opens. */
  vendorId?: string | null;
  batchId?: string | null;
}

export const VENDOR_RETURN_CONFIG = {
  panelClass: 'sheet-panel',
  width: '1000px',
  maxWidth: '100vw',
  ariaLabelledBy: 'vr-title',
} as const;

interface PickRow {
  item: EligibleReturnItem;
  selected: boolean;
  qty: string;
  creditPercent: string;
  startPercent: string;
  error: string | null;
}

/**
 * "+ New return" (story 79-8, mockup 09): Vendor, the return policy line, the card "Items eligible to return to
 * <vendor>" (Select, Item · batch, Expiry, Source, Qty, Rate, Credit %, Credit) and the footer "Debit note · <n> lines
 * · ₹ <credit> + GST reversal" with "Create debit note & pick list". Credit % starts from the vendor's credit levels
 * (the lower of the damaged and expired levels when both apply) and a changed value is marked "Changed from policy".
 * Short supply of a posted receipt carries no stock movement. The server holds the units (now, or after approval).
 */
@Component({
  selector: 'app-vendor-return',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, MatFormFieldModule, MatSelectModule, IconComponent],
  styles: [
    `
      .scroll {
        overflow-x: auto;
      }
      table {
        min-width: 900px;
      }
      .num {
        text-align: right;
        white-space: nowrap;
      }
      .sub {
        font-size: 0.78rem;
        color: var(--ink-3);
      }
      input.cell {
        min-height: 44px;
        width: 100%;
        min-width: 70px;
        padding: 6px 8px;
        border: 1px solid var(--line);
        border-radius: var(--r-sm);
        background: var(--glass-hi);
        font: inherit;
      }
      .row-err {
        color: var(--crit);
        font-size: 0.78rem;
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="vr-title">Vendor return & debit note</h2>
        <p>Central Store</p>
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>

    <div class="sheet-b">
      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>Vendor</mat-label>
        <mat-select data-testid="f-vr-vendor" [value]="vendorId()" (selectionChange)="chooseVendor($event.value)">
          @for (vendor of vendors(); track vendor.id) {
            <mat-option [value]="vendor.id">{{ vendor.name }}</mat-option>
          } @empty {
            <mat-option disabled>No active vendor supplies the Central Store</mat-option>
          }
        </mat-select>
      </mat-form-field>

      @if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="vr-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }

      @if (eligible(); as e) {
        <p data-testid="vr-policy">{{ policy() }}</p>
        <h3>Items eligible to return to {{ e.vendor.name }}</h3>
        @if (rows().length === 0) {
          <p class="sub" data-testid="vr-empty">Nothing is eligible to return to this vendor.</p>
        } @else {
          <div class="scroll">
            <table data-testid="vr-table">
              <thead>
                <tr>
                  <th scope="col"><span class="sr-only">Select</span></th>
                  <th scope="col">Item · batch</th>
                  <th scope="col">Expiry</th>
                  <th scope="col">Source</th>
                  <th scope="col" class="num">Qty</th>
                  <th scope="col" class="num">Rate</th>
                  <th scope="col" class="num">Credit %</th>
                  <th scope="col" class="num">Credit</th>
                </tr>
              </thead>
              <tbody>
                @for (r of rows(); track $index; let i = $index) {
                  <tr [attr.data-testid]="'vr-row-' + i">
                    <td>
                      <input
                        type="checkbox"
                        [checked]="r.selected"
                        [attr.aria-label]="'Select ' + r.item.product.name"
                        [attr.data-testid]="'vr-select-' + i"
                        (change)="toggle(i)"
                      />
                    </td>
                    <td>
                      {{ r.item.product.name }}
                      <div class="sub">
                        {{ r.item.batch ? r.item.batch.batchNo : '—' }}
                        @if (r.item.source === 'GRN_SHORT') {
                          · short supply, no stock movement
                        }
                      </div>
                    </td>
                    <td>{{ r.item.batch ? month(r.item.batch.expiryDate) : '—' }}</td>
                    <td>
                      <span class="pill p-wait">{{ source(r.item) }}</span>
                    </td>
                    <td class="num">
                      <input
                        class="cell"
                        inputmode="numeric"
                        [value]="r.qty"
                        [attr.aria-label]="'Quantity of ' + r.item.product.name"
                        [attr.data-testid]="'vr-qty-' + i"
                        (input)="edit(i, 'qty', $any($event.target).value)"
                      />
                      <div class="sub">
                        @if (r.item.availableQty === null) {
                          <span title="Counting in progress">—</span>
                        } @else {
                          of {{ r.item.availableQty }}
                        }
                      </div>
                    </td>
                    <td class="num">{{ rate(r.item.rate) }}</td>
                    <td class="num">
                      <input
                        class="cell"
                        inputmode="decimal"
                        [value]="r.creditPercent"
                        [attr.aria-label]="'Credit percent of ' + r.item.product.name"
                        [attr.data-testid]="'vr-credit-percent-' + i"
                        (input)="edit(i, 'creditPercent', $any($event.target).value)"
                      />
                      @if (changed(r)) {
                        <div class="sub" [attr.data-testid]="'vr-changed-' + i">Changed from policy</div>
                      }
                      @if (r.error; as text) {
                        <div class="row-err" role="alert">{{ text }}</div>
                      }
                    </td>
                    <td class="num" [attr.data-testid]="'vr-credit-' + i">{{ creditText(r) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      }

      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="vr-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </div>

    <div class="sheet-f">
      <span data-testid="vr-footer">Debit note · {{ selected().length }} {{ selected().length === 1 ? 'line' : 'lines' }} · {{ money(creditTotal()) }} + GST reversal</span>
      <button mat-stroked-button type="button" (click)="close()">Cancel</button>
      <button
        mat-flat-button
        type="button"
        data-testid="vr-create"
        [disabled]="saving() || selected().length === 0"
        (click)="create()"
      >
        Create debit note & pick list
      </button>
    </div>
  `,
})
export class VendorReturnDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<VendorReturnDialog, DebitNote | null>>(MatDialogRef);
  protected readonly data = inject<VendorReturnData>(MAT_DIALOG_DATA);

  protected readonly vendors = signal<Vendor[]>([]);
  protected readonly vendorId = signal('');
  protected readonly eligible = signal<VendorReturnEligible | null>(null);
  protected readonly rows = signal<PickRow[]>([]);
  protected readonly loadError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);

  private levels: CreditLevels = { standard: null, damaged: null, expired: null };
  private createKey = newClientKey();
  private submitKey = newClientKey();

  protected readonly month = monthYearText;
  protected readonly rate = rateText;
  protected readonly money = rupees;
  protected readonly source = sourceChipText;

  protected readonly selected = computed(() => this.rows().filter((r) => r.selected));
  protected readonly creditTotal = computed(() =>
    this.selected().reduce((sum, r) => sum + (this.creditOf(r) ?? 0), 0),
  );
  protected readonly policy = computed(() => {
    const e = this.eligible();
    return e ? policyLine(e.policy, this.levels) : '';
  });

  ngOnInit(): void {
    this.api.vendors({ status: 'ACTIVE', suppliesStoreId: this.data.storeId, size: 100 }).subscribe({
      next: (page) => {
        this.vendors.set(
          page.items.filter((v) => !v.blocked),
        );
        const preset = this.data.vendorId;
        if (preset && this.vendors().some((v) => v.id === preset)) {
          this.chooseVendor(preset);
        }
      },
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
  }

  protected chooseVendor(vendorId: string): void {
    this.vendorId.set(vendorId);
    this.eligible.set(null);
    this.rows.set([]);
    this.loadError.set(null);
    this.formError.set(null);
    const vendor = this.vendors().find((v) => v.id === vendorId);
    this.levels = {
      standard: vendor?.returnCreditPercent ?? null,
      damaged: vendor?.damagedCreditPercent ?? null,
      expired: vendor?.expiredCreditPercent ?? null,
    };
    this.api.vendorReturnEligible(this.data.storeId, vendorId).subscribe({
      next: (answer) => {
        this.eligible.set(answer);
        this.levels = { ...this.levels, standard: this.levels.standard ?? answer.policy.creditPercent };
        this.rows.set(
          answer.items.map((item) => {
            const start = trimPercent(startingCreditPercent(item, this.levels));
            return {
              item,
              selected: this.data.batchId != null && item.batch?.batchId === this.data.batchId,
              qty: item.availableQty === null ? '' : String(item.availableQty),
              creditPercent: start,
              startPercent: start,
              error: null,
            };
          }),
        );
      },
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
  }

  protected toggle(index: number): void {
    this.rows.update((list) => list.map((r, i) => (i === index ? { ...r, selected: !r.selected, error: null } : r)));
  }

  protected edit(index: number, field: 'qty' | 'creditPercent', value: string): void {
    this.rows.update((list) => list.map((r, i) => (i === index ? { ...r, [field]: value, error: null } : r)));
  }

  protected changed(row: PickRow): boolean {
    return row.creditPercent.trim() !== row.startPercent;
  }

  private creditOf(row: PickRow): number | null {
    return creditPaise(Number(row.qty), row.item.rate, row.creditPercent.trim());
  }

  protected creditText(row: PickRow): string {
    const credit = row.qty === '' ? null : this.creditOf(row);
    return credit === null ? '—' : rupees(credit);
  }

  private problem(row: PickRow): string | null {
    const max = row.item.availableQty;
    if (wholeQty(1, max ?? 999999)(new FormControl(row.qty)) !== null) {
      return max === null ? 'Enter a whole number from 1' : `Enter 1 to ${max}`;
    }
    if (percentRange(new FormControl(row.creditPercent)) !== null) {
      return 'Enter a % from 0 to 100';
    }
    return null;
  }

  protected close(): void {
    this.ref.close(null);
  }

  protected create(): void {
    if (this.saving()) {
      return;
    }
    this.formError.set(null);
    let bad = false;
    const checked = this.rows().map((r) => {
      const error = r.selected ? this.problem(r) : null;
      bad = bad || error !== null;
      return { ...r, error };
    });
    this.rows.set(checked);
    const picked = checked.filter((r) => r.selected);
    if (bad || picked.length === 0) {
      return;
    }
    const lines: DebitNoteLineInput[] = picked.map((r) =>
      r.item.source === 'GRN_SHORT'
        ? {
            type: 'SHORT_SUPPLY',
            goodsReceiptLineId: r.item.goodsReceiptLineId,
            qty: Number(r.qty),
            creditPercent: r.creditPercent.trim(),
          }
        : {
            type: 'STOCK_RETURN',
            batchId: r.item.batch?.batchId ?? null,
            sourceState: r.item.sourceState,
            qty: Number(r.qty),
            creditPercent: r.creditPercent.trim(),
          },
    );
    this.saving.set(true);
    this.api
      .createDebitNote(this.data.storeId, { clientKey: this.createKey, vendorId: this.vendorId(), lines })
      .subscribe({
        next: (draft) => this.submit(draft),
        error: (error: unknown) => {
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
        },
      });
  }

  /** The draft is made first, then submitted: the number is taken and the units are held (or the approval is asked). */
  private submit(draft: DebitNote): void {
    this.api
      .submitDebitNote(this.data.storeId, draft.id, { clientKey: this.submitKey, version: draft.version })
      .subscribe({
        next: (done) => {
          this.saving.set(false);
          this.createKey = newClientKey();
          this.submitKey = newClientKey();
          this.ref.close(done);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.formError.set(`${saveErrorMessage(error)} The draft is kept in the list.`);
        },
      });
  }
}
