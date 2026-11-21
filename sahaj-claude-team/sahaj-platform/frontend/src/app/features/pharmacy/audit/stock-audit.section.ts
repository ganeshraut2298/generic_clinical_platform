import { ChangeDetectionStrategy, Component, OnInit, inject, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage } from '../pharmacy-display.util';
import { StockAudit, StockAuditPage } from '../pharmacy-stockcount.model';
import { PharmacyApi } from '../pharmacy.api';
import { auditChip, blindQty, signedRupees } from '../stockuse/stockuse.util';
import { COUNT_SHEET_CONFIG, CountSheetData, CountSheetDialog } from './count-sheet.dialog';
import { START_COUNT_CONFIG, StartCountData, StartCountDialog } from './start-count.dialog';
import { VARIANCE_CONFIG, VarianceData, VarianceDialog, VarianceResult } from './variance.dialog';

const PAGE_SIZE = 20;

/**
 * Stock audit (physical verification, story 79-17, mockup 13): "<store> · audit AUD-…" with the tiles Items in this
 * count, Counted, With variance and Variance value. The count is blind: "With variance" and "Variance value" are given
 * only to a person who reviews counts, and only after the count is submitted; otherwise they show a dash and the audit
 * says "Counting in progress". The counter opens the count sheet, the reviewer the variance review. "Start stock count"
 * needs the start capability. The audit plan table of the mockup (frequency per store) is not built: the contract has no
 * audit plan.
 */
@Component({
  selector: 'app-stock-audit',
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
    .audit {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 12px 0;
      border-top: 1px solid var(--line);
    }
    .tiles {
      display: flex;
      flex-wrap: wrap;
      gap: 16px;
      font-weight: 600;
    }
    .acts {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .acts button {
      min-height: 44px;
    }
    .pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="au-h" data-testid="stock-audit">
      <div class="head">
        <h2 id="au-h">Stock audit (physical verification)</h2>
        @if (canStart()) {
          <button mat-flat-button type="button" data-testid="start-count" (click)="start()">Start stock count</button>
        }
      </div>
      <p class="notice teal" data-testid="audit-blind-note">
        <app-icon name="lock" [size]="16" />
        <span>Blind count · the counter does not see the system quantity, so the count is honest.</span>
      </p>
      @if (notice(); as text) {
        <div class="notice good" role="status" data-testid="audit-note">
          <app-icon name="check" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
      @if (loading()) {
        <p class="loading" role="status">Loading the counts</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="audit-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="load(0)">Try again</button>
        </div>
      } @else if (data(); as d) {
        @if (d.items.length === 0) {
          <app-empty data-testid="audit-empty">No stock count yet for this store.</app-empty>
        } @else {
          @for (audit of d.items; track audit.id) {
            <article class="audit" [attr.data-testid]="'audit-' + audit.number">
              <h3>
                {{ audit.store.name }} · audit {{ audit.number }}
                <span class="pill" [class]="chip(audit).css" [attr.data-testid]="'audit-status-' + audit.number">
                  {{ chip(audit).text }}
                </span>
              </h3>
              <p class="tiles" [attr.data-testid]="'audit-tiles-' + audit.number">
                <span>Items in this count {{ audit.itemsInCount }}</span>
                <span>Counted {{ audit.counted }}</span>
                <span data-testid="tile-variance">With variance {{ qty(audit.withVariance) }}</span>
                <span data-testid="tile-value">Variance value {{ money(audit.varianceValuePaise) }}</span>
              </p>
              <p class="sub">Started by {{ audit.startedBy.name }} · {{ when(audit.startedAt) }}</p>
              <div class="acts">
                @if (canCount() && (audit.status === 'COUNTING' || audit.status === 'RECOUNT')) {
                  <button mat-flat-button type="button" [attr.data-testid]="'open-count-' + audit.number" (click)="openCount(audit)">
                    {{ audit.status === 'RECOUNT' ? 'Recount' : 'Count sheet' }}
                  </button>
                }
                @if (canReview() && audit.status !== 'COUNTING' && audit.status !== 'RECOUNT') {
                  <button mat-stroked-button type="button" [attr.data-testid]="'open-variance-' + audit.number" (click)="openVariance(audit)">
                    Variance review
                  </button>
                }
              </div>
            </article>
          }
          @if (d.total > pageSize) {
            <div class="pager" data-testid="audit-pager">
              <span role="status">Page {{ d.page + 1 }} of {{ pages(d.total) }}</span>
              <button mat-stroked-button type="button" [disabled]="d.page === 0" (click)="load(d.page - 1)">Previous</button>
              <button mat-stroked-button type="button" [disabled]="(d.page + 1) * pageSize >= d.total" (click)="load(d.page + 1)">
                Next
              </button>
            </div>
          }
        }
      }
    </section>
  `,
})
export class StockAuditSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  private readonly context = inject(PharmacyContextStore);

  /** "Post as consumption instead": the reviewer's chosen audit line, handed to the Ward consumption tab (79-16). */
  readonly consumptionRequested = output<string>();

  protected readonly pageSize = PAGE_SIZE;
  protected readonly data = signal<StockAuditPage | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly canStart = this.context.canStartCount;
  protected readonly canCount = this.context.canCount;
  protected readonly canReview = this.context.canReviewCount;

  protected readonly chip = (audit: StockAudit) => auditChip(audit.status);
  protected readonly qty = blindQty;
  protected readonly money = signedRupees;
  protected readonly when = (instant: string) => formatDisplayDateTimeOfInstant(instant);
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
    this.api.stockAudits(storeId, { page, size: PAGE_SIZE }).subscribe({
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

  protected start(): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    const data: StartCountData = { storeId, storeName: this.context.workingStore()?.name ?? '' };
    this.dialog
      .open(StartCountDialog, { ...START_COUNT_CONFIG, data })
      .afterClosed()
      .subscribe((audit: StockAudit | null | undefined) => {
        if (audit) {
          this.notice.set(`Audit ${audit.number} started. Counting in progress.`);
          this.load(0);
        }
      });
  }

  protected openCount(audit: StockAudit): void {
    const data: CountSheetData = { storeId: audit.store.storeId, auditId: audit.id };
    this.dialog
      .open(CountSheetDialog, { ...COUNT_SHEET_CONFIG, data })
      .afterClosed()
      .subscribe((done: StockAudit | null | undefined) => {
        if (done) {
          this.notice.set(`Count of ${done.number} submitted. It is waiting for review.`);
        }
        this.load(0);
      });
  }

  protected openVariance(audit: StockAudit): void {
    const data: VarianceData = { storeId: audit.store.storeId, auditId: audit.id };
    this.dialog
      .open(VarianceDialog, { ...VARIANCE_CONFIG, data })
      .afterClosed()
      .subscribe((result: VarianceResult | undefined) => {
        if (result && 'consumptionLineId' in result) {
          this.consumptionRequested.emit(result.consumptionLineId);
          return;
        }
        if (result && 'audit' in result) {
          this.notice.set(
            result.audit.status === 'CLOSED'
              ? `Audit ${result.audit.number} is closed. The adjustment is posted.`
              : result.audit.status === 'RECOUNT'
                ? `Audit ${result.audit.number} went back to the counter for a recount.`
                : `Audit ${result.audit.number} is waiting for approval.`,
          );
        }
        this.load(0);
      });
  }
}
