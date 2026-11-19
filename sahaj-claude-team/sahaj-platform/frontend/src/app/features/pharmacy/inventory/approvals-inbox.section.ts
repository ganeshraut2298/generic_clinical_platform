import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { loadErrorMessage } from '../pharmacy-display.util';
import { ApprovalInboxPage, ApprovalInboxRow, ApprovalRequest, InboxFilter } from '../pharmacy-inventory.model';
import { PharmacyApi } from '../pharmacy.api';
import { INDENT_DETAIL_CONFIG, IndentDetailData, IndentDetailDialog } from '../indent/indent-detail.dialog';
import { APPROVAL_REJECT_CONFIG, ApprovalRejectData, ApprovalRejectDialog } from './approval-reject.dialog';
import { APPROVAL_REVIEW_CONFIG, ApprovalReviewData, ApprovalReviewDialog } from './approval-review.dialog';
import { INBOX_ACTION_LABELS, inboxChipText, waitingText } from './inventory.util';

const PAGE_SIZE = 20;

/**
 * Approvals inbox (story 79-2, mockup 14): the header "Approvals inbox" with "Signed in as <role>", the filter chips
 * All, Indents, Purchase and Stock with their counts, and the card "Waiting for your decision" with the columns Type,
 * What, Raised by, Waiting and Rule and the buttons Reject and Review (Recount and Review for an audit variance).
 * The server decides what each person sees, the order (urgent, escalated, longest waiting) and the escalation, which
 * is worked out when the list is read; this screen only draws it. Review opens the document at its approval step;
 * the inbox never approves from the list. The mockup's Sales chip and the direct Approve button are for discounts
 * (SAH-80) and are not built here.
 */
