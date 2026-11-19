import { ChangeDetectionStrategy, Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { FormControl } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { rateText, rupees } from '../opening-stock/opening-stock.util';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, saveErrorMessage } from '../pharmacy-display.util';
import {
  PurchaseOrder,
  PurchaseOrderPage,
  PurchaseOrderStatus,
  PurchaseOrdersFromSuggestionsRow,
  ReorderSuggestionPage,
  ReorderSuggestionRow,
} from '../pharmacy-buying.model';
import { PharmacyApi } from '../pharmacy.api';
import { Vendor } from '../pharmacy.model';
import { PO_DETAIL_CONFIG, PoDetailDialog, PoDetailResult } from './po-detail.dialog';
import { PO_FORM_CONFIG, PoFormDialog } from './po-form.dialog';
import { overdueText, poStatusChip, positiveRate, trimRate, wholeQty } from './purchase.util';

type Tile = 'ALL' | 'DRAFT' | 'WAITING_APPROVAL' | 'SENT' | 'PARTLY_RECEIVED' | 'OVERDUE';

const PAGE_SIZE = 20;

interface ReorderDraft {
  row: ReorderSuggestionRow;
  selected: boolean;
  qty: string;
  rate: string;
  vendorId: string;
  /** Messages shown on the row after "Create POs". */
  error: string | null;
}

/**
 * Purchase orders of the Central Store (story 79-6, mockup 07): the header with "+ Manual PO", the tiles Draft,
 * Waiting for approval, Sent to vendor, Partly received and Overdue delivery (each filters the list), the card
 * "Reorder suggestions · <n> items" with "Create POs (split by vendor)", and "Recent POs". Any other working store
 * sees "Purchase orders are raised at the Central Store." and nothing else. Not built from the mockup: average daily
 * use, the best vendor ranking and WhatsApp or e-mail sending.
 */
