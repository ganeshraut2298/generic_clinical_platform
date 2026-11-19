import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { rupees } from '../opening-stock/opening-stock.util';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage } from '../pharmacy-display.util';
import {
  LowStockPage,
  ProductStock,
  StockBatchRow,
  StockOverview,
  StockView,
} from '../pharmacy-inventory.model';
import { PharmacyApi } from '../pharmacy.api';
import { DebitNote } from '../pharmacy-buying.model';
import { VENDOR_RETURN_CONFIG, VendorReturnData, VendorReturnDialog } from '../purchase/vendor-return.dialog';
import { BatchTableComponent } from './batch-table.component';
import { qtyWithUnit } from './inventory.util';
import { ProductPickerComponent } from './product-picker.component';
import {
  STOCK_ADJUSTMENT_CONFIG,
  StockAdjustmentData,
  StockAdjustmentDialog,
} from './stock-adjustment.dialog';
import { StockAdjustment } from '../pharmacy-inventory.model';

type Pill = 'ALL' | 'NEAR_EXPIRY' | 'EXPIRED' | 'LOW_STOCK' | 'SCHEDULE';

/** The product and store another screen asks this one to open (the store-name link of "Store stock", 79-4). */
export interface BatchFocus {
  storeId: string;
  productId: string;
}

const PAGE_SIZE = 25;

/**
 * Batch inventory and expiry (story 79-3, mockup 06): the header "<n> SKUs · <n> batches · stock value ₹ <amount>", the
 * views All batches, Near expiry (30, 60 or 90 days), Expired · quarantined, Low stock and Schedule H / H1 / X, a
 * product search, the batch table in FEFO order and, once a product is chosen, its heading line with the rack and
 * reorder level of the store. Not built (Later in the story): the ABC and XYZ matrix, non-moving, cold chain, "Plan"
 * and "Branch transfer". The owner picks any store; everyone else sees the stores they are mapped to.
 */
