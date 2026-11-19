import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { newClientKey } from '../../../shared/client-key';
import { formatDisplayDate } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { ApiError } from '../../../core/http/problem-detail';
import { loadErrorMessage, problemSlug, saveErrorMessage } from '../pharmacy-display.util';
import { OpeningStockPostingPreview } from '../pharmacy-inventory.model';
import { PharmacyApi } from '../pharmacy.api';
import { OpeningStockImport } from '../pharmacy.model';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import { expiryMonthYear } from '../inventory/inventory.util';
import { rateText, rupees } from './opening-stock.util';

export interface PostToStockData {
  storeId: string;
  importId: string;
  storeName: string;
}

export const POST_TO_STOCK_CONFIG = {
  panelClass: 'sheet-panel',
  width: '560px',
  maxWidth: '100vw',
  ariaLabelledBy: 'post-title',
} as const;

/** The text shown while the server posts (NEW, story 79-1). */
export const POSTING_TEXT = 'Posting. Do not close this page.';

/**
 * "Post to stock" confirmation of an approved opening-stock file (story 79-1). It reads the preview first (store,
 * lines, total quantity, total value, uploader and approver, the lines whose expiry date passed since approval
 * that will go to Quarantine, and any line whose batch exists with another MRP), then posts once. One
 * client key is kept for the whole attempt, so a retry after a lost connection replays the same answer and
 * never posts twice. While posting the sheet cannot be closed and the button is disabled.
 */
@Component({
  selector: 'app-post-to-stock',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, IconComponent],
  styles: [
    `
      .totals {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
        gap: 10px;
        margin: 0 0 12px;
      }
      .totals div {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .totals dt {
        font-size: 0.78rem;
        font-weight: 600;
        color: var(--ink-2);
      }
      .totals dd {
        margin: 0;
        font-weight: 600;
      }
      ul {
        margin: 6px 0 12px;
        padding-left: 18px;
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="post-title">Post to stock</h2>
        <p>{{ data.storeName }}</p>
      </div>
      <button
        mat-icon-button
        type="button"
        aria-label="Close"
        [disabled]="posting()"
        (click)="close()"
      >
        <app-icon name="x" />
      </button>
    </div>

    <div class="sheet-b">
      @if (loading()) {
        <p class="loading" role="status">Loading</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="post-load-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="load()">Try again</button>
        </div>
      } @else if (preview(); as p) {
        <dl class="totals" data-testid="post-totals">
          <div>
            <dt>Store</dt>
            <dd>{{ p.store.name }}</dd>
          </div>
          <div>
            <dt>Lines</dt>
            <dd data-testid="post-lines">{{ p.lineCount }}</dd>
          </div>
          <div>
            <dt>Total quantity</dt>
            <dd data-testid="post-quantity">{{ p.totalQuantity }}</dd>
          </div>
          <div>
            <dt>Total value at purchase rate</dt>
            <dd data-testid="post-value">{{ money(p.totalValuePaise) }}</dd>
          </div>
          <div>
            <dt>Uploaded by</dt>
            <dd>{{ p.uploadedBy.name }}</dd>
          </div>
          <div>
            <dt>Approved by</dt>
            <dd>{{ p.approvedBy?.name ?? '—' }}</dd>
          </div>
        </dl>

        @if (p.expiredLines.length > 0) {
          <div class="notice warn" data-testid="post-expired">
            <app-icon name="alert" [size]="16" />
            <span>
              {{ p.expiredLines.length }} {{ p.expiredLines.length === 1 ? 'line has' : 'lines have' }} expired
              since approval. They go to Quarantine with the reason Expired, never to Available.
            </span>
          </div>
          <ul data-testid="post-expired-list">
            @for (line of p.expiredLines; track line.rowNo) {
              <li>
                Row {{ line.rowNo }} · {{ line.productName }} · batch {{ line.batchNo }} · expired
                {{ month(line.expiryDate) }} · {{ line.quantity }}
              </li>
            }
          </ul>
        }

        @if (p.mrpMismatchLines.length > 0) {
          <div class="notice err" role="alert" data-testid="post-mrp">
            <app-icon name="alert" [size]="16" />
            <span>
              Same batch with a different MRP. Check the batch number. Nothing can be posted until the
              file is corrected.
            </span>
          </div>
          <ul data-testid="post-mrp-list">
            @for (line of p.mrpMismatchLines; track line.rowNo) {
              <li>
                Row {{ line.rowNo }} · batch {{ line.batchNo }} · file MRP {{ rate(line.fileMrp) }}, batch MRP
                {{ rate(line.batchMrp) }}
              </li>
            }
          </ul>
        }

        @if (lockText(); as text) {
          <p class="note" data-testid="post-lock">{{ text }}</p>
        }
        <p class="note">
          After posting, the stock can be used at once. A posted import cannot be edited, cancelled or posted
          again. A correction is a stock adjustment.
        </p>
      }

      @if (posting()) {
        <p class="loading" role="status" data-testid="posting-text">{{ postingText }}</p>
      }
      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="post-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </div>

    <div class="sheet-f">
      <button mat-stroked-button type="button" [disabled]="posting()" (click)="close()">Cancel</button>
      @if (preview()?.canPost) {
        <button
          mat-flat-button
          type="button"
          data-testid="confirm-post"
          [disabled]="posting()"
          (click)="post()"
        >
          Post to stock
        </button>
      }
    </div>
  `,
})
export class PostToStockDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<PostToStockDialog, OpeningStockImport | null>>(MatDialogRef);
  protected readonly data = inject<PostToStockData>(MAT_DIALOG_DATA);

  protected readonly preview = signal<OpeningStockPostingPreview | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly posting = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly postingText = POSTING_TEXT;

  /** One key for the whole attempt: a retry replays the same answer (rule 10 of the contract). */
  private attemptKey = newClientKey();

  protected readonly money = rupees;
  protected readonly rate = rateText;
  protected readonly month = expiryMonthYear;
  protected readonly date = formatDisplayDate;

  protected readonly lockText = computed<string | null>(() => {
    switch (this.preview()?.postLockReason) {
      case 'NOT_APPROVED':
        return 'This import is not approved.';
      case 'ALREADY_POSTED':
        return 'This import was already posted.';
      default:
        return null;
    }
  });

  ngOnInit(): void {
    this.ref.disableClose = false;
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.previewOpeningStockPosting(this.data.storeId, this.data.importId).subscribe({
      next: (preview) => {
        this.preview.set(preview);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }

  protected close(): void {
    if (!this.posting()) {
      this.ref.close(null);
    }
  }

  protected post(): void {
    if (this.posting()) {
      return;
    }
    this.formError.set(null);
    this.posting.set(true);
    this.ref.disableClose = true;
    this.api.postOpeningStock(this.data.storeId, this.data.importId, this.attemptKey).subscribe({
      next: (item) => {
        this.posting.set(false);
        this.ref.disableClose = false;
        this.ref.close(item);
      },
      error: (error: unknown) => {
        this.posting.set(false);
        this.ref.disableClose = false;
        this.formError.set(saveErrorMessage(error));
        const slug = problemSlug(error);
        // The import changed under us (already posted, not approved, an MRP clash): read it again.
        if (error instanceof ApiError && error.status === 409 && slug !== 'client-key-reused') {
          this.attemptKey = newClientKey();
          this.load();
        } else if (slug === 'client-key-reused') {
          this.attemptKey = newClientKey();
        }
      },
    });
  }
}