@Component({
  selector: 'app-approvals-inbox',
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
      align-items: baseline;
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
      min-width: 880px;
    }
    .sub {
      font-size: 0.78rem;
      color: var(--ink-3);
    }
    .marks {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
      margin-top: 4px;
    }
    .acts {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }
    .pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="ai-h" data-testid="approvals-inbox">
      <div class="head">
        <h2 id="ai-h">Approvals inbox</h2>
        @if (data(); as d) {
          <span class="sub" data-testid="signed-in-as">Signed in as {{ d.signedInAs }}</span>
        }
      </div>

      @if (data(); as d) {
        <div class="chips" role="group" aria-label="Type" data-testid="inbox-chips">
          @for (chip of chips(d); track chip.key) {
            <button
              type="button"
              [attr.aria-pressed]="chip.key === filter()"
              [attr.data-testid]="'chip-' + chip.key"
              (click)="choose(chip.key)"
            >
              {{ chip.label }} {{ chip.count }}
            </button>
          }
        </div>
      }

      @if (actionNote(); as text) {
        <div class="notice good" role="status" data-testid="inbox-note">
          <app-icon name="check" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }

      @if (loading()) {
        <p class="loading" role="status">Loading approvals</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="inbox-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" data-testid="inbox-retry" (click)="load(page())">
            Try again
          </button>
        </div>
      } @else if (data(); as d) {
        <h3>Waiting for your decision</h3>
        <p class="note">Sorted by urgency. Every decision is saved with your name, time and reason.</p>
        @if (d.items.length === 0) {
          <app-empty data-testid="inbox-empty">Nothing is waiting for your decision.</app-empty>
        } @else {
          <div class="tbl-wrap" data-testid="inbox-table">
            <table>
              <thead>
                <tr>
                  <th scope="col">Type</th>
                  <th scope="col">What</th>
                  <th scope="col">Raised by</th>
                  <th scope="col">Waiting</th>
                  <th scope="col">Rule</th>
                  <th scope="col"><span class="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                @for (row of d.items; track row.requestId) {
                  <tr [attr.data-testid]="'inbox-row-' + row.requestId">
                    <td>
                      <span class="pill p-blue" data-testid="row-type">{{ type(row) }}</span>
                    </td>
                    <td data-testid="row-what">{{ row.what }}</td>
                    <td>
                      {{ row.raisedBy.name }}
                      <div class="sub">{{ row.raisedStore.name }}</div>
                    </td>
                    <td data-testid="row-waiting">{{ waiting(row.waitingMinutes) }}</td>
                    <td>
                      {{ row.ruleText }}
                      <div class="marks">
                        @if (row.escalated) {
                          <span class="pill p-warn" data-testid="row-escalated">Escalated</span>
                        }
                        @if (row.needsAnotherApprover) {
                          <span class="pill p-wait" data-testid="row-needs-other">Needs another approver</span>
                        }
                      </div>
                    </td>
                    <td>
                      <div class="acts">
                        @if (row.actions.includes('REJECT')) {
                          <button
                            mat-stroked-button
                            type="button"
                            [attr.data-testid]="'reject-' + row.requestId"
                            (click)="reject(row)"
                          >
                            {{ labels.REJECT }}
                          </button>
                        }
                        @if (row.actions.includes('RECOUNT')) {
                          <button
                            mat-stroked-button
                            type="button"
                            [attr.data-testid]="'recount-' + row.requestId"
                            (click)="review(row)"
                          >
                            {{ labels.RECOUNT }}
                          </button>
                        }
                        @if (row.actions.includes('REVIEW')) {
                          <button
                            mat-flat-button
                            type="button"
                            [attr.data-testid]="'review-' + row.requestId"
                            (click)="review(row)"
                          >
                            {{ labels.REVIEW }}
                          </button>
                        }
                      </div>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (d.total > PAGE_SIZE) {
            <div class="pager" data-testid="inbox-pager">
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
        <p class="note">
          Approval limits and who approves what are set in Inventory setup, Approval rules. Items waiting beyond their
          limit escalate to the next approver.
        </p>
      }
    </section>
  `,
})
export class ApprovalsInboxSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);

  protected readonly PAGE_SIZE = PAGE_SIZE;
  protected readonly labels = INBOX_ACTION_LABELS;
  protected readonly waiting = waitingText;

  protected readonly data = signal<ApprovalInboxPage | null>(null);
  protected readonly filter = signal<InboxFilter>('ALL');
  protected readonly page = signal(0);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionNote = signal<string | null>(null);

  ngOnInit(): void {
    this.load(0);
  }

  protected type(row: ApprovalInboxRow): string {
    return inboxChipText(row.chip, row.urgent);
  }

  protected pages(total: number): number {
    return Math.max(1, Math.ceil(total / PAGE_SIZE));
  }

  /** The chips Indents, Purchase and Stock with the counts the server gives for the caller. Sales is SAH-80. */
  protected chips(d: ApprovalInboxPage): { key: InboxFilter; label: string; count: number }[] {
    return [
      { key: 'ALL', label: 'All', count: d.counts.all },
      { key: 'INDENTS', label: 'Indents', count: d.counts.indents },
      { key: 'PURCHASE', label: 'Purchase', count: d.counts.purchase },
      { key: 'STOCK', label: 'Stock', count: d.counts.stock },
    ];
  }

  protected choose(filter: InboxFilter): void {
    this.filter.set(filter);
    this.load(0);
  }

  protected load(page: number): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.approvalInbox({ type: this.filter(), page, size: PAGE_SIZE }).subscribe({
      next: (answer) => {
        this.data.set(answer);
        this.page.set(answer.page);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        // Nothing stale is shown as current (story 79-2).
        this.data.set(null);
        this.loading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }

  protected review(row: ApprovalInboxRow): void {
    if (row.subjectType === 'INDENT') {
      this.reviewIndent(row);
      return;
    }
    const data: ApprovalReviewData = { row };
    this.dialog
      .open(ApprovalReviewDialog, { ...APPROVAL_REVIEW_CONFIG, data })
      .afterClosed()
      .subscribe((changed: boolean | undefined) => {
        if (changed) {
          this.actionNote.set('Your decision was saved.');
          this.load(this.page());
        }
      });
  }

  /** An indent opens on its own approval card (story 79-10): approved quantities, remark, Reject and Approve. */
  private reviewIndent(row: ApprovalInboxRow): void {
    const data: IndentDetailData = { storeId: row.subjectStoreId, indentId: row.subjectId };
    this.dialog
      .open(IndentDetailDialog, { ...INDENT_DETAIL_CONFIG, data })
      .afterClosed()
      .subscribe((changed: boolean | undefined) => {
        if (changed) {
          this.actionNote.set('Your decision was saved.');
          this.load(this.page());
        }
      });
  }

  protected reject(row: ApprovalInboxRow): void {
    const data: ApprovalRejectData = { requestId: row.requestId, what: row.what };
    this.dialog
      .open(ApprovalRejectDialog, { ...APPROVAL_REJECT_CONFIG, data })
      .afterClosed()
      .subscribe((done: ApprovalRequest | null | undefined) => {
        if (done) {
          this.actionNote.set('Rejected. The request went back to the person who raised it.');
          this.load(this.page());
        }
      });
  }
}
