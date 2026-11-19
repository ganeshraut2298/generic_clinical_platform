import { ChangeDetectionStrategy, Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage } from '../pharmacy-display.util';
import { DispatchListRow, Indent, IndentListRow, IndentPage, IndentStatus } from '../pharmacy-indent.model';
import { PharmacyApi } from '../pharmacy.api';
import { DISPATCH_CONFIG, DispatchData, IndentDispatchDialog } from './indent-dispatch.dialog';
import { INDENT_DETAIL_CONFIG, IndentDetailData, IndentDetailDialog } from './indent-detail.dialog';
import { INDENT_FORM_CONFIG, IndentFormData, IndentFormDialog } from './indent-form.dialog';
import { RECEIPT_CONFIG, ReceiptData, ReceiptDialog } from './receipt.dialog';
import {
  INDENT_STATUS_FILTER,
  ageText,
  dispatchStatusChip,
  indentRowChip,
  priorityChip,
  whenText,
} from './indent.util';

const PAGE_SIZE = 20;

/**
 * "Ward / store indents" (stories 79-9 to 79-12, mockup 10). At a store that raises indents: the indents of the working
 * store with a status filter and "+ New indent" (a Central Store user and the owner at the Central Store do not see it),
 * and, for a person who may receive, the dispatches in transit to the store ("Receive"). At the Central Store: the
 * indents to dispatch (Approved, or with a back-order open), urgent first, each with the requesting store, priority and
 * age; "Process & dispatch" opens the dispatch. Everything else the server checks again.
 */
