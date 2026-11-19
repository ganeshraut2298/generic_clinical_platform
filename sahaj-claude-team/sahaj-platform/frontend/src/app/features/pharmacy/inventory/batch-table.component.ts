import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Chip } from '../pharmacy-display.util';
import { STOCK_STATE_LABELS, StockBatchRow } from '../pharmacy-inventory.model';
import { rateText } from '../opening-stock/opening-stock.util';
import { batchStatusChips, expiryMonthYear, marginText, stateQty } from './inventory.util';

/**
 * The batch table of mockup 06: FEFO #, Batch, Expiry (month and year), Qty, MRP, Purchase rate, Landed, Margin,
 * Supplier, Status, and one action cell. A row is one batch in one state, so a quarantined quantity of a batch has its
 * own row with the state in the Status chip. The Product column is added in the store-wide list.
 */
@Component({
  selector: 'app-batch-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule],
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    table {
      min-width: 960px;
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
    .sub {
      font-size: 0.76rem;
      color: var(--ink-3);
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
    }
    .linkbtn {
      border: 0;
      background: transparent;
      padding: 0;
      min-height: 44px;
      text-align: left;
      font-weight: 600;
      color: var(--pri-text);
      text-decoration: underline;
    }
  `,
  template: `
    <div class="tbl-wrap" data-testid="batch-table">
      <table>
        <thead>
          <tr>
            @if (showProduct()) {
              <th scope="col">Product</th>
            }
            <th scope="col">FEFO #</th>
            <th scope="col">Batch</th>
            <th scope="col">Expiry</th>
            <th scope="col" class="num">Qty</th>
            <th scope="col" class="num">MRP</th>
            <th scope="col" class="num">Purchase rate</th>
            <th scope="col" class="num">Landed</th>
            <th scope="col" class="num">Margin</th>
            <th scope="col">Supplier</th>
            <th scope="col">Status</th>
            @if (hasActions()) {
              <th scope="col"><span class="sr-only">Action</span></th>
            }
          </tr>
        </thead>
        <tbody>
          @for (row of rows(); track row.batch.batchId + row.state) {
            <tr [attr.data-testid]="'batch-' + row.batch.batchNo + '-' + row.state">
              @if (showProduct()) {
                <td>
                  <button
                    type="button"
                    class="linkbtn"
                    [attr.data-testid]="'open-product-' + row.product.code"
                    (click)="productOpened.emit(row)"
                  >
                    {{ row.product.name }}
                  </button>
                  <div class="sub">{{ row.product.strength }} · {{ row.product.form }}</div>
                </td>
              }
              <td data-testid="fefo">{{ row.fefoRank ?? '—' }}</td>
              <td>{{ row.batch.batchNo }}</td>
              <td>{{ month(row.batch.expiryDate) }}</td>
              <td class="num">
                {{ qty(row.qty) }}
                @if (row.qty !== null) {
                  <span class="sub">{{ row.product.issueUnit }}</span>
                }
              </td>
              <td class="num">{{ rate(row.mrp) }}</td>
              <td class="num">{{ rate(row.purchaseRate) }}</td>
              <td class="num">{{ rate(row.landed) }}</td>
              <td class="num">{{ margin(row.marginPercent) }}</td>
              <td>{{ row.supplier?.name ?? '—' }}</td>
              <td>
                <span class="chips">
                  @for (chip of chips(row); track chip.text) {
                    <span class="pill" [class]="chip.css" data-testid="status-chip">{{ chip.text }}</span>
                  }
                </span>
              </td>
              @if (hasActions()) {
                <td>
                  @if (canAdjust() && row.state === 'AVAILABLE' && row.daysToExpiry >= 0) {
                    <button
                      mat-stroked-button
                      type="button"
                      [attr.data-testid]="'adjust-' + row.batch.batchNo"
                      (click)="adjust.emit(row)"
                    >
                      Adjust
                    </button>
                  }
                  @if (row.canVendorReturn) {
                    <button
                      mat-stroked-button
                      type="button"
                      [attr.data-testid]="'return-' + row.batch.batchNo + '-' + row.state"
                      [attr.aria-label]="'Return ' + row.product.name + ' batch ' + row.batch.batchNo + ' to the vendor'"
                      (click)="vendorReturn.emit(row)"
                    >
                      Return
                    </button>
                  }
                </td>
              }
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
export class BatchTableComponent {
  readonly rows = input.required<StockBatchRow[]>();
  readonly showProduct = input(false);
  /** The "Adjust" action of an Available batch, for a person with the adjust capability (79-5). */
  readonly canAdjust = input(false);
  readonly productOpened = output<StockBatchRow>();
  readonly adjust = output<StockBatchRow>();
  /** The "Return" action of a quarantined or near-expiry batch at the Central Store (79-3, mockup 06). */
  readonly vendorReturn = output<StockBatchRow>();

  protected readonly hasActions = computed(() => this.canAdjust() || this.rows().some((r) => r.canVendorReturn));

  protected readonly month = expiryMonthYear;
  protected readonly qty = stateQty;
  protected readonly rate = rateText;
  protected readonly margin = marginText;
  protected readonly states = STOCK_STATE_LABELS;
  protected chips(row: StockBatchRow): Chip[] {
    return batchStatusChips(row);
  }
}