@Component({
  selector: 'app-purchase-orders',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, EmptyStateComponent],
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
      align-items: center;
      justify-content: space-between;
      gap: 10px;
    }
    .tiles {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .tiles button {
      min-height: 44px;
      min-width: 120px;
      padding: 8px 12px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
      text-align: left;
      font-weight: 600;
    }
    .tiles button[aria-pressed='true'] {
      border: 2px solid var(--pri);
      padding: 7px 11px;
      background: var(--pri-soft);
      color: var(--pri-text);
    }
    .tiles b {
      display: block;
      font-size: 1.2rem;
    }
    table {
      min-width: 880px;
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
    .sub {
      font-size: 0.78rem;
      color: var(--ink-3);
    }
    input.cell,
    select.cell {
      min-height: 44px;
      width: 100%;
      min-width: 80px;
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
    .pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
    }
  `,
  template: `
    @if (!isCentral()) {
      <app-empty data-testid="po-not-central">Purchase orders are raised at the Central Store.</app-empty>
    } @else {
      <section class="panel surf" aria-labelledby="po-h" data-testid="purchase-orders">
        <div class="head">
          <div>
            <h2 id="po-h">Purchase orders</h2>
            <p class="sub">{{ storeName() }}</p>
          </div>
          <button mat-flat-button type="button" data-testid="new-po" (click)="openForm()">+ Manual PO</button>
        </div>

        @if (notice(); as text) {
          <div class="notice good" role="status" data-testid="po-note">
            <app-icon name="check" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }

        @if (tiles(); as t) {
          <div class="tiles" role="group" aria-label="Purchase order status" data-testid="po-tiles">
            @for (tile of tileList; track tile.key) {
              <button
                type="button"
                [attr.aria-pressed]="tileKey() === tile.key"
                [attr.data-testid]="'po-tile-' + tile.key"
                (click)="chooseTile(tile.key)"
              >
                {{ tile.label }}
                <b>{{ tileCount(tile.key) }}</b>
              </button>
            }
          </div>
        }
      </section>

      <section class="panel surf" aria-labelledby="re-h" data-testid="reorder-card">
        <h2 id="re-h">Reorder suggestions · {{ drafts().length }} items</h2>
        @if (reorderLoading()) {
          <p class="loading" role="status">Loading reorder suggestions</p>
        } @else if (reorderError(); as text) {
          <div class="notice err" role="alert" data-testid="reorder-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
            <button mat-stroked-button type="button" (click)="loadReorder()">Try again</button>
          </div>
        } @else if (drafts().length === 0) {
          <app-empty data-testid="reorder-empty">Nothing is at or below its reorder level.</app-empty>
        } @else {
          <div class="tbl-wrap">
            <table data-testid="reorder-table">
              <thead>
                <tr>
                  <th scope="col"><span class="sr-only">Select</span></th>
                  <th scope="col">Item and purchase unit</th>
                  <th scope="col" class="num">CS stock</th>
                  @if (isOwner()) {
                    <th scope="col" class="num">All stores</th>
                  }
                  <th scope="col">Open PO</th>
                  <th scope="col" class="num">Suggested</th>
                  <th scope="col">Vendor</th>
                  <th scope="col" class="num">Rate</th>
                </tr>
              </thead>
              <tbody>
                @for (d of drafts(); track d.row.product.productId; let i = $index) {
                  <tr [attr.data-testid]="'reorder-' + d.row.product.code">
                    <td>
                      <input
                        type="checkbox"
                        [checked]="d.selected"
                        [attr.aria-label]="'Select ' + d.row.product.name"
                        [attr.data-testid]="'reorder-select-' + d.row.product.code"
                        (change)="toggle(i)"
                      />
                    </td>
                    <td>
                      {{ d.row.product.name }}
                      <div class="sub">
                        {{ d.row.product.strength }} · {{ d.row.product.form }} · per {{ d.row.product.purchaseUnit }}
                      </div>
                    </td>
                    <td class="num" [attr.data-testid]="'reorder-cs-' + d.row.product.code">
                      @if (d.row.csStock === null) {
                        <span title="Counting in progress">—</span>
                      } @else {
                        {{ d.row.csStock }} {{ d.row.product.issueUnit }}
                      }
                    </td>
                    @if (isOwner()) {
                      <td class="num">
                        @if (d.row.allStoresStock === null) {
                          <span title="Counting in progress">—</span>
                        } @else {
                          {{ d.row.allStoresStock }}
                        }
                      </td>
                    }
                    <td>
                      @if (d.row.openPoNo) {
                        {{ d.row.openPoNo }}
                      } @else {
                        —
                      }
                    </td>
                    <td class="num">
                      <input
                        class="cell"
                        inputmode="numeric"
                        [value]="d.qty"
                        [attr.aria-label]="'Suggested quantity of ' + d.row.product.name"
                        [attr.data-testid]="'reorder-qty-' + d.row.product.code"
                        (input)="edit(i, 'qty', $any($event.target).value)"
                      />
                    </td>
                    <td>
                      @if (d.row.vendor && !d.row.vendor.blocked) {
                        {{ d.row.vendor.name }}
                      } @else {
                        <select
                          class="cell"
                          [attr.aria-label]="'Vendor for ' + d.row.product.name"
                          [attr.data-testid]="'reorder-vendor-' + d.row.product.code"
                          (change)="edit(i, 'vendorId', $any($event.target).value)"
                        >
                          <option value="" [selected]="d.vendorId === ''">Choose a vendor</option>
                          @for (v of vendors(); track v.id) {
                            <option [value]="v.id" [selected]="d.vendorId === v.id">{{ v.name }}</option>
                          }
                        </select>
                        @if (d.row.vendor?.blocked) {
                          <div class="sub">{{ d.row.vendor?.name }} · Blocked · licence expired</div>
                        }
                      }
                    </td>
                    <td class="num">
                      <input
                        class="cell"
                        inputmode="decimal"
                        [value]="d.rate"
                        [attr.aria-label]="'Rate of ' + d.row.product.name"
                        [attr.data-testid]="'reorder-rate-' + d.row.product.code"
                        (input)="edit(i, 'rate', $any($event.target).value)"
                      />
                      @if (d.error; as text) {
                        <div class="row-err" role="alert">{{ text }}</div>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (createError(); as text) {
            <div class="notice err" role="alert" data-testid="reorder-create-error">
              <app-icon name="alert" [size]="16" />
              <span>{{ text }}</span>
            </div>
          }
          <div>
            <button
              mat-flat-button
              type="button"
              data-testid="create-pos"
              [disabled]="creating() || selectedCount() === 0"
              (click)="createFromSuggestions()"
            >
              Create POs (split by vendor)
            </button>
          </div>
        }
      </section>

      <section class="panel surf" aria-labelledby="rp-h" data-testid="recent-pos">
        <h2 id="rp-h">Recent POs</h2>
        @if (loading()) {
          <p class="loading" role="status">Loading purchase orders</p>
        } @else if (loadError(); as text) {
          <div class="notice err" role="alert" data-testid="po-list-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
            <button mat-stroked-button type="button" (click)="load(0)">Try again</button>
          </div>
        } @else if (page(); as d) {
          @if (d.items.length === 0) {
            <app-empty data-testid="po-empty">
              No purchase orders yet. Create one from the reorder list or choose + Manual PO.
            </app-empty>
          } @else {
            <div class="tbl-wrap">
              <table data-testid="po-table">
                <thead>
                  <tr>
                    <th scope="col">PO</th>
                    <th scope="col">Vendor</th>
                    <th scope="col" class="num">Value</th>
                    <th scope="col">Status</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of d.items; track row.id) {
                    <tr>
                      <td>
                        <button
                          mat-button
                          type="button"
                          [attr.data-testid]="'po-open-' + (row.number ?? row.id)"
                          (click)="openDetail(row.id)"
                        >
                          {{ row.number ?? 'Draft' }}
                        </button>
                      </td>
                      <td>{{ row.vendorName }}</td>
                      <td class="num">{{ money(row.totalPaise) }}</td>
                      <td>
                        <span class="pill" [class]="chip(row.status, row.shortLineCount).css">{{
                          chip(row.status, row.shortLineCount).text
                        }}</span>
                        @if (overdue(row.overdueDays); as text) {
                          <span class="pill p-crit">{{ text }}</span>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            @if (d.total > PAGE_SIZE) {
              <div class="pager">
                <span role="status">Page {{ d.page + 1 }} of {{ pages(d.total) }}</span>
                <button mat-stroked-button type="button" [disabled]="d.page === 0" (click)="load(d.page - 1)">
                  Previous
                </button>
                <button
                  mat-stroked-button
                  type="button"
                  [disabled]="(d.page + 1) * PAGE_SIZE >= d.total"
                  (click)="load(d.page + 1)"
                >
                  Next
                </button>
              </div>
            }
          }
        }
      </section>
    }
  `,
})
export class PurchaseOrdersSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  private readonly context = inject(PharmacyContextStore);

  /** "Open in approvals": the Inventory page switches to the Approvals inbox tab. */
  readonly approvalsRequested = output<void>();

  protected readonly PAGE_SIZE = PAGE_SIZE;
  protected readonly tileList: { key: Tile; label: string }[] = [
    { key: 'DRAFT', label: 'Draft' },
    { key: 'WAITING_APPROVAL', label: 'Waiting for approval' },
    { key: 'SENT', label: 'Sent to vendor' },
    { key: 'PARTLY_RECEIVED', label: 'Partly received' },
    { key: 'OVERDUE', label: 'Overdue delivery' },
  ];

  protected readonly isCentral = this.context.isCentralStore;
  protected readonly storeName = computed(() => this.context.workingStore()?.name ?? '');
  protected readonly isOwner = computed(() => this.context.context()?.caller.role === 'OWNER');

  protected readonly page = signal<PurchaseOrderPage | null>(null);
  protected readonly tiles = computed(() => this.page()?.tiles ?? this.lastTiles());
  private readonly lastTiles = signal<PurchaseOrderPage['tiles'] | null>(null);
  protected readonly tileKey = signal<Tile>('ALL');
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly drafts = signal<ReorderDraft[]>([]);
  protected readonly vendors = signal<Vendor[]>([]);
  protected readonly reorderLoading = signal(true);
  protected readonly reorderError = signal<string | null>(null);
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly selectedCount = computed(() => this.drafts().filter((d) => d.selected).length);
  private createKey = newClientKey();

  protected readonly money = rupees;
  protected readonly rate = rateText;
  protected readonly chip = (status: PurchaseOrderStatus, shortLineCount = 0) => poStatusChip(status, shortLineCount);
  protected readonly overdue = overdueText;

  ngOnInit(): void {
    if (!this.isCentral()) {
      return;
    }
    this.load(0);
    this.loadReorder();
    const storeId = this.context.workingStoreId();
    this.api.vendors({ status: 'ACTIVE', suppliesStoreId: storeId ?? undefined, size: 100 }).subscribe({
      next: (answer) => {
        this.vendors.set(answer.items.filter((v) => !v.blocked));
      },
      error: () => this.vendors.set([]),
    });
  }

  protected tileCount(key: Tile): number {
    const t = this.tiles();
    if (!t) {
      return 0;
    }
    switch (key) {
      case 'DRAFT':
        return t.draft;
      case 'WAITING_APPROVAL':
        return t.waitingApproval;
      case 'SENT':
        return t.sent;
      case 'PARTLY_RECEIVED':
        return t.partlyReceived;
      case 'OVERDUE':
        return t.overdue;
      default:
        return 0;
    }
  }

  protected pages(total: number): number {
    return Math.max(1, Math.ceil(total / PAGE_SIZE));
  }

  protected chooseTile(key: Tile): void {
    this.tileKey.set(this.tileKey() === key ? 'ALL' : key);
    this.load(0);
  }

  protected load(page: number): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    const tile = this.tileKey();
    const query =
      tile === 'ALL'
        ? { page, size: PAGE_SIZE }
        : tile === 'OVERDUE'
          ? { overdue: true, page, size: PAGE_SIZE }
          : { status: [tile as PurchaseOrderStatus], page, size: PAGE_SIZE };
    this.api.purchaseOrders(storeId, query).subscribe({
      next: (answer) => {
        this.lastTiles.set(answer.tiles);
        this.page.set(answer);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }

  protected loadReorder(): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      this.reorderLoading.set(false);
      return;
    }
    this.reorderLoading.set(true);
    this.reorderError.set(null);
    this.api.reorderSuggestions(storeId).subscribe({
      next: (answer: ReorderSuggestionPage) => {
        this.drafts.set(answer.items.map((row) => this.draftOf(row)));
        this.reorderLoading.set(false);
      },
      error: (error: unknown) => {
        this.reorderLoading.set(false);
        this.reorderError.set(loadErrorMessage(error));
      },
    });
  }

  private draftOf(row: ReorderSuggestionRow): ReorderDraft {
    return {
      row,
      // A product that an open purchase order already covers shows 0 and is not selected.
      selected: row.suggestedQty > 0,
      qty: String(row.suggestedQty),
      rate: row.lastRate ? trimRate(row.lastRate) : '',
      vendorId: row.vendor && !row.vendor.blocked ? row.vendor.vendorId : '',
      error: null,
    };
  }

  protected toggle(index: number): void {
    this.drafts.update((list) => list.map((d, i) => (i === index ? { ...d, selected: !d.selected, error: null } : d)));
  }

  protected edit(index: number, field: 'qty' | 'rate' | 'vendorId', value: string): void {
    this.drafts.update((list) => list.map((d, i) => (i === index ? { ...d, [field]: value, error: null } : d)));
  }

  /** The client checks of the contract; the server checks again and its answer is shown. */
  private checkRow(d: ReorderDraft): string | null {
    if (d.vendorId === '' && !(d.row.vendor && !d.row.vendor.blocked)) {
      return 'Choose a vendor';
    }
    if (wholeQty(1, 999999)(new FormControl(d.qty)) !== null) {
      return 'Enter a whole number from 1 to 999999';
    }
    if (positiveRate(new FormControl(d.rate)) !== null) {
      return 'Enter a rate above 0, up to 4 decimals';
    }
    return null;
  }

  protected createFromSuggestions(): void {
    const storeId = this.context.workingStoreId();
    if (!storeId || this.creating()) {
      return;
    }
    this.createError.set(null);
    let bad = false;
    const checked = this.drafts().map((d) => {
      const error = d.selected ? this.checkRow(d) : null;
      bad = bad || error !== null;
      return { ...d, error };
    });
    this.drafts.set(checked);
    if (bad) {
      return;
    }
    const rows: PurchaseOrdersFromSuggestionsRow[] = checked
      .filter((d) => d.selected)
      .map((d) => ({
        productId: d.row.product.productId,
        vendorId: d.vendorId === '' ? (d.row.vendor?.vendorId ?? null) : d.vendorId,
        qty: Number(d.qty),
        rate: d.rate.trim(),
      }));
    this.creating.set(true);
    this.api.createPurchaseOrdersFromSuggestions(storeId, { clientKey: this.createKey, rows }).subscribe({
      next: (created) => {
        this.creating.set(false);
        this.createKey = newClientKey();
        this.notice.set(
          created.length === 1 ? '1 draft purchase order created.' : `${created.length} draft purchase orders created.`,
        );
        this.load(0);
        this.loadReorder();
      },
      error: (error: unknown) => {
        this.creating.set(false);
        this.createError.set(saveErrorMessage(error));
      },
    });
  }

  protected openForm(po?: { id: string }): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    if (po) {
      this.api.purchaseOrder(storeId, po.id).subscribe({
        next: (full) => this.showForm(storeId, full),
        error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
      });
      return;
    }
    this.showForm(storeId);
  }

  private showForm(storeId: string, po?: PurchaseOrder): void {
    this.dialog
      .open(PoFormDialog, { ...PO_FORM_CONFIG, data: { storeId, po } })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notice.set('Draft purchase order saved.');
          this.load(0);
        }
      });
  }

  protected openDetail(poId: string): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    this.dialog
      .open(PoDetailDialog, { ...PO_DETAIL_CONFIG, data: { storeId, poId } })
      .afterClosed()
      .subscribe((result: PoDetailResult | undefined) => {
        if (!result) {
          return;
        }
        if (result.changed) {
          this.load(0);
          this.loadReorder();
        }
        if (result.next === 'edit') {
          this.openForm({ id: poId });
        } else if (result.next === 'approvals') {
          this.approvalsRequested.emit();
        } else if (result.next) {
          this.openDetail(result.next.open);
        }
      });
  }
}