@Component({
  selector: 'app-indents',
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
    .filter {
      min-height: 44px;
      padding: 0 10px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
      font: inherit;
    }
    table {
      min-width: 720px;
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
      <app-empty data-testid="indents-no-access">
        You do not have access to indents at this store. Ask your owner.
      </app-empty>
    } @else {
      <section class="panel surf" aria-labelledby="indents-h" data-testid="indents">
        <div class="head">
          <div>
            <h2 id="indents-h">Ward / store indents</h2>
            <p class="sub">{{ storeName() }}</p>
          </div>
          <div class="head">
            @if (!dispatching()) {
              <label class="sub" for="indent-status-filter">Status</label>
              <select
                id="indent-status-filter"
                class="filter"
                data-testid="indent-status-filter"
                [value]="status()"
                (change)="chooseStatus($any($event.target).value)"
              >
                <option value="">All</option>
                @for (f of statuses; track f.value) {
                  <option [value]="f.value">{{ f.label }}</option>
                }
              </select>
            }
            @if (canRaise()) {
              <button mat-flat-button type="button" data-testid="new-indent" (click)="openNew()">+ New indent</button>
            }
          </div>
        </div>

        @if (notice(); as text) {
          <div class="notice good" role="status" data-testid="indent-note">
            <app-icon name="check" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }

        @if (loading()) {
          <p class="loading" role="status">Loading indents</p>
        } @else if (loadError(); as text) {
          <div class="notice err" role="alert" data-testid="indents-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
            <button mat-stroked-button type="button" (click)="load(0)">Try again</button>
          </div>
        } @else if (page(); as d) {
          @if (d.items.length === 0) {
            <app-empty data-testid="indents-empty">
              @if (dispatching()) {
                Nothing is waiting to be dispatched.
              } @else {
                No indents yet.{{ canRaise() ? ' Choose + New indent.' : '' }}
              }
            </app-empty>
          } @else {
            <div class="tbl-wrap">
              <table data-testid="indents-table">
                <thead>
                  <tr>
                    <th scope="col">Indent</th>
                    <th scope="col">{{ dispatching() ? 'From store' : 'To store' }}</th>
                    <th scope="col">Items</th>
                    <th scope="col">Priority</th>
                    <th scope="col">Status</th>
                    <th scope="col">Raised by</th>
                    <th scope="col">{{ dispatching() ? 'Age' : 'Raised' }}</th>
                    <th scope="col"><span class="sr-only">Action</span></th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of d.items; track row.id) {
                    <tr [attr.data-testid]="'indent-row-' + (row.number ?? row.id)">
                      <td>{{ row.number ?? 'Draft' }}</td>
                      <td>{{ dispatching() ? row.fromStore.name : row.toStore.name }}</td>
                      <td>{{ row.itemCount }}</td>
                      <td>
                        <span class="pill" [class]="priority(row).css">{{ priority(row).text }}</span>
                      </td>
                      <td>
                        <span class="pill" [class]="chip(row).css" data-testid="indent-status">{{ chip(row).text }}</span>
                      </td>
                      <td>{{ row.raisedBy.name }}</td>
                      <td>{{ dispatching() ? age(row.ageMinutes) : when(row.raisedAt) }}</td>
                      <td>
                        @if (dispatching()) {
                          <button
                            mat-flat-button
                            type="button"
                            [attr.data-testid]="'indent-process-' + (row.number ?? row.id)"
                            (click)="process(row)"
                          >
                            Process & dispatch
                          </button>
                        } @else {
                          <button
                            mat-stroked-button
                            type="button"
                            [attr.data-testid]="'indent-open-' + (row.number ?? row.id)"
                            (click)="open(row)"
                          >
                            {{ row.status === 'DRAFT' ? 'Edit draft' : 'Open' }}
                          </button>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            @if (d.total > PAGE_SIZE) {
              <div class="pager" data-testid="indents-pager">
                <span role="status">Page {{ d.page + 1 }} of {{ pages(d.total) }}</span>
                <button mat-stroked-button type="button" [disabled]="d.page === 0" (click)="load(d.page - 1)">Previous</button>
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

        @if (canReceive()) {
          <h3>Dispatches in transit</h3>
          @if (incomingError(); as text) {
            <div class="notice err" role="alert" data-testid="incoming-error">
              <app-icon name="alert" [size]="16" />
              <span>{{ text }}</span>
            </div>
          } @else if (incoming().length === 0) {
            <app-empty data-testid="incoming-empty">Nothing is on its way to this store.</app-empty>
          } @else {
            <div class="tbl-wrap">
              <table data-testid="incoming-table">
                <thead>
                  <tr>
                    <th scope="col">Dispatch</th>
                    <th scope="col">From</th>
                    <th scope="col">Items</th>
                    <th scope="col">Status</th>
                    <th scope="col">Sent</th>
                    <th scope="col"><span class="sr-only">Action</span></th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of incoming(); track row.id) {
                    <tr [attr.data-testid]="'dispatch-row-' + row.reference">
                      <td>{{ row.reference }}</td>
                      <td>{{ row.fromStore.name }}</td>
                      <td>{{ row.itemCount }}</td>
                      <td>
                        <span class="pill" [class]="dispatchChip(row).css" data-testid="dispatch-status">{{ dispatchChip(row).text }}</span>
                      </td>
                      <td>{{ when(row.dispatchedAt) }}</td>
                      <td>
                        <button
                          mat-flat-button
                          type="button"
                          [attr.data-testid]="'dispatch-receive-' + row.reference"
                          (click)="receive(row)"
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
      </section>
    }
  `,
})
export class IndentsSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  protected readonly store = inject(PharmacyContextStore);

  /** "Open in approvals": an indent that waits for a decision of someone else. */
  readonly approvalsRequested = output<void>();

  protected readonly PAGE_SIZE = PAGE_SIZE;
  protected readonly statuses = INDENT_STATUS_FILTER;
  protected readonly page = signal<IndentPage | null>(null);
  protected readonly incoming = signal<DispatchListRow[]>([]);
  protected readonly incomingError = signal<string | null>(null);
  protected readonly status = signal<IndentStatus | ''>('');
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly dispatching = this.store.canDispatch;
  protected readonly canRaise = this.store.canRaiseIndent;
  protected readonly canReceive = this.store.canReceive;
  protected readonly storeName = computed(() => this.store.workingStore()?.name ?? '');
  protected readonly canUse = computed(() => this.dispatching() || this.canRaise() || this.canReceive());

  protected readonly priority = (row: IndentListRow) => priorityChip(row.priority);
  protected readonly chip = indentRowChip;
  protected readonly age = ageText;
  protected readonly when = whenText;
  protected readonly dispatchChip = (row: DispatchListRow) => dispatchStatusChip(row.status);

  ngOnInit(): void {
    if (this.canUse()) {
      this.load(0);
      this.loadIncoming();
    } else {
      this.loading.set(false);
    }
  }

  protected pages(total: number): number {
    return Math.max(1, Math.ceil(total / PAGE_SIZE));
  }

  protected chooseStatus(value: string): void {
    this.status.set(value as IndentStatus | '');
    this.load(0);
  }

  protected load(pageIndex: number): void {
    const storeId = this.store.workingStoreId();
    if (!storeId || !(this.dispatching() || this.canRaise())) {
      this.loading.set(false);
      this.page.set(null);
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.api
      .indents(storeId, {
        view: this.dispatching() ? 'TO_DISPATCH' : 'RAISED',
        status: this.dispatching() ? '' : this.status(),
        page: pageIndex,
        size: PAGE_SIZE,
      })
      .subscribe({
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

  private loadIncoming(): void {
    const storeId = this.store.workingStoreId();
    if (!storeId || !this.canReceive()) {
      return;
    }
    this.incomingError.set(null);
    this.api.incomingDispatches(storeId, { status: 'IN_TRANSIT', size: PAGE_SIZE }).subscribe({
      next: (answer) => this.incoming.set(answer.items),
      error: (error: unknown) => this.incomingError.set(loadErrorMessage(error)),
    });
  }

  protected openNew(): void {
    this.openForm(null);
  }

  private openForm(indent: Indent | null): void {
    const storeId = this.store.workingStoreId();
    const ctx = this.store.workingStore();
    const caller = this.store.context()?.caller;
    if (!storeId || !ctx) {
      return;
    }
    const data: IndentFormData = {
      storeId,
      storeName: ctx.name,
      actingAs: `Acting as ${caller?.name ?? ''} · mapped to ${ctx.name} · can raise indents for ${ctx.name}`,
      toStoreName: indent?.toStore.name ?? null,
      indent,
    };
    this.dialog
      .open(IndentFormDialog, { ...INDENT_FORM_CONFIG, data })
      .afterClosed()
      .subscribe((done: Indent | null | undefined) => {
        if (!done) {
          return;
        }
        this.notice.set(this.formNote(done));
        this.load(0);
      });
  }

  private formNote(done: Indent): string {
    if (done.status === 'DISCARDED') {
      return 'Draft discarded.';
    }
    if (done.status === 'DRAFT') {
      return 'Draft saved. It has no number yet.';
    }
    if (done.status === 'WAITING_APPROVAL') {
      return `${done.number} is waiting for HOD approval.`;
    }
    return `${done.number} was sent to the Central Store.`;
  }

  protected open(row: IndentListRow): void {
    const storeId = this.store.workingStoreId();
    if (!storeId) {
      return;
    }
    if (row.status === 'DRAFT') {
      this.api.indent(storeId, row.id).subscribe({
        next: (indent) => this.openForm(indent),
        error: (error: unknown) => this.loadError.set(loadErrorMessage(error)),
      });
      return;
    }
    const data: IndentDetailData = { storeId, indentId: row.id };
    this.dialog
      .open(IndentDetailDialog, { ...INDENT_DETAIL_CONFIG, data })
      .afterClosed()
      .subscribe((changed: boolean | undefined) => {
        if (changed) {
          this.notice.set('Your change was saved.');
          this.load(0);
        }
      });
  }

  protected process(row: IndentListRow): void {
    const storeId = this.store.workingStoreId();
    if (!storeId) {
      return;
    }
    const data: DispatchData = {
      storeId,
      indentId: row.id,
      canSignScheduleX: this.store.canHandleScheduleX(),
    };
    this.dialog
      .open(IndentDispatchDialog, { ...DISPATCH_CONFIG, data })
      .afterClosed()
      .subscribe((done: boolean | undefined) => {
        if (done) {
          this.notice.set('Dispatched. The stock is in transit to the requesting store.');
          this.load(0);
        }
      });
  }

  protected receive(row: DispatchListRow): void {
    const storeId = this.store.workingStoreId();
    if (!storeId) {
      return;
    }
    const data: ReceiptData = {
      storeId,
      dispatchId: row.id,
      isPharmacist: this.store.canReceiveScheduleX(),
    };
    this.dialog
      .open(ReceiptDialog, { ...RECEIPT_CONFIG, data })
      .afterClosed()
      .subscribe((done: boolean | undefined) => {
        if (done) {
          this.notice.set('Receipt confirmed. The accepted units are now in this store.');
          this.load(0);
          this.loadIncoming();
        }
      });
  }
}
