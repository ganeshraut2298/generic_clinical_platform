import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage } from '../pharmacy-display.util';
import { ReturnItem, ReturnSide, Shortage } from '../pharmacy-indent.model';
import { PharmacyApi } from '../pharmacy.api';
import {
  batchWithExpiry,
  isOpenShortage,
  returnStatusChip,
  returnTypeText,
  shortageStatusChip,
  whenText,
} from './indent.util';
import { RETURN_ITEM_CONFIG, ReturnItemData, ReturnItemDialog } from './return-item.dialog';
import { SHORTAGE_CONFIG, ShortageData, ShortageSettleDialog } from './shortage-settle.dialog';

const PAGE_SIZE = 20;

/**
 * "Waiting for action" (story 79-13, mockup 11 and 10 step 5). At the Central Store (accept returns capability): the
 * returned and rejected items to accept or dispute (No., Type, Item · batch, Qty, Reason, Raised by, Status) and the
 * shortages to settle ("Short · under investigation"). At a receiving store: the items it sent back, with the dispute
 * reason and a reply. A shortage is never listed as a return. Items are acted on in the sheets; the server checks again.
 */
@Component({
  selector: 'app-waiting-for-action',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, EmptyStateComponent],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    table {
      min-width: 760px;
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
    @if (!canUse()) {
      <app-empty data-testid="waiting-no-access">There is nothing waiting for action at this store.</app-empty>
    } @else {
      <section class="panel surf" aria-labelledby="waiting-h" data-testid="waiting">
        <h2 id="waiting-h">Waiting for action</h2>
        <p class="sub">{{ storeName() }}</p>

        @if (notice(); as text) {
          <div class="notice good" role="status" data-testid="waiting-note">
            <app-icon name="check" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }

        <h3>{{ accepting() ? 'Returned and rejected items' : 'Items you sent back' }}</h3>
        @if (loading()) {
          <p class="loading" role="status">Loading</p>
        } @else if (loadError(); as text) {
          <div class="notice err" role="alert" data-testid="waiting-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
            <button mat-stroked-button type="button" (click)="loadAll()">Try again</button>
          </div>
        } @else if (items().length === 0) {
          <app-empty data-testid="waiting-empty">
            Nothing was rejected. All items were accepted, so nothing goes back to Central Store.
          </app-empty>
        } @else {
          <div class="tbl-wrap">
            <table data-testid="waiting-table">
              <thead>
                <tr>
                  <th scope="col">No.</th>
                  <th scope="col">Type</th>
                  <th scope="col">Item · batch</th>
                  <th scope="col">Qty</th>
                  <th scope="col">Reason</th>
                  <th scope="col">Raised by</th>
                  <th scope="col">Status</th>
                  <th scope="col"><span class="sr-only">Action</span></th>
                </tr>
              </thead>
              <tbody>
                @for (row of items(); track row.id) {
                  <tr [attr.data-testid]="'waiting-row-' + row.docNo">
                    <td>{{ row.docNo }}</td>
                    <td>{{ type(row) }}</td>
                    <td>
                      {{ row.product.name }}
                      <div class="sub">{{ batch(row) }}</div>
                    </td>
                    <td>{{ row.qty }}</td>
                    <td>{{ row.reason.label }}</td>
                    <td>
                      {{ row.raisedBy.name }}
                      <div class="sub">{{ when(row.raisedAt) }}</div>
                    </td>
                    <td>
                      <span class="pill" [class]="chip(row).css" data-testid="return-row-status">{{ chip(row).text }}</span>
                    </td>
                    <td>
                      <button
                        mat-stroked-button
                        type="button"
                        [attr.data-testid]="'waiting-open-' + row.docNo"
                        (click)="openItem(row)"
                      >
                        {{ accepting() && (row.status === 'WAITING' || row.status === 'DISPUTED') ? 'Review' : 'Open' }}
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (total() > PAGE_SIZE) {
            <div class="pager">
              <span role="status">Page {{ page() + 1 }} of {{ pages() }}</span>
              <button mat-stroked-button type="button" [disabled]="page() === 0" (click)="loadItems(page() - 1)">Previous</button>
              <button
                mat-stroked-button
                type="button"
                [disabled]="(page() + 1) * PAGE_SIZE >= total()"
                (click)="loadItems(page() + 1)"
              >
                Next
              </button>
            </div>
          }
        }

        @if (accepting()) {
          <h3>Shortages</h3>
          @if (shortageError(); as text) {
            <div class="notice err" role="alert" data-testid="shortage-list-error">
              <app-icon name="alert" [size]="16" />
              <span>{{ text }}</span>
            </div>
          } @else if (shortages().length === 0) {
            <app-empty data-testid="shortages-empty">No shortage is waiting.</app-empty>
          } @else {
            <div class="tbl-wrap">
              <table data-testid="shortages-table">
                <thead>
                  <tr>
                    <th scope="col">Dispatch</th>
                    <th scope="col">Receiving store</th>
                    <th scope="col">Item · batch</th>
                    <th scope="col">Qty</th>
                    <th scope="col">Status</th>
                    <th scope="col"><span class="sr-only">Action</span></th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of shortages(); track row.id) {
                    <tr [attr.data-testid]="'shortage-row-' + row.dispatchReference">
                      <td>{{ row.dispatchReference }}</td>
                      <td>{{ row.receivingStore.name }}</td>
                      <td>
                        {{ row.product.name }}
                        <div class="sub">{{ shortageBatch(row) }}</div>
                      </td>
                      <td>{{ row.qty }}</td>
                      <td>
                        <span class="pill" [class]="shortageChip(row).css" data-testid="shortage-row-status">{{ shortageChip(row).text }}</span>
                      </td>
                      <td>
                        @if (open(row)) {
                          <button
                            mat-stroked-button
                            type="button"
                            [attr.data-testid]="'shortage-open-' + row.dispatchReference"
                            (click)="openShortage(row)"
                          >
                            Settle shortage of {{ row.qty }}
                          </button>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        }
      </section>
    }
  `,
})
export class WaitingForActionSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  protected readonly store = inject(PharmacyContextStore);

  protected readonly PAGE_SIZE = PAGE_SIZE;
  protected readonly items = signal<ReturnItem[]>([]);
  protected readonly shortages = signal<Shortage[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly shortageError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly accepting = this.store.canAcceptReturn;
  protected readonly canUse = computed(() => this.accepting() || this.store.canReceive());
  protected readonly storeName = computed(() => this.store.workingStore()?.name ?? '');
  protected readonly pages = computed(() => Math.max(1, Math.ceil(this.total() / PAGE_SIZE)));

  protected readonly type = (row: ReturnItem) => returnTypeText(row.type);
  protected readonly chip = (row: ReturnItem) => returnStatusChip(row.status);
  protected readonly batch = (row: ReturnItem) => batchWithExpiry(row.batch.batchNo, row.batch.expiryDate);
  protected readonly shortageBatch = (row: Shortage) => batchWithExpiry(row.batch.batchNo, row.batch.expiryDate);
  protected readonly shortageChip = (row: Shortage) => shortageStatusChip(row.status);
  protected readonly open = (row: Shortage) => isOpenShortage(row.status);
  protected readonly when = whenText;

  ngOnInit(): void {
    if (this.canUse()) {
      this.loadAll();
    } else {
      this.loading.set(false);
    }
  }

  private side(): ReturnSide {
    return this.accepting() ? 'ACCEPTING' : 'RETURNING';
  }

  protected loadAll(): void {
    this.loadItems(0);
    this.loadShortages();
  }

  protected loadItems(page: number): void {
    const storeId = this.store.workingStoreId();
    if (!storeId) {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.api.waitingForAction(storeId, { side: this.side(), page, size: PAGE_SIZE }).subscribe({
      next: (answer) => {
        this.items.set(answer.items);
        this.total.set(answer.total);
        this.page.set(answer.page);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }

  private loadShortages(): void {
    const storeId = this.store.workingStoreId();
    if (!storeId || !this.accepting()) {
      return;
    }
    this.shortageError.set(null);
    this.api.shortages(storeId, { size: PAGE_SIZE }).subscribe({
      next: (answer) => this.shortages.set(answer.items),
      error: (error: unknown) => this.shortageError.set(loadErrorMessage(error)),
    });
  }

  protected openItem(row: ReturnItem): void {
    const storeId = this.store.workingStoreId();
    if (!storeId) {
      return;
    }
    const data: ReturnItemData = {
      storeId,
      itemId: row.id,
      side: this.side(),
      canSignScheduleX: this.store.canHandleScheduleX(),
    };
    this.dialog
      .open(ReturnItemDialog, { ...RETURN_ITEM_CONFIG, data })
      .afterClosed()
      .subscribe((changed: boolean | undefined) => {
        if (changed) {
          this.notice.set('Saved.');
          this.loadAll();
        }
      });
  }

  protected openShortage(row: Shortage): void {
    const storeId = this.store.workingStoreId();
    if (!storeId) {
      return;
    }
    const data: ShortageData = { storeId, shortage: row, canSignScheduleX: this.store.canHandleScheduleX() };
    this.dialog
      .open(ShortageSettleDialog, { ...SHORTAGE_CONFIG, data })
      .afterClosed()
      .subscribe((changed: boolean | undefined) => {
        if (changed) {
          this.notice.set('Saved.');
          this.loadAll();
        }
      });
  }
}
