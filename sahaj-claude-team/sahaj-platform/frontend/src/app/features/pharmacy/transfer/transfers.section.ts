import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { ageText } from '../indent/indent.util';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage } from '../pharmacy-display.util';
import { TransferListRow, TransferPage } from '../pharmacy-stockuse.model';
import { PharmacyApi } from '../pharmacy.api';
import { transferChip } from '../stockuse/stockuse.util';
import { TRANSFER_DETAIL_CONFIG, TransferDetailData, TransferDetailDialog } from './transfer-detail.dialog';
import { TRANSFER_FORM_CONFIG, TransferFormData, TransferFormDialog } from './transfer-form.dialog';

const PAGE_SIZE = 20;

/**
 * Stock transfer (story 79-15, mockup 12): "Open transfers" with No., From → to, Items, Status and Age, and "+ New
 * transfer". Transfers not received in 24 hours show "Not received · <n> h" on both stores' lists (the hours come from
 * the server). A row opens the transfer, where the sending store dispatches and the receiving store receives like an
 * indent. The tab shows to a person who can read the working store's stock; "+ New transfer" needs the raise capability.
 */
@Component({
  selector: 'app-transfers',
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
    table {
      min-width: 700px;
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
    .pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="tr-h" data-testid="transfers">
      <div class="head">
        <h2 id="tr-h">Stock transfer</h2>
        @if (canRaise()) {
          <button mat-flat-button type="button" data-testid="new-transfer" (click)="openNew()">+ New transfer</button>
        }
      </div>
      <p class="note">Store to store and branch to branch. Transfers not received in 24 hours are escalated to both store HODs.</p>
      @if (notice(); as text) {
        <div class="notice good" role="status" data-testid="transfer-note">
          <app-icon name="check" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
      <h3>Open transfers</h3>
      @if (loading()) {
        <p class="loading" role="status">Loading transfers</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="transfers-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="load(0)">Try again</button>
        </div>
      } @else if (data(); as d) {
        @if (d.items.length === 0) {
          <app-empty data-testid="transfers-empty">No open transfers.</app-empty>
        } @else {
          <div class="tbl-wrap">
            <table data-testid="transfers-table">
              <thead>
                <tr>
                  <th scope="col">No.</th>
                  <th scope="col">From → to</th>
                  <th scope="col" class="num">Items</th>
                  <th scope="col">Status</th>
                  <th scope="col">Age</th>
                </tr>
              </thead>
              <tbody>
                @for (row of d.items; track row.id) {
                  <tr [attr.data-testid]="'transfer-row-' + row.number">
                    <td>
                      <button mat-button type="button" [attr.data-testid]="'open-transfer-' + row.number" (click)="open(row)">
                        {{ row.number }}
                      </button>
                    </td>
                    <td>{{ row.fromStore.name }} → {{ row.toStore.name }}</td>
                    <td class="num">{{ row.itemCount }}</td>
                    <td>
                      <span class="pill" [class]="chip(row).css">{{ chip(row).text }}</span>
                    </td>
                    <td>{{ age(row.ageMinutes) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (d.total > pageSize) {
            <div class="pager" data-testid="transfers-pager">
              <span role="status">Page {{ d.page + 1 }} of {{ pages(d.total) }}</span>
              <button mat-stroked-button type="button" [disabled]="d.page === 0" (click)="load(d.page - 1)">Previous</button>
              <button mat-stroked-button type="button" [disabled]="(d.page + 1) * pageSize >= d.total" (click)="load(d.page + 1)">Next</button>
            </div>
          }
        }
      }
    </section>
  `,
})
export class TransfersSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  private readonly context = inject(PharmacyContextStore);

  protected readonly pageSize = PAGE_SIZE;
  protected readonly data = signal<TransferPage | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly canRaise = this.context.canRaiseIndent;

  protected readonly chip = (row: TransferListRow) => transferChip(row.status, row.notReceivedHours);
  protected readonly age = ageText;
  protected readonly pages = (total: number) => Math.max(1, Math.ceil(total / PAGE_SIZE));

  ngOnInit(): void {
    this.load(0);
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
    this.api.transfers(storeId, { open: true, page, size: PAGE_SIZE }).subscribe({
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
    const ctx = this.context.workingStore();
    if (!ctx) {
      return;
    }
    const stores = new Map<string, { id: string; name: string; central: boolean }>();
    stores.set(ctx.storeId, { id: ctx.storeId, name: ctx.name, central: ctx.type === 'CENTRAL' });
    const open = (): void => {
      const data: TransferFormData = { storeId: ctx.storeId, storeName: ctx.name, stores: [...stores.values()] };
      this.dialog
        .open(TransferFormDialog, { ...TRANSFER_FORM_CONFIG, data })
        .afterClosed()
        .subscribe((transfer: { number: string; status: string } | null | undefined) => {
          if (transfer) {
            this.notice.set(
              transfer.status === 'WAITING_APPROVAL'
                ? `${transfer.number} is waiting for approval. Nothing moves until it is dispatched.`
                : `${transfer.number} is approved. The sending store can dispatch it.`,
            );
            this.load(0);
          }
        });
    };
    // The other end comes from the unit's transfer stores (contract 0.3.6), for every role.
    this.api.transferStores(ctx.storeId).subscribe({
      next: (list) => {
        for (const s of list.items) {
          stores.set(s.storeId, { id: s.storeId, name: s.name, central: s.type === 'CENTRAL' });
        }
        open();
      },
      error: () => open(),
    });
  }

  protected open(row: TransferListRow): void {
    const storeId = this.context.workingStoreId();
    const ctx = this.context.context();
    if (!storeId || !ctx) {
      return;
    }
    const data: TransferDetailData = {
      storeId,
      transferId: row.id,
      canDispatch: this.context.canDispatchTransfer(),
      canReceive: this.context.canReceive(),
      isPharmacist: this.context.canReceiveScheduleX(),
    };
    this.dialog
      .open(TransferDetailDialog, { ...TRANSFER_DETAIL_CONFIG, data })
      .afterClosed()
      .subscribe((changed: boolean | undefined) => {
        if (changed) {
          this.load(0);
        }
      });
  }
}
