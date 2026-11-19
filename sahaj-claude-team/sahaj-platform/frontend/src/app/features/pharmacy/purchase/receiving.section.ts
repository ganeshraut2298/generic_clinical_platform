import { ChangeDetectionStrategy, Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { rupees } from '../opening-stock/opening-stock.util';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage } from '../pharmacy-display.util';
import { GoodsReceiptPage, PurchaseOrderPage } from '../pharmacy-buying.model';
import { PharmacyApi } from '../pharmacy.api';
import { GRN_DIALOG_CONFIG, GrnDialog, GrnDialogData, GrnDialogResult } from './grn.dialog';
import { grnStatusChip, overdueText, poStatusChip } from './purchase.util';

const PAGE_SIZE = 20;

/**
 * Receive stock (story 79-7, mockup 08): "Purchase orders (<n> open)" lists the sent and partly received purchase
 * orders of this unit, each with "Receive"; "Direct receipt" starts a receipt with no purchase order; "Recent
 * receipts" lists the receipts newest first, and a draft opens to continue. The OCR, e-invoice and CSV sources of the
 * mockup are not built (DEC-072): the invoice is typed. Any other working store sees "Purchase orders are raised at
 * the Central Store." (receiving is a Central Store job).
 */
@Component({
  selector: 'app-receiving',
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
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    table {
      min-width: 720px;
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
    .sub {
      font-size: 0.78rem;
      color: var(--ink-3);
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
      <app-empty data-testid="grn-not-central">Receiving is done at the Central Store. Choose the Central Store as your working store.</app-empty>
    } @else {
      <section class="panel surf" aria-labelledby="grn-h" data-testid="receiving">
        <div class="head">
          <div>
            <h2 id="grn-h">Receive stock (GRN)</h2>
            <p class="sub">Purchase invoice → GRN · Central Store</p>
          </div>
          <div class="actions">
            <button
              mat-stroked-button
              type="button"
              data-testid="toggle-open-pos"
              [attr.aria-expanded]="showPos()"
              (click)="togglePos()"
            >
              Purchase orders ({{ openCount() }} open)
            </button>
            <button mat-flat-button type="button" data-testid="direct-receipt" (click)="startDirect()">
              Direct receipt
            </button>
          </div>
        </div>

        @if (notice(); as text) {
          <div class="notice good" role="status" data-testid="grn-note">
            <app-icon name="check" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }

        @if (showPos()) {
          @if (posError(); as text) {
            <div class="notice err" role="alert" data-testid="open-pos-error">
              <app-icon name="alert" [size]="16" />
              <span>{{ text }}</span>
              <button mat-stroked-button type="button" (click)="loadPos()">Try again</button>
            </div>
          } @else if (pos(); as p) {
            @if (p.items.length === 0) {
              <app-empty data-testid="open-pos-empty">No sent purchase orders are waiting for goods.</app-empty>
            } @else {
              <div class="tbl-wrap">
                <table data-testid="open-pos">
                  <thead>
                    <tr>
                      <th scope="col">PO</th>
                      <th scope="col">Vendor</th>
                      <th scope="col" class="num">Value</th>
                      <th scope="col">Status</th>
                      <th scope="col"><span class="sr-only">Receive</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (row of p.items; track row.id) {
                      <tr>
                        <td>{{ row.number }}</td>
                        <td>{{ row.vendorName }}</td>
                        <td class="num">{{ money(row.totalPaise) }}</td>
                        <td>
                          <span class="pill" [class]="poChip(row.status).css">{{ poChip(row.status).text }}</span>
                          @if (overdue(row.overdueDays); as text) {
                            <span class="pill p-crit">{{ text }}</span>
                          }
                        </td>
                        <td>
                          <button
                            mat-stroked-button
                            type="button"
                            [attr.data-testid]="'receive-' + row.number"
                            (click)="startFromPo(row.id, row.number, row.vendorName)"
                          >
                            Receive
                          </button>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          }
        }
      </section>

      <section class="panel surf" aria-labelledby="rg-h" data-testid="recent-grns">
        <h2 id="rg-h">Recent receipts</h2>
        @if (loading()) {
          <p class="loading" role="status">Loading receipts</p>
        } @else if (loadError(); as text) {
          <div class="notice err" role="alert" data-testid="grn-list-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
            <button mat-stroked-button type="button" (click)="load(0)">Try again</button>
          </div>
        } @else if (page(); as d) {
          @if (d.items.length === 0) {
            <app-empty data-testid="grn-empty">No receipts yet. Choose Purchase orders or Direct receipt.</app-empty>
          } @else {
            <div class="tbl-wrap">
              <table data-testid="grn-table">
                <thead>
                  <tr>
                    <th scope="col">GRN</th>
                    <th scope="col">Vendor</th>
                    <th scope="col">Invoice</th>
                    <th scope="col" class="num">Invoice total</th>
                    <th scope="col">Status</th>
                    <th scope="col">Created</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of d.items; track row.id) {
                    <tr>
                      <td>
                        <button mat-button type="button" [attr.data-testid]="'grn-open-' + row.id" (click)="open(row.id)">
                          {{ row.number ?? 'Draft' }}
                        </button>
                      </td>
                      <td>{{ row.vendorName }}</td>
                      <td>{{ row.invoiceNo }}</td>
                      <td class="num">{{ money(row.invoiceTotalPaise) }}</td>
                      <td>
                        <span class="pill" [class]="grnChip(row.status).css">{{ grnChip(row.status).text }}</span>
                      </td>
                      <td>{{ when(row.createdAt) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            @if (d.total > PAGE_SIZE) {
              <div class="pager">
                <span role="status">Page {{ d.page + 1 }} of {{ pages(d.total) }}</span>
                <button mat-stroked-button type="button" [disabled]="d.page === 0" (click)="load(d.page - 1)">Previous</button>
                <button mat-stroked-button type="button" [disabled]="(d.page + 1) * PAGE_SIZE >= d.total" (click)="load(d.page + 1)">Next</button>
              </div>
            }
          }
        }
      </section>
    }
  `,
})
export class ReceivingSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  private readonly context = inject(PharmacyContextStore);

  /** "Open in approvals" from a receipt that waits for a rate-rise approval. */
  readonly approvalsRequested = output<void>();

  protected readonly PAGE_SIZE = PAGE_SIZE;
  protected readonly isCentral = this.context.isCentralStore;
  protected readonly page = signal<GoodsReceiptPage | null>(null);
  protected readonly pos = signal<PurchaseOrderPage | null>(null);
  protected readonly openCount = computed(() => this.pos()?.total ?? 0);
  protected readonly showPos = signal(false);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly posError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly money = rupees;
  protected readonly poChip = (status: Parameters<typeof poStatusChip>[0]) => poStatusChip(status);
  protected readonly grnChip = grnStatusChip;
  protected readonly overdue = overdueText;
  protected readonly when = (instant: string) => formatDisplayDateTimeOfInstant(instant);

  ngOnInit(): void {
    if (!this.isCentral()) {
      return;
    }
    this.load(0);
    this.loadPos();
  }

  protected pages(total: number): number {
    return Math.max(1, Math.ceil(total / PAGE_SIZE));
  }

  protected togglePos(): void {
    this.showPos.set(!this.showPos());
  }

  protected load(page: number): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.api.goodsReceipts(storeId, undefined, page, PAGE_SIZE).subscribe({
      next: (answer) => {
        this.page.set(answer);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }

  /** The open purchase orders: sent or partly received ("Purchase orders (<n> open)"). */
  protected loadPos(): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    this.posError.set(null);
    this.api.purchaseOrders(storeId, { status: ['SENT', 'PARTLY_RECEIVED'], size: 50 }).subscribe({
      next: (answer) => this.pos.set(answer),
      error: (error: unknown) => this.posError.set(loadErrorMessage(error)),
    });
  }

  protected startDirect(): void {
    this.openSheet({});
  }

  protected startFromPo(id: string, number: string | null, vendorName: string): void {
    this.openSheet({ po: { id, number, vendorName } });
  }

  protected open(grnId: string): void {
    this.openSheet({ grnId });
  }

  private openSheet(part: Pick<GrnDialogData, 'po' | 'grnId'>): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    this.dialog
      .open(GrnDialog, { ...GRN_DIALOG_CONFIG, data: { storeId, ...part } })
      .afterClosed()
      .subscribe((result: GrnDialogResult | undefined) => {
        if (!result) {
          return;
        }
        if (result.changed) {
          this.notice.set('Receipt saved.');
          this.load(0);
          this.loadPos();
        }
        if (result.next === 'approvals') {
          this.approvalsRequested.emit();
        }
      });
  }
}
