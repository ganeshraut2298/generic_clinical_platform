import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { newClientKey } from '../../../shared/client-key';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { rupees } from '../opening-stock/opening-stock.util';
import { loadErrorMessage, saveErrorMessage } from '../pharmacy-display.util';
import { PurchaseOrder } from '../pharmacy-buying.model';
import { ApprovalInboxRow, ApprovalRequest, StockAdjustment } from '../pharmacy-inventory.model';
import { PharmacyApi } from '../pharmacy.api';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import { APPROVAL_REJECT_CONFIG, ApprovalRejectData, ApprovalRejectDialog } from './approval-reject.dialog';
import {
  actingAsText,
  decideLockText,
  differenceText,
  expiryMonthYear,
  historyEventText,
} from './inventory.util';

export interface ApprovalReviewData {
  row: ApprovalInboxRow;
}

export const APPROVAL_REVIEW_CONFIG = {
  panelClass: 'sheet-panel',
  width: '600px',
  maxWidth: '100vw',
  ariaLabelledBy: 'approval-review-title',
} as const;

/**
 * "Review" step of an inbox item (story 79-2): the request with its levels and decisions and, for a stock adjustment
 * (the only document of this release that goes through the inbox), the adjustment itself: batch, book and physical
 * quantity, difference, value, reason and history. The person approves or rejects here, never from the list. The
 * other transaction types open on their own screens when those are released (later work packages); until then their
 * request detail is shown and they can still be rejected. Nobody approves their own request: the lock reason of the
 * server is shown as a sentence and Approve is not drawn.
 */
