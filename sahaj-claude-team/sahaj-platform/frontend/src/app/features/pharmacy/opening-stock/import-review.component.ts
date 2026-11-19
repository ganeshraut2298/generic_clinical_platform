import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { IconComponent } from '../../../shared/icon/icon.component';
import { formatDisplayDate, formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, saveErrorMessage } from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import { OpeningStockImport, OpeningStockLine } from '../pharmacy.model';
import { CONFIRM_SHEET_CONFIG, ConfirmSheetComponent } from '../shared/confirm-sheet.component';
import {
  approveLockText,
  decisionText,
  fieldLabel,
  importChip,
  rateText,
  rupees,
} from './opening-stock.util';
import { POST_TO_STOCK_CONFIG, PostToStockData, PostToStockDialog } from './post-to-stock.dialog';
import { REJECT_IMPORT_CONFIG, RejectImportData, RejectImportDialog } from './reject-import.dialog';

const LINES_PAGE = 50;

/**
 * Review of one opening-stock file (story 78-7): the store, the number of lines, the total quantity, the
 * total value at purchase rate, the warnings, and every line with its problems. The owner approves or
 * rejects a file that is waiting, and may reject a draft too (DEC-076), so that a draft nobody finishes does not
 * hold its store and products; the server answers import-state for any other state. Approve is not drawn when the server says the signed-in owner cannot
 * approve (the uploader may not approve their own file; the server refuses it anyway). An approved file is posted
 * to stock once (SAH-79-1, "Post to stock"); a posted file shows the Posted state and nothing else can change it.
 */
