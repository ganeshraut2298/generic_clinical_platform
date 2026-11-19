import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { rupees } from '../opening-stock/opening-stock.util';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage } from '../pharmacy-display.util';
import { AdjustmentStatus, StockAdjustment, StockAdjustmentPage } from '../pharmacy-inventory.model';
import { PharmacyApi } from '../pharmacy.api';
import {
  actingAsText,
  adjustmentStatusChip,
  differenceText,
  expiryMonthYear,
  historyEventText,
} from './inventory.util';
import {
  STOCK_ADJUSTMENT_CONFIG,
  StockAdjustmentData,
  StockAdjustmentDialog,
} from './stock-adjustment.dialog';

type StatusFilter = AdjustmentStatus | 'ALL';

const PAGE_SIZE = 20;

/**
 * Stock adjustments of the working store (story 79-5): the list, newest first, with the status of each (Posted, Waiting
 * for approval, Rejected) and, for each, the History card: raised, decided and posted, each with user, store, time,
 * book and physical quantity and reason (BR-26). "Stock adjustment" raises a new one. Shown only to a person with the
 * adjust capability for the working store.
 */
@Component({
  selector: 'app-stock-adjustments',
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
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .chips button {
      min-height: 44px;
      padding: 8px 12px;
      border: 1px solid var(--line);
      border-radius: 999px;
      background: var(--glass-hi);
      font-weight: 600;
      font-size: 0.86rem;
    }
    .chips button[aria-pressed='true'] {
      border: 2px solid var(--pri);
      padding: 7px 11px;
      background: var(--pri-soft);
      color: var(--pri-text);
    }
    table {
      min-width: 860px;
    }
    .sub {
      font-size: 0.78rem;
      color: var(--ink-3);
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
    ol.steps {
      margin: 4px 0;
      padding-left: 18px;
    }
    .pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="sa-h" data-testid="stock-adjustments">
      <div class="head">
        <h2 id="sa-h">Stock adjustments</h2>
        @if (canAdjust()) {
          <button mat-flat-button type="button" data-testid="new-adjustment" (click)="openNew()">
            Stock adjustment
          </button>
        }
      </div>
      <p class="note">
        A correction is a new line in the stock ledger, never an edit. Adjustments above the limit of your rules, or on
        Schedule X items, go to the owner for approval.
      </p>

      @if (notice(); as text) {
        <div class="notice good" role="status" data-testid="adjust-note">
          <app-icon name="check" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }

      <div class="chips" role="group" aria-label="Status">
        @for (f of filters; track f.key) {
          <button
            type="button"
            [attr.aria-pressed]="f.key === filter()"
            [attr.data-testid]="'adj-filter-' + f.key"
            (click)="choose(f.key)"
          >
            {{ f.label }}
          </button>
        }
      </div>

      @if (loading()) {
        <p class="loading" role="status">Loading adjustments</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="adjust-list-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="load(0)">Try again</button>
        </div>
      } @else if (data(); as d) {
        @if (d.items.length === 0) {
          <app-empty data-testid="adjust-empty">No stock adjustments yet.</app-empty>
        } @else {
          <div class="tbl-wrap" data-testid="adjust-table">
            <table>
              <thead>
                <tr>
                  <th scope="col">Number</th>
                  <th scope="col">Batch</th>
                  <th scope="col" class="num">Book → physical</th>
                  <th scope="col" class="num">Difference</th>
                  <th scope="col">Reason</th>
                  <th scope="col">Raised by</th>
                  <th scope="col">Status</th>
                  <th scope="col"><span class="sr-only">History</span></th>
                </tr>
              </thead>
              <tbody>
                @for (a of d.items; track a.id) {
                  <tr [attr.data-testid]="'adj-' + a.number">
                    <td>{{ a.number }}</td>
                    <td>
                      {{ a.product.name }} · {{ a.batch.batchNo }}
                      <div class="sub">expires {{ month(a.batch.expiryDate) }}</div>
                    </td>
                    <td class="num">{{ a.bookQty }} → {{ a.physicalQty }}</td>
                    <td class="num">{{ diff(a.difference) }} · {{ money(a.valuePaise) }}</td>
                    <td>{{ a.reason.label }}</td>
                    <td>
                      {{ a.raisedBy.name }}
                      <div class="sub">{{ when(a.raisedAt) }}</div>
                    </td>
                    <td>
                      <span class="pill" [class]="chip(a.status).css">{{ chip(a.status).text }}</span>
                      @if (a.status === 'REJECTED' && a.approval?.rejectReason; as why) {
                        <div class="sub" data-testid="reject-reason">{{ why.label }}</div>
                      }
                    </td>
                    <td>
                      <button
                        mat-stroked-button
                        type="button"
                        [attr.aria-expanded]="open() === a.id"
                        [attr.data-testid]="'history-' + a.number"
                        (click)="toggle(a.id)"
                      >
                        History
                      </button>
                    </td>
                  </tr>
                  @if (open() === a.id) {
                    <tr [attr.data-testid]="'history-rows-' + a.number">
                      <td colspan="8">
                        <ol class="steps">
                          @for (h of a.history; track h.at + h.event) {
                            <li>
                              {{ eventText(h.event) }} · {{ h.by?.name ?? 'System' }}{{ h.actingAs ? ' (' + acting(h.actingAs) + ')' : '' }}
                              {{ h.store ? ' · ' + h.store.name : '' }} · {{ when(h.at) }}
                              @if (h.qtyBefore !== null && h.qtyAfter !== null) {
                                · {{ h.qtyBefore }} → {{ h.qtyAfter }}
                              }
                              {{ h.reason ? ' · ' + h.reason.label : '' }}
                            </li>
                          }
                        </ol>
                      </td>
                    </tr>
                  }
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
  `,
})
export class StockAdjustmentsSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  private readonly context = inject(PharmacyContextStore);

  protected readonly PAGE_SIZE = PAGE_SIZE;
  protected readonly filters: { key: StatusFilter; label: string }[] = [
    { key: 'ALL', label: 'All' },
    { key: 'WAITING_APPROVAL', label: 'Waiting for approval' },
    { key: 'POSTED', label: 'Posted' },
    { key: 'REJECTED', label: 'Rejected' },
  ];

  protected readonly data = signal<StockAdjustmentPage | null>(null);
  protected readonly filter = signal<StatusFilter>('ALL');
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly open = signal<string | null>(null);

  protected readonly canAdjust = this.context.canAdjustStock;

  protected readonly money = rupees;
  protected readonly month = expiryMonthYear;
  protected readonly diff = differenceText;
  protected readonly chip = adjustmentStatusChip;
  protected readonly eventText = historyEventText;
  protected readonly acting = actingAsText;
  protected readonly when = (instant: string) => formatDisplayDateTimeOfInstant(instant);

  ngOnInit(): void {
    this.load(0);
  }

  protected pages(total: number): number {
    return Math.max(1, Math.ceil(total / PAGE_SIZE));
  }

  protected choose(filter: StatusFilter): void {
    this.filter.set(filter);
    this.load(0);
  }

  protected toggle(id: string): void {
    this.open.set(this.open() === id ? null : id);
  }

  protected load(page: number): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      this.loading.set(false);
      this.data.set({ items: [], total: 0, page: 0, size: PAGE_SIZE });
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    const status = this.filter();
    this.api
      .stockAdjustments(storeId, { status: status === 'ALL' ? undefined : status, page, size: PAGE_SIZE })
      .subscribe({
        next: (answer) => {
          this.data.set(answer);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.data.set(null);
          this.loading.set(false);
          this.loadError.set(loadErrorMessage(error));
        },
      });
  }

  protected openNew(): void {
    const ctx = this.context.context();
    const storeId = this.context.workingStoreId();
    if (!ctx || !storeId) {
      return;
    }
    const data: StockAdjustmentData = {
      storeId,
      storeName: this.context.workingStore()?.name ?? '',
      preset: null,
      callerMembershipId: ctx.caller.membershipId,
      callerRole: ctx.caller.role,
    };
    this.dialog
      .open(StockAdjustmentDialog, { ...STOCK_ADJUSTMENT_CONFIG, data })
      .afterClosed()
      .subscribe((done: StockAdjustment | null | undefined) => {
        if (done) {
          this.notice.set(
            done.status === 'POSTED'
              ? `${done.number} posted.`
              : `${done.number} is waiting for approval. It shows in the Approvals inbox.`,
          );
          this.load(0);
        }
      });
  }
}