@Component({
  selector: 'app-approval-review',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, IconComponent],
  styles: [
    `
      dl.facts {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
        gap: 10px;
        margin: 0 0 12px;
      }
      dl.facts div {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      dl.facts dt {
        font-size: 0.78rem;
        font-weight: 600;
        color: var(--ink-2);
      }
      dl.facts dd {
        margin: 0;
        font-weight: 600;
      }
      ol.steps {
        margin: 6px 0 12px;
        padding-left: 18px;
      }
      .sub {
        font-size: 0.78rem;
        color: var(--ink-3);
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="approval-review-title">Review</h2>
        <p data-testid="review-what">{{ data.row.what }}</p>
      </div>
      <button mat-icon-button type="button" (click)="close()" aria-label="Close">
        <app-icon name="x" />
      </button>
    </div>

    <div class="sheet-b">
      @if (loading()) {
        <p class="loading" role="status">Loading</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="review-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="load()">Try again</button>
        </div>
      } @else if (request(); as r) {
        <dl class="facts" data-testid="review-facts">
          <div>
            <dt>Raised by</dt>
            <dd>{{ r.raisedBy.name }} · {{ data.row.raisedStore.name }}</dd>
          </div>
          <div>
            <dt>Raised</dt>
            <dd>{{ when(r.raisedAt) }}</dd>
          </div>
          <div>
            <dt>Amount</dt>
            <dd>{{ money(r.amountPaise) }}</dd>
          </div>
          <div>
            <dt>Rule</dt>
            <dd>{{ data.row.ruleText }}</dd>
          </div>
        </dl>

        @if (r.escalated) {
          <p class="note" data-testid="review-escalated">Escalated. It waited longer than its rule allows.</p>
        }
        @if (r.needsAnotherApprover) {
          <p class="note" data-testid="review-needs-other">Needs another approver.</p>
        }

        @if (adjustment(); as a) {
          <h3>Stock adjustment {{ a.number }}</h3>
          <dl class="facts" data-testid="adjustment-facts">
            <div>
              <dt>Batch</dt>
              <dd>{{ a.product.name }} · {{ a.batch.batchNo }} · {{ month(a.batch.expiryDate) }}</dd>
            </div>
            <div>
              <dt>Book → physical</dt>
              <dd>{{ a.bookQty }} → {{ a.physicalQty }}</dd>
            </div>
            <div>
              <dt>Difference</dt>
              <dd>{{ diff(a.difference) }} · {{ money(a.valuePaise) }}</dd>
            </div>
            <div>
              <dt>Reason</dt>
              <dd>{{ a.reason.label }}{{ a.reason.note ? ' · ' + a.reason.note : '' }}</dd>
            </div>
          </dl>
        }

        @if (purchaseOrder(); as po) {
          <h3>Purchase order {{ po.number }}</h3>
          <dl class="facts" data-testid="po-facts">
            <div>
              <dt>Vendor</dt>
              <dd>{{ po.vendor.name }}</dd>
            </div>
            <div>
              <dt>Items</dt>
              <dd>{{ po.lines.length }}</dd>
            </div>
            <div>
              <dt>Total incl. GST</dt>
              <dd>{{ money(po.totalPaise) }}</dd>
            </div>
            @if (po.ruleNote) {
              <div>
                <dt>Rule</dt>
                <dd>{{ po.ruleNote }}</dd>
              </div>
            }
          </dl>
        }

        @if (r.levels.length > 0) {
          <h3>Levels</h3>
          <ol class="steps" data-testid="review-levels">
            @for (level of r.levels; track level.level) {
              <li>
                Level {{ level.level }} · {{ level.approver === 'OWNER' ? 'Owner' : 'Store HOD' }} · {{ level.ruleText }}
              </li>
            }
          </ol>
        }

        @if (r.decisions.length > 0) {
          <h3>Decisions</h3>
          <ol class="steps" data-testid="review-decisions">
            @for (d of r.decisions; track d.decidedAt) {
              <li>
                Level {{ d.level }} · {{ d.decision === 'APPROVED' ? 'Approved' : 'Rejected' }} by {{ d.decidedBy.name }}
                ({{ acting(d.actingAs) }}) · {{ when(d.decidedAt) }}{{ d.reason ? ' · ' + d.reason.label : '' }}
              </li>
            }
          </ol>
        }

        @if (adjustment(); as a) {
          @if (a.history.length > 0) {
            <h3>History</h3>
            <ol class="steps" data-testid="review-history">
              @for (h of a.history; track h.at + h.event) {
                <li>
                  {{ eventText(h.event) }} · {{ h.by?.name ?? 'System' }}{{ h.store ? ' · ' + h.store.name : '' }} ·
                  {{ when(h.at) }}
                  @if (h.qtyBefore !== null && h.qtyAfter !== null) {
                    · {{ h.qtyBefore }} → {{ h.qtyAfter }}
                  }
                  {{ h.reason ? ' · ' + h.reason.label : '' }}
                </li>
              }
            </ol>
          }
        }

        @if (lock(); as text) {
          <p class="note" data-testid="review-lock">{{ text }}</p>
        }
        @if (!canApproveHere() && r.canDecide) {
          <p class="note" data-testid="review-elsewhere">
            This document opens on its own screen when that screen is released. You can still reject it here.
          </p>
        }
      }

      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="decision-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </div>

    <div class="sheet-f">
      <button mat-stroked-button type="button" (click)="close()">Close</button>
      @if (request()?.canDecide) {
        <button
          mat-stroked-button
          type="button"
          data-testid="review-reject"
          [disabled]="deciding()"
          (click)="reject()"
        >
          Reject
        </button>
        @if (canApproveHere()) {
          <button
            mat-flat-button
            type="button"
            data-testid="review-approve"
            [disabled]="deciding()"
            (click)="approve()"
          >
            Approve
          </button>
        }
      }
    </div>
  `,
})
export class ApprovalReviewDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  private readonly ref = inject<MatDialogRef<ApprovalReviewDialog, boolean>>(MatDialogRef);
  protected readonly data = inject<ApprovalReviewData>(MAT_DIALOG_DATA);

  protected readonly request = signal<ApprovalRequest | null>(null);
  protected readonly adjustment = signal<StockAdjustment | null>(null);
  protected readonly purchaseOrder = signal<PurchaseOrder | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly deciding = signal(false);
  protected readonly formError = signal<string | null>(null);

  /** Only a document of a released screen is approved here (the stock adjustment of this release). */
  protected readonly canApproveHere = computed(() => this.data.row.subjectType === 'STOCK_ADJUSTMENT' || this.data.row.subjectType === 'PURCHASE_ORDER');
  protected readonly lock = computed(() => decideLockText(this.request()?.decideLockReason ?? null));

  private attemptKey = newClientKey();
  private changed = false;

  protected readonly money = rupees;
  protected readonly month = expiryMonthYear;
  protected readonly diff = differenceText;
  protected readonly acting = actingAsText;
  protected readonly eventText = historyEventText;
  protected readonly when = (instant: string) => formatDisplayDateTimeOfInstant(instant);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.approvalRequest(this.data.row.requestId).subscribe({
      next: (request) => {
        this.request.set(request);
        this.loading.set(false);
        this.loadDocument(request);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }

  private loadDocument(request: ApprovalRequest): void {
    // The row names the store path the document is opened under (required since contract 0.3.2).
    const storeId = this.data.row.subjectStoreId;
    if (request.subjectType === 'PURCHASE_ORDER') {
      this.api.purchaseOrder(storeId, request.subjectId).subscribe({
        next: (po) => this.purchaseOrder.set(po),
        error: () => this.purchaseOrder.set(null),
      });
      return;
    }
    if (request.subjectType !== 'STOCK_ADJUSTMENT') {
      return;
    }
    this.api.stockAdjustment(storeId, request.subjectId).subscribe({
      next: (a) => this.adjustment.set(a),
      error: () => this.adjustment.set(null),
    });
  }

  protected close(): void {
    this.ref.close(this.changed);
  }

  protected approve(): void {
    const request = this.request();
    if (this.deciding() || request === null || request.currentLevel === null) {
      return;
    }
    this.formError.set(null);
    this.deciding.set(true);
    this.api
      .decideApproval(request.id, {
        clientKey: this.attemptKey,
        level: request.currentLevel,
        requestVersion: request.version,
        decision: 'APPROVED',
      })
      .subscribe({
        next: () => {
          this.deciding.set(false);
          this.changed = true;
          this.ref.close(true);
        },
        error: (error: unknown) => {
          this.deciding.set(false);
          this.formError.set(saveErrorMessage(error));
          // Someone else decided first, or the stock moved: read the request again so that the sheet shows the truth.
          this.attemptKey = newClientKey();
          this.changed = true;
          this.load();
        },
      });
  }

  protected reject(): void {
    const data: ApprovalRejectData = { requestId: this.data.row.requestId, what: this.data.row.what };
    this.dialog
      .open(ApprovalRejectDialog, { ...APPROVAL_REJECT_CONFIG, data })
      .afterClosed()
      .subscribe((done: ApprovalRequest | null | undefined) => {
        if (done) {
          this.changed = true;
          this.ref.close(true);
        }
      });
  }
}