@Component({
  selector: 'app-import-review',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatCheckboxModule, IconComponent, EmptyStateComponent],
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    .titleline {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 8px;
    }
    .totals {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
      gap: 10px;
      margin: 0 0 12px;
    }
    .totals div {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding: 8px 12px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
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
    table {
      min-width: 880px;
    }
    .sub {
      font-size: 0.76rem;
      color: var(--ink-3);
    }
    .issue-error {
      color: var(--crit);
    }
    .issue-warning {
      color: var(--warn);
    }
    .acts {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-top: 12px;
    }
    .pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
      margin-top: 10px;
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="rv-h" data-testid="import-review">
      <div class="panel-h">
        <div class="titleline">
          <h2 id="rv-h">Review: {{ item()?.storeName ?? 'opening stock file' }}</h2>
          @if (item(); as it) {
            <span class="pill" [class]="chip(it).css" data-testid="review-status">
              {{ chip(it).text }}
            </span>
          }
        </div>
        <button mat-stroked-button type="button" data-testid="close-review" (click)="closed.emit()">
          Close review
        </button>
      </div>

      @if (loading()) {
        <p class="loading" role="status">Loading the file</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="review-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="load()">Try again</button>
        </div>
      } @else if (item(); as it) {
        <p class="sub" data-testid="review-meta">
          Uploaded by {{ it.uploadedBy.name }} on {{ when(it.createdAt) }}
          @if (it.decidedBy; as who) {
            · {{ it.status === 'REJECTED' ? 'Rejected' : 'Approved' }} by {{ who.name }} on
            {{ when(it.decidedAt) }}
          }
        </p>

        <dl class="totals" data-testid="review-totals">
          <div>
            <dt>Store</dt>
            <dd>{{ it.storeName }}</dd>
          </div>
          <div>
            <dt>Lines</dt>
            <dd data-testid="total-lines">{{ it.lineCount }}</dd>
          </div>
          <div>
            <dt>Total quantity</dt>
            <dd data-testid="total-quantity">{{ it.totalQuantity }}</dd>
          </div>
          <div>
            <dt>Total value at purchase rate</dt>
            <dd data-testid="total-value">{{ money(it.totalValuePaise) }}</dd>
          </div>
          <div>
            <dt>Warnings</dt>
            <dd data-testid="total-warnings">{{ it.warningCount }}</dd>
          </div>
          @if (it.errorCount > 0) {
            <div>
              <dt>Problems</dt>
              <dd data-testid="total-errors">{{ it.errorCount }}</dd>
            </div>
          }
        </dl>

        @if (decision(it); as text) {
          <p class="note" data-testid="reject-reason">Reason: {{ text }}</p>
        }
        @if (it.status === 'NOT_ACCEPTED') {
          <div class="notice err" data-testid="not-accepted-note">
            <app-icon name="alert" [size]="16" />
            <span>Not accepted. Fix every problem below and upload the file again.</span>
          </div>
        }
        @if (it.posted) {
          <div class="notice good" data-testid="posted-note">
            <app-icon name="check" [size]="16" />
            <span>
              Posted on {{ when(it.postedAt ?? null) }}{{ it.postedBy ? ' by ' + it.postedBy.name : '' }}. The
              stock is in {{ it.storeName }} and can be used. A correction is a stock adjustment.
            </span>
          </div>
        } @else if (it.status === 'APPROVED') {
          <div class="notice teal" data-testid="approved-note">
            <app-icon name="lock" [size]="16" />
            <span>
              Approved. The lines are kept as opening stock. They cannot be used until they are posted to stock.
            </span>
          </div>
        }
        @if (it.status === 'REPLACED') {
          <p class="note" data-testid="replaced-note">Replaced by a newer file. It cannot be approved.</p>
        }

        @if (it.warningCount > 0 || it.errorCount > 0) {
          <mat-checkbox
            [checked]="onlyIssues()"
            (change)="setOnlyIssues($event.checked)"
            data-testid="only-issues"
          >
            Show only rows with a problem or warning
          </mat-checkbox>
        }

        @if (linesLoading()) {
          <p class="loading" role="status">Loading lines</p>
        } @else if (linesError(); as text) {
          <div class="notice err" role="alert" data-testid="lines-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
            <button mat-stroked-button type="button" (click)="loadLines(page())">Try again</button>
          </div>
        } @else if (lines().length === 0) {
          <app-empty>No lines to show.</app-empty>
        } @else {
          <div class="tbl-wrap" data-testid="lines-table">
            <table>
              <caption class="sub">
                Row numbers count the data rows only. The header row and the example row are not counted.
              </caption>
              <thead>
                <tr>
                  <th scope="col">Row</th>
                  <th scope="col">Product</th>
                  <th scope="col">Batch</th>
                  <th scope="col">Expiry date</th>
                  <th scope="col" class="num">MRP</th>
                  <th scope="col" class="num">Purchase rate</th>
                  <th scope="col" class="num">Quantity</th>
                  <th scope="col" class="num">Value</th>
                  <th scope="col">Problems</th>
                </tr>
              </thead>
              <tbody>
                @for (line of lines(); track line.rowNo) {
                  <tr [attr.data-testid]="'line-' + line.rowNo">
                    <td>{{ line.rowNo }}</td>
                    <td>
                      <strong>{{ line.productName ?? line.productCode }}</strong>
                      <div class="sub">{{ line.productCode }}</div>
                    </td>
                    <td>{{ line.batch }}</td>
                    <td>{{ date(line) }}</td>
                    <td class="num">{{ rate(line.mrp) }}</td>
                    <td class="num">{{ rate(line.purchaseRate) }}</td>
                    <td class="num">{{ line.quantity ?? '—' }}</td>
                    <td class="num">{{ lineValue(line) }}</td>
                    <td>
                      @for (issue of line.issues; track issue.code + issue.field) {
                        <div [class]="'issue-' + issue.severity.toLowerCase()" data-testid="line-issue">
                          <strong>{{ issue.severity === 'ERROR' ? 'Error' : 'Warning' }}</strong>
                          · {{ label(issue.field) }}: {{ issue.message }}
                        </div>
                      } @empty {
                        —
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <div class="pager" data-testid="lines-pager">
            <span role="status">{{ rangeText() }}</span>
            <button
              mat-stroked-button
              type="button"
              data-testid="lines-prev"
              [disabled]="page() === 0"
              (click)="loadLines(page() - 1)"
            >
              Previous
            </button>
            <button
              mat-stroked-button
              type="button"
              data-testid="lines-next"
              [disabled]="!hasNext()"
              (click)="loadLines(page() + 1)"
            >
              Next
            </button>
          </div>
        }

        @if (it.status === 'APPROVED' && !it.posted && canPost()) {
          <div class="acts" data-testid="post-acts">
            <button
              mat-flat-button
              type="button"
              data-testid="post-to-stock"
              [disabled]="deciding()"
              (click)="openPost(it)"
            >
              Post to stock
            </button>
          </div>
        }

        @if (decisionError(); as text) {
          <div class="notice err" role="alert" data-testid="decision-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }

        @if (it.status === 'WAITING_APPROVAL' || (it.status === 'DRAFT' && canReject())) {
          <div class="acts" data-testid="decision">
            @if (it.status === 'WAITING_APPROVAL' && it.canApprove) {
              <button
                mat-flat-button
                type="button"
                data-testid="approve"
                [disabled]="deciding()"
                (click)="confirmApprove(it)"
              >
                Approve
              </button>
            }
            @if (canReject()) {
              <button
                mat-stroked-button
                type="button"
                data-testid="reject"
                [disabled]="deciding()"
                (click)="openReject(it)"
              >
                Reject
              </button>
            }
          </div>
          @if (lockText(it); as text) {
            <p class="note" data-testid="approve-lock">{{ text }}</p>
          }
        }
      }
    </section>
  `,
})
export class ImportReviewComponent implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  private readonly context = inject(PharmacyContextStore);

  readonly storeId = input.required<string>();
  readonly importId = input.required<string>();
  /** The file changed (approved, rejected): the list above is read again. */
  readonly decided = output<OpeningStockImport>();
  readonly closed = output<void>();

  protected readonly item = signal<OpeningStockImport | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly lines = signal<OpeningStockLine[]>([]);
  protected readonly linesLoading = signal(false);
  protected readonly linesError = signal<string | null>(null);
  protected readonly page = signal(0);
  protected readonly total = signal(0);
  protected readonly onlyIssues = signal(false);
  protected readonly deciding = signal(false);
  protected readonly decisionError = signal<string | null>(null);

  /** Rejecting is an owner's action (SETUP_EDIT); the server refuses anyone else. */
  protected readonly canReject = this.context.canEditSetup;
  /** "Post to stock" (79-1): shown when the server lists postOpeningStock for the import's store. */
  protected readonly canPost = computed(() => this.context.canPostOpeningStockAt(this.storeId()));

  protected readonly hasNext = computed(() => (this.page() + 1) * LINES_PAGE < this.total());
  protected readonly rangeText = computed(() => {
    const from = this.page() * LINES_PAGE + 1;
    const to = Math.min(this.total(), (this.page() + 1) * LINES_PAGE);
    return `Showing ${from}–${to} of ${this.total()}`;
  });

  protected readonly chip = importChip;
  protected readonly money = rupees;
  protected readonly rate = rateText;
  protected readonly label = fieldLabel;
  protected readonly decision = decisionText;
  protected readonly lockText = approveLockText;
  protected readonly when = (instant: string | null) => formatDisplayDateTimeOfInstant(instant);
  protected readonly date = (line: OpeningStockLine) =>
    line.expiryDate ? formatDisplayDate(line.expiryDate) : '—';
  protected readonly lineValue = (line: OpeningStockLine) =>
    line.valuePaise === null ? '—' : rupees(line.valuePaise);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.openingStockImport(this.storeId(), this.importId()).subscribe({
      next: (item) => {
        this.item.set(item);
        this.loading.set(false);
        // A file that was not accepted is read for its problems first.
        this.onlyIssues.set(item.status === 'NOT_ACCEPTED');
        this.loadLines(0);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }

  protected setOnlyIssues(value: boolean): void {
    this.onlyIssues.set(value);
    this.loadLines(0);
  }

  protected loadLines(page: number): void {
    this.linesLoading.set(true);
    this.linesError.set(null);
    this.api
      .openingStockLines(this.storeId(), this.importId(), {
        withIssues: this.onlyIssues(),
        page,
        size: LINES_PAGE,
      })
      .subscribe({
        next: (answer) => {
          this.lines.set(answer.items);
          this.total.set(answer.total);
          this.page.set(answer.page);
          this.linesLoading.set(false);
        },
        error: (error: unknown) => {
          this.linesLoading.set(false);
          this.linesError.set(loadErrorMessage(error));
        },
      });
  }

  protected confirmApprove(item: OpeningStockImport): void {
    this.decisionError.set(null);
    this.dialog
      .open(ConfirmSheetComponent, {
        ...CONFIRM_SHEET_CONFIG,
        data: {
          title: `Approve opening stock for ${item.storeName}?`,
          body: `${item.lineCount} lines, total value ${rupees(item.totalValuePaise)}. After approval the file cannot be edited or cancelled.`,
          confirmLabel: 'Approve',
        },
      })
      .afterClosed()
      .subscribe((yes) => {
        if (yes === true) {
          this.approve(item);
        }
      });
  }

  private approve(item: OpeningStockImport): void {
    this.deciding.set(true);
    this.api.approveOpeningStockImport(item.storeId, item.id).subscribe({
      next: (done) => {
        this.deciding.set(false);
        this.item.set(done);
        this.decided.emit(done);
      },
      error: (error: unknown) => {
        this.deciding.set(false);
        this.decisionError.set(saveErrorMessage(error));
        // The file may have changed meanwhile (decided by someone else): read it again.
        this.api.openingStockImport(item.storeId, item.id).subscribe({
          next: (fresh) => this.item.set(fresh),
          error: () => undefined,
        });
      },
    });
  }

  protected openPost(item: OpeningStockImport): void {
    this.decisionError.set(null);
    const data: PostToStockData = { storeId: item.storeId, importId: item.id, storeName: item.storeName };
    this.dialog
      .open(PostToStockDialog, { ...POST_TO_STOCK_CONFIG, data })
      .afterClosed()
      .subscribe((done: OpeningStockImport | null | undefined) => {
        if (done) {
          this.item.set(done);
          this.decided.emit(done);
        } else {
          // The import may have been posted by someone else meanwhile: read it again.
          this.api.openingStockImport(item.storeId, item.id).subscribe({
            next: (fresh) => this.item.set(fresh),
            error: () => undefined,
          });
        }
      });
  }

  protected openReject(item: OpeningStockImport): void {
    this.decisionError.set(null);
    const data: RejectImportData = { storeId: item.storeId, importId: item.id, storeName: item.storeName };
    this.dialog
      .open(RejectImportDialog, { ...REJECT_IMPORT_CONFIG, data })
      .afterClosed()
      .subscribe((done: OpeningStockImport | null | undefined) => {
        if (done) {
          this.item.set(done);
          this.decided.emit(done);
        }
      });
  }
}