@Component({
  selector: 'app-batch-inventory',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatSelectModule,
    IconComponent,
    EmptyStateComponent,
    BatchTableComponent,
    ProductPickerComponent,
  ],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      justify-content: space-between;
      gap: 10px;
    }
    .pills {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .pills button,
    .buckets button {
      min-height: 44px;
      padding: 8px 12px;
      border: 1px solid var(--line);
      border-radius: 999px;
      background: var(--glass-hi);
      font-weight: 600;
      font-size: 0.86rem;
    }
    .pills button[aria-pressed='true'],
    .buckets button[aria-pressed='true'] {
      border: 2px solid var(--pri);
      padding: 7px 11px;
      background: var(--pri-soft);
      color: var(--pri-text);
    }
    .buckets {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .sub {
      font-size: 0.78rem;
      color: var(--ink-3);
    }
    .acts {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
    }
    .num {
      text-align: right;
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
    <section class="panel surf" aria-labelledby="bi-h" data-testid="batch-inventory">
      <div class="head">
        <div>
          <h2 id="bi-h">Batch inventory & expiry</h2>
          @if (overview(); as o) {
            <p class="sub" data-testid="inventory-summary">
              {{ o.skuCount }} SKUs · {{ o.batchCount }} batches · stock value {{ o.stockValuePaise === null ? '—' : money(o.stockValuePaise) }}
            </p>
          }
        </div>
        <div class="acts">
          @if (stores().length > 1) {
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Store</mat-label>
              <mat-select
                [value]="storeId()"
                (selectionChange)="chooseStore($event.value)"
                data-testid="inventory-store"
              >
                @for (s of stores(); track s.storeId) {
                  <mat-option [value]="s.storeId">{{ s.name }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
          }
          @if (canAdjust()) {
            <button mat-stroked-button type="button" data-testid="open-adjustment" (click)="openAdjustment(null)">
              Stock adjustment
            </button>
          }
        </div>
      </div>

      @if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="inventory-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="reload()">Try again</button>
        </div>
      } @else if (!storeId()) {
        <app-empty>Pick your working store to see its stock.</app-empty>
      } @else {
        <app-product-picker (picked)="openProduct($event.productId)" />

        @if (adjustedNote(); as text) {
          <div class="notice good" role="status" data-testid="adjusted-note">
            <app-icon name="check" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }

        @if (detail(); as d) {
          <div data-testid="product-heading">
            <h3>{{ d.product.name }} · {{ d.product.strength }} · {{ d.product.form }}{{ d.pack ? ' · ' + d.pack : '' }}</h3>
            <p class="sub" data-testid="product-line">
              Salt: {{ d.salt }} · HSN {{ d.hsn }} · GST {{ d.gstRate }}% · Rack {{ d.rack ?? '—' }} · Reorder level
              {{ d.reorderLevel === null ? '—' : d.reorderLevel + ' ' + d.product.issueUnit }}
            </p>
            <p class="note">Stock lives at product + batch level. Every issue picks the top row first (FEFO).</p>
          </div>
          @if (d.batches.length === 0) {
            <app-empty>No stock of this product in this store.</app-empty>
          } @else {
            <app-batch-table [rows]="d.batches" [canAdjust]="canAdjust()" (adjust)="openAdjustment($event)" (vendorReturn)="openVendorReturn($event)" />
          }
          <div class="acts">
            <button mat-stroked-button type="button" data-testid="close-product" (click)="choosePill(active())">
              Back to the list
            </button>
          </div>
        } @else {
          <div class="pills" role="group" aria-label="Views" data-testid="pills">
            @for (pill of pills(); track pill.key) {
              <button
                type="button"
                [attr.aria-pressed]="pill.key === active()"
                [attr.data-testid]="'pill-' + pill.key"
                (click)="choosePill(pill.key)"
              >
                {{ pill.label }}
              </button>
            }
          </div>
          @if (active() === 'NEAR_EXPIRY') {
            <div class="buckets" role="group" aria-label="Days to expiry" data-testid="buckets">
              @for (days of bucketDays; track days) {
                <button
                  type="button"
                  [attr.aria-pressed]="days === within()"
                  [attr.data-testid]="'bucket-' + days"
                  (click)="chooseBucket(days)"
                >
                  {{ days }} days
                </button>
              }
            </div>
          }

          @if (listLoading()) {
            <p class="loading" role="status">Loading stock</p>
          } @else if (active() === 'LOW_STOCK') {
            @if (low(); as l) {
              @if (l.items.length === 0) {
                <app-empty>Nothing is at or below its reorder level.</app-empty>
              } @else {
                <div class="tbl-wrap" data-testid="low-stock-table">
                  <table>
                    <thead>
                      <tr>
                        <th scope="col">Product</th>
                        <th scope="col" class="num">Available</th>
                        <th scope="col" class="num">Reorder level</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (item of l.items; track item.product.productId) {
                        <tr [attr.data-testid]="'low-' + item.product.code">
                          <td>
                            <button type="button" class="linkbtn" (click)="openProduct(item.product.productId)">
                              {{ item.product.name }}
                            </button>
                            <div class="sub">{{ item.product.strength }} · {{ item.product.form }}</div>
                          </td>
                          <td class="num">{{ qtyUnit(item.available, item.product.issueUnit) }}</td>
                          <td class="num">{{ item.reorderLevel }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
              @if (l.total > PAGE_SIZE) {
                <div class="pager" data-testid="low-pager">
                  <span role="status">Page {{ l.page + 1 }} of {{ pages(l.total) }}</span>
                  <button mat-stroked-button type="button" [disabled]="l.page === 0" (click)="loadLow(l.page - 1)">
                    Previous
                  </button>
                  <button
                    mat-stroked-button
                    type="button"
                    [disabled]="(l.page + 1) * PAGE_SIZE >= l.total"
                    (click)="loadLow(l.page + 1)"
                  >
                    Next
                  </button>
                </div>
              }
            }
          } @else if (overview()?.empty) {
            <app-empty data-testid="inventory-empty">
              No stock in this store yet. Post opening stock or receive stock.
            </app-empty>
          } @else if (rows().length === 0) {
            <app-empty data-testid="list-empty">No batches in this view.</app-empty>
          } @else {
            <app-batch-table
              [rows]="rows()"
              [showProduct]="true"
              [canAdjust]="canAdjust()"
              (productOpened)="openProduct($event.product.productId)"
              (adjust)="openAdjustment($event)"
              (vendorReturn)="openVendorReturn($event)"
            />
            @if (total() > PAGE_SIZE) {
              <div class="pager" data-testid="batch-pager">
                <span role="status">Page {{ page() + 1 }} of {{ pages(total()) }}</span>
                <button
                  mat-stroked-button
                  type="button"
                  data-testid="batch-prev"
                  [disabled]="page() === 0"
                  (click)="loadBatches(page() - 1)"
                >
                  Previous
                </button>
                <button
                  mat-stroked-button
                  type="button"
                  data-testid="batch-next"
                  [disabled]="(page() + 1) * PAGE_SIZE >= total()"
                  (click)="loadBatches(page() + 1)"
                >
                  Next
                </button>
              </div>
            }
          }
        }
      }
    </section>
  `,
})
export class BatchInventorySection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  private readonly context = inject(PharmacyContextStore);

  /** Another screen asks this one to open a product in a store (the store link of "Store stock"). */
  readonly focus = input<BatchFocus | null>(null);

  protected readonly PAGE_SIZE = PAGE_SIZE;
  protected readonly bucketDays: readonly (30 | 60 | 90)[] = [30, 60, 90];
  protected readonly money = rupees;
  protected readonly qtyUnit = qtyWithUnit;

  protected readonly stores = this.context.stores;
  protected readonly storeId = signal<string | null>(this.context.workingStoreId());
  protected readonly active = signal<Pill>('ALL');
  protected readonly within = signal<30 | 60 | 90>(90);
  protected readonly overview = signal<StockOverview | null>(null);
  protected readonly rows = signal<StockBatchRow[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly low = signal<LowStockPage | null>(null);
  protected readonly detail = signal<ProductStock | null>(null);
  protected readonly listLoading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly adjustedNote = signal<string | null>(null);

  protected readonly canAdjust = computed(() => {
    const id = this.storeId();
    return id !== null && this.stores().find((s) => s.storeId === id)?.access.adjustStock === true;
  });

  protected readonly pills = computed<{ key: Pill; label: string }[]>(() => {
    const p = this.overview()?.pills;
    return [
      { key: 'ALL', label: 'All batches' },
      { key: 'NEAR_EXPIRY', label: `Near expiry (90 d)${p ? ' · ' + p.nearExpiry90 : ''}` },
      { key: 'EXPIRED', label: `Expired · quarantined${p ? ' · ' + p.expiredQuarantined : ''}` },
      { key: 'LOW_STOCK', label: `Low stock${p ? ' · ' + p.lowStock : ''}` },
      { key: 'SCHEDULE', label: 'Schedule H / H1 / X' },
    ];
  });

  constructor() {
    // A product link from "Store stock" opens that product in that store.
    effect(() => {
      const f = this.focus();
      if (f) {
        untracked(() => {
          this.storeId.set(f.storeId);
          this.loadOverview();
          this.openProduct(f.productId);
        });
      }
    });
  }

  ngOnInit(): void {
    if (this.focus() === null) {
      this.reload();
    }
  }

  protected pages(total: number): number {
    return Math.max(1, Math.ceil(total / PAGE_SIZE));
  }

  protected reload(): void {
    this.loadError.set(null);
    this.loadOverview();
    this.detail.set(null);
    this.choosePill(this.active());
  }

  protected chooseStore(storeId: string): void {
    this.storeId.set(storeId);
    this.detail.set(null);
    this.reload();
  }

  private loadOverview(): void {
    const id = this.storeId();
    if (!id) {
      return;
    }
    this.api.stockOverview(id).subscribe({
      next: (o) => this.overview.set(o),
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
  }

  protected choosePill(pill: Pill): void {
    this.active.set(pill);
    this.detail.set(null);
    if (pill === 'LOW_STOCK') {
      this.loadLow(0);
    } else {
      this.loadBatches(0);
    }
  }

  protected chooseBucket(days: 30 | 60 | 90): void {
    this.within.set(days);
    this.loadBatches(0);
  }

  protected loadBatches(page: number): void {
    const id = this.storeId();
    if (!id) {
      return;
    }
    const pill = this.active();
    const view: StockView = pill === 'LOW_STOCK' ? 'ALL' : pill;
    this.listLoading.set(true);
    this.api
      .stockBatches(id, { view, withinDays: this.within(), page, size: PAGE_SIZE })
      .subscribe({
        next: (answer) => {
          this.rows.set(answer.items);
          this.total.set(answer.total);
          this.page.set(answer.page);
          this.listLoading.set(false);
        },
        error: (error: unknown) => {
          this.listLoading.set(false);
          this.loadError.set(loadErrorMessage(error));
        },
      });
  }

  protected loadLow(page: number): void {
    const id = this.storeId();
    if (!id) {
      return;
    }
    this.listLoading.set(true);
    this.api.lowStock(id, { page, size: PAGE_SIZE }).subscribe({
      next: (answer) => {
        this.low.set(answer);
        this.listLoading.set(false);
      },
      error: (error: unknown) => {
        this.listLoading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }

  protected openProduct(productId: string): void {
    const id = this.storeId();
    if (!id) {
      return;
    }
    this.loadError.set(null);
    this.api.productStock(id, productId).subscribe({
      next: (stock) => this.detail.set(stock),
      error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
    });
  }

  /** "Return" on a quarantined or near-expiry batch: the vendor-return sheet with the batch and its vendor chosen. */
  protected openVendorReturn(row: StockBatchRow): void {
    const id = this.storeId();
    if (!id) {
      return;
    }
    const data: VendorReturnData = {
      storeId: id,
      vendorId: row.supplier?.vendorId ?? null,
      batchId: row.batch.batchId,
    };
    this.dialog
      .open(VendorReturnDialog, { ...VENDOR_RETURN_CONFIG, data })
      .afterClosed()
      .subscribe((done: DebitNote | null | undefined) => {
        if (!done) {
          return;
        }
        this.adjustedNote.set(`${done.number ?? 'The debit note'} is created. The units are held for the vendor.`);
        const open = this.detail();
        this.loadOverview();
        if (open) {
          this.openProduct(open.product.productId);
        } else {
          this.choosePill(this.active());
        }
      });
  }

  protected openAdjustment(preset: StockBatchRow | null): void {
    const id = this.storeId();
    const ctx = this.context.context();
    if (!id || !ctx) {
      return;
    }
    const data: StockAdjustmentData = {
      storeId: id,
      storeName: this.stores().find((s) => s.storeId === id)?.name ?? '',
      preset,
      callerMembershipId: ctx.caller.membershipId,
      callerRole: ctx.caller.role,
    };
    this.dialog
      .open(StockAdjustmentDialog, { ...STOCK_ADJUSTMENT_CONFIG, data })
      .afterClosed()
      .subscribe((done: StockAdjustment | null | undefined) => {
        if (!done) {
          return;
        }
        this.adjustedNote.set(
          done.status === 'POSTED'
            ? `${done.number} posted. The batch now shows the new quantity.`
            : `${done.number} is waiting for approval. It shows in the Approvals inbox.`,
        );
        const open = this.detail();
        this.loadOverview();
        if (open) {
          this.openProduct(open.product.productId);
        } else {
          this.choosePill(this.active());
        }
      });
  }

}
