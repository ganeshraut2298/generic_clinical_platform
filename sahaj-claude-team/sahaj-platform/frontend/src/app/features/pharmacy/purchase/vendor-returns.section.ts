import { ChangeDetectionStrategy, Component, OnInit, inject, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { rupees } from '../opening-stock/opening-stock.util';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage } from '../pharmacy-display.util';
import { DebitNote, DebitNotePage, DebitNoteStatus } from '../pharmacy-buying.model';
import { PharmacyApi } from '../pharmacy.api';
import { DEBIT_NOTE_CONFIG, DebitNoteDialog, DebitNoteResult } from './debit-note.dialog';
import { VENDOR_RETURN_CONFIG, VendorReturnDialog } from './vendor-return.dialog';
import { dnStatusChip } from './purchase.util';

type StatusFilter = DebitNoteStatus | 'ALL';

const PAGE_SIZE = 20;

/**
 * Vendor return & debit note (story 79-8, mockup 09): the list of debit notes of the Central Store, newest first, with
 * a status filter, the button "+ New return" and, for each row, the tracking card. A draft prepared by a receipt
 * (79-7) is listed as Draft for the Central Store user to review and create. Not built from the mockup: the
 * "Non-moving" source and "Signature + photo" (OQ-79-25).
 */
@Component({
  selector: 'app-vendor-returns',
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
      <app-empty data-testid="vr-not-central">Vendor returns are made at the Central Store. Choose the Central Store as your working store.</app-empty>
    } @else {
      <section class="panel surf" aria-labelledby="vrs-h" data-testid="vendor-returns">
        <div class="head">
          <div>
            <h2 id="vrs-h">Vendor return & debit note</h2>
            <p class="sub">Central Store</p>
          </div>
          <button mat-flat-button type="button" data-testid="new-return" (click)="openNew()">+ New return</button>
        </div>

        @if (notice(); as text) {
          <div class="notice good" role="status" data-testid="vr-note">
            <app-icon name="check" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }

        <div class="chips" role="group" aria-label="Status">
          @for (f of filters; track f.key) {
            <button
              type="button"
              [attr.aria-pressed]="f.key === filter()"
              [attr.data-testid]="'dn-filter-' + f.key"
              (click)="choose(f.key)"
            >
              {{ f.label }}
            </button>
          }
        </div>

        @if (loading()) {
          <p class="loading" role="status">Loading debit notes</p>
        } @else if (loadError(); as text) {
          <div class="notice err" role="alert" data-testid="dn-list-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
            <button mat-stroked-button type="button" (click)="load(0)">Try again</button>
          </div>
        } @else if (page(); as d) {
          @if (d.items.length === 0) {
            <app-empty data-testid="dn-empty">No debit notes yet. Choose + New return.</app-empty>
          } @else {
            <div class="tbl-wrap">
              <table data-testid="dn-table">
                <thead>
                  <tr>
                    <th scope="col">Debit note</th>
                    <th scope="col">Vendor</th>
                    <th scope="col" class="num">Credit</th>
                    <th scope="col">Status</th>
                    <th scope="col">Created</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of d.items; track row.id) {
                    <tr>
                      <td>
                        <button mat-button type="button" [attr.data-testid]="'dn-open-' + row.id" (click)="openDn(row.id)">
                          {{ row.number ?? 'Draft' }}
                        </button>
                      </td>
                      <td>{{ row.vendorName }}</td>
                      <td class="num">{{ money(row.creditTotalPaise) }}</td>
                      <td>
                        <span class="pill" [class]="chip(row.status).css">{{ chip(row.status).text }}</span>
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
export class VendorReturnsSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  private readonly context = inject(PharmacyContextStore);

  /** "Open in approvals" from a debit note that waits for approval. */
  readonly approvalsRequested = output<void>();

  protected readonly PAGE_SIZE = PAGE_SIZE;
  protected readonly isCentral = this.context.isCentralStore;
  protected readonly filters: { key: StatusFilter; label: string }[] = [
    { key: 'ALL', label: 'All' },
    { key: 'DRAFT', label: 'Draft' },
    { key: 'WAITING_APPROVAL', label: 'Waiting for approval' },
    { key: 'CREATED', label: 'Created' },
    { key: 'HANDED_OVER', label: 'Handed to vendor rep' },
    { key: 'CREDIT_NOTE_RECEIVED', label: 'Credit note received' },
    { key: 'CANCELLED', label: 'Cancelled' },
  ];

  protected readonly page = signal<DebitNotePage | null>(null);
  protected readonly filter = signal<StatusFilter>('ALL');
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly money = rupees;
  protected readonly chip = dnStatusChip;
  protected readonly when = (instant: string) => formatDisplayDateTimeOfInstant(instant);

  ngOnInit(): void {
    if (this.isCentral()) {
      this.load(0);
    }
  }

  protected pages(total: number): number {
    return Math.max(1, Math.ceil(total / PAGE_SIZE));
  }

  protected choose(filter: StatusFilter): void {
    this.filter.set(filter);
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
    const status = this.filter();
    this.api.debitNotes(storeId, { status: status === 'ALL' ? undefined : status, page, size: PAGE_SIZE }).subscribe({
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

  protected openNew(): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    this.dialog
      .open(VendorReturnDialog, { ...VENDOR_RETURN_CONFIG, data: { storeId } })
      .afterClosed()
      .subscribe((created: DebitNote | null | undefined) => {
        if (created) {
          this.notice.set(
            created.number ? `Debit note ${created.number} created.` : 'Debit note created.',
          );
          this.load(0);
          this.openDn(created.id);
        }
      });
  }

  protected openDn(dnId: string): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    this.dialog
      .open(DebitNoteDialog, { ...DEBIT_NOTE_CONFIG, data: { storeId, dnId } })
      .afterClosed()
      .subscribe((result: DebitNoteResult | undefined) => {
        if (!result) {
          return;
        }
        if (result.changed) {
          this.load(0);
        }
        if (result.next === 'approvals') {
          this.approvalsRequested.emit();
        }
      });
  }
}
