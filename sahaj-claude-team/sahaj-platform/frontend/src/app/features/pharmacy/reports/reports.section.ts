import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { addDays, istToday } from '../../../shared/format/ist-date';
import { provideIsoDateAdapter } from '../../../shared/format/iso-date-adapter';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { StockLedgerSection } from '../inventory/stock-ledger.section';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage } from '../pharmacy-display.util';
import {
  ReportCatalogue,
  ReportPage,
  ReportQuery,
} from '../pharmacy-stockcount.model';
import { PharmacyApi } from '../pharmacy.api';
import { Vendor } from '../pharmacy.model';
import { exportErrorMessage, isNumericColumn, reportCellText, saveBlob } from '../stockuse/stockuse.util';

const PAGE_SIZE = 50;
const MAX_DAYS = 366;
/** Reports "as of now": the date range is ignored by the server (store-stock, batch-expiry, below-reorder). */
const NOW_REPORTS = new Set(['store-stock', 'batch-expiry', 'below-reorder']);
const PURCHASE_REPORTS = new Set(['po-vs-grn', 'purchase-register', 'debit-notes-pending']);
const LEDGER_KEY = 'stock-ledger';

/**
 * Reports & stock ledger (stories 79-18 and 79-19, mockup 15). The catalogue is what the server lists for the caller,
 * grouped Stock, Movement, Purchase; a group with no report is not drawn. A report is run for a store and dates, shown
 * in the typed columns the server sends (a hidden quantity is a dash, contract rule 24), downloaded as CSV, or printed
 * from the browser. The stock ledger of one product keeps its own screen. The group "Sales & compliance" (SAH-80-21) comes
 * from the same catalogue when the server lists it (a cashier gets none); its six reports use these filters, the
 * grouping and person filters of the report text are not in the contract and are not offered. Not built: stock valuation, non-moving, vendor performance, rate history (OQ-79-33).
 */
@Component({
  selector: 'app-reports',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [provideIsoDateAdapter()],
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDatepickerModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    IconComponent,
    EmptyStateComponent,
    StockLedgerSection,
  ],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .group {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
    }
    .group b {
      min-width: 90px;
    }
    .group button[aria-pressed='true'] {
      border: 2px solid var(--pri);
      background: var(--pri-soft);
      color: var(--pri-text);
    }
    .group button {
      min-height: 44px;
    }
    form.filters {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      align-items: flex-start;
    }
    table {
      min-width: 800px;
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
    @media print {
      form.filters,
      .group,
      .pager,
      .noprint {
        display: none;
      }
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="rep-h" data-testid="reports">
      <h2 id="rep-h">Reports</h2>
      @if (catalogue(); as c) {
        <p class="note" data-testid="reports-line">{{ c.line }}</p>
        @for (g of c.groups; track g.group) {
          @if (g.reports.length > 0) {
            <div class="group" role="group" [attr.aria-label]="g.label" [attr.data-testid]="'report-group-' + g.group">
              <b>{{ g.label }}</b>
              @for (r of g.reports; track r.key) {
                <button
                  mat-stroked-button
                  type="button"
                  [attr.aria-pressed]="selected() === r.key"
                  [attr.data-testid]="'report-' + r.key"
                  (click)="choose(r.key)"
                >
                  {{ r.label }}
                </button>
              }
            </div>
          }
        } @empty {
          <app-empty data-testid="reports-none">You have no report to run.</app-empty>
        }
      } @else if (catalogueError(); as text) {
        <div class="notice err" role="alert" data-testid="reports-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="ngOnInit()">Try again</button>
        </div>
      } @else {
        <p class="loading" role="status">Loading reports</p>
      }
    </section>

    @if (selected() === ledgerKey) {
      <app-stock-ledger />
    } @else if (selected(); as key) {
      <section class="panel surf" aria-labelledby="run-h" data-testid="report-run">
        <h3 id="run-h">{{ label(key) }}</h3>
        <form class="filters" [formGroup]="form" (ngSubmit)="run(0)" novalidate>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Store</mat-label>
            <mat-select formControlName="storeId" data-testid="report-store">
              <mat-option value="">{{ isPurchase() ? 'Central Store' : 'All my stores' }}</mat-option>
              @for (s of stores(); track s.storeId) {
                <mat-option [value]="s.storeId">{{ s.name }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          @if (!isNow()) {
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>From</mat-label>
              <input matInput [matDatepicker]="fromPicker" formControlName="from" data-testid="report-from" />
              <mat-datepicker-toggle matIconSuffix [for]="fromPicker" aria-label="Choose the start date" />
              <mat-datepicker #fromPicker />
            </mat-form-field>
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>To</mat-label>
              <input matInput [matDatepicker]="toPicker" formControlName="to" data-testid="report-to" />
              <mat-datepicker-toggle matIconSuffix [for]="toPicker" aria-label="Choose the end date" />
              <mat-datepicker #toPicker />
            </mat-form-field>
          }
          @if (isPurchase()) {
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Vendor</mat-label>
              <mat-select formControlName="vendorId" data-testid="report-vendor">
                <mat-option value="">All vendors</mat-option>
                @for (v of vendors(); track v.id) {
                  <mat-option [value]="v.id">{{ v.name }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
          }
          @if (key === 'po-vs-grn') {
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Status</mat-label>
              <mat-select formControlName="status" data-testid="report-status">
                <mat-option value="">All</mat-option>
                <mat-option value="OVERDUE">Overdue</mat-option>
              </mat-select>
            </mat-form-field>
          }
          <button mat-flat-button type="submit" data-testid="report-show" [disabled]="loading()">Show</button>
          <button mat-stroked-button type="button" data-testid="report-csv" [disabled]="loading() || page() === null" (click)="download()">
            Download CSV
          </button>
          <button mat-stroked-button type="button" data-testid="report-print" [disabled]="page() === null" (click)="print()">
            Print
          </button>
        </form>
        @if (rangeError(); as text) {
          <p class="note" role="alert" data-testid="report-range-error">{{ text }}</p>
        }
        @if (downloadError(); as text) {
          <p class="note" role="alert" data-testid="report-csv-error">{{ text }}</p>
        }

        @if (loading()) {
          <p class="loading" role="status">Loading the report</p>
        } @else if (runError(); as text) {
          <div class="notice err" role="alert" data-testid="report-run-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
          </div>
        } @else if (page(); as p) {
          @if (p.rows.length === 0) {
            <app-empty data-testid="report-empty">Nothing to show for these filters.</app-empty>
          } @else {
            <div class="tbl-wrap" data-testid="report-table">
              <table>
                <caption class="sr-only">{{ p.title }}</caption>
                <thead>
                  <tr>
                    @for (col of p.columns; track col.key) {
                      <th scope="col" [class.num]="numeric(col)">{{ col.label }}</th>
                    }
                  </tr>
                </thead>
                <tbody>
                  @for (row of p.rows; track $index; let r = $index) {
                    <tr [attr.data-testid]="'report-row-' + r">
                      @for (col of p.columns; track col.key; let c = $index) {
                        <td [class.num]="numeric(col)">{{ cell(p, c, row[c]) }}</td>
                      }
                    </tr>
                  }
                </tbody>
                @if (p.totals; as totals) {
                  <tfoot data-testid="report-totals">
                    @for (t of totals; track $index) {
                      <tr>
                        @for (col of p.columns; track col.key; let c = $index) {
                          <td [class.num]="numeric(col)">{{ c === 0 ? t.label : t.cells[c] === undefined ? '' : cell(p, c, t.cells[c]) }}</td>
                        }
                      </tr>
                    }
                  </tfoot>
                }
              </table>
            </div>
            @if (p.total > pageSize) {
              <div class="pager" data-testid="report-pager">
                <span role="status">Page {{ p.page + 1 }} of {{ pages(p.total) }}</span>
                <button mat-stroked-button type="button" [disabled]="p.page === 0" (click)="run(p.page - 1)">Previous</button>
                <button mat-stroked-button type="button" [disabled]="(p.page + 1) * pageSize >= p.total" (click)="run(p.page + 1)">
                  Next
                </button>
              </div>
            }
            <p class="note">{{ p.total }} rows. Download the CSV file for all of them.</p>
          }
        }
      </section>
    }
  `,
})
export class ReportsSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  protected readonly ledgerKey = LEDGER_KEY;
  protected readonly pageSize = PAGE_SIZE;
  protected readonly stores = this.context.stores;

  protected readonly catalogue = signal<ReportCatalogue | null>(null);
  protected readonly catalogueError = signal<string | null>(null);
  protected readonly selected = signal<string | null>(null);
  protected readonly vendors = signal<Vendor[]>([]);
  protected readonly page = signal<ReportPage | null>(null);
  protected readonly loading = signal(false);
  protected readonly runError = signal<string | null>(null);
  protected readonly rangeError = signal<string | null>(null);
  protected readonly downloadError = signal<string | null>(null);

  protected readonly form = new FormGroup({
    storeId: new FormControl('', { nonNullable: true }),
    from: new FormControl<string | null>(addDays(istToday(), -30)),
    to: new FormControl<string | null>(istToday()),
    vendorId: new FormControl('', { nonNullable: true }),
    status: new FormControl('', { nonNullable: true }),
  });

  protected readonly isNow = computed(() => NOW_REPORTS.has(this.selected() ?? ''));
  protected readonly isPurchase = computed(() => PURCHASE_REPORTS.has(this.selected() ?? ''));

  protected readonly numeric = isNumericColumn;
  protected readonly pages = (total: number) => Math.max(1, Math.ceil(total / PAGE_SIZE));
  protected readonly cell = (page: ReportPage, index: number, value: string | number | null) =>
    reportCellText(page.columns[index], value);

  ngOnInit(): void {
    this.catalogueError.set(null);
    this.api.reportCatalogue().subscribe({
      next: (c) => {
        this.catalogue.set(c);
        if (this.selected() === null) {
          const first = c.groups.flatMap((g) => g.reports)[0];
          if (first) {
            this.choose(first.key);
          }
        }
      },
      error: (error: unknown) => this.catalogueError.set(loadErrorMessage(error)),
    });
  }

  protected label(key: string): string {
    return (
      this.catalogue()
        ?.groups.flatMap((g) => g.reports)
        .find((r) => r.key === key)?.label ?? key
    );
  }

  protected choose(key: string): void {
    this.selected.set(key);
    this.page.set(null);
    this.runError.set(null);
    this.rangeError.set(null);
    this.downloadError.set(null);
    this.form.patchValue({ vendorId: '', status: '' });
    if (PURCHASE_REPORTS.has(key) && this.vendors().length === 0) {
      this.api.vendors({ status: 'ALL', size: 100 }).subscribe({
        next: (answer) => this.vendors.set(answer.items),
        error: () => this.vendors.set([]),
      });
    }
    if (key !== LEDGER_KEY) {
      this.run(0);
    }
  }

  private query(): ReportQuery | null {
    const { storeId, from, to, vendorId, status } = this.form.getRawValue();
    this.rangeError.set(null);
    const key = this.selected() ?? '';
    const query: ReportQuery = {
      storeId: storeId || undefined,
      vendorId: PURCHASE_REPORTS.has(key) ? vendorId || undefined : undefined,
      status: key === 'po-vs-grn' ? status || undefined : undefined,
    };
    if (!NOW_REPORTS.has(key)) {
      if (!from || !to) {
        this.rangeError.set('Choose the dates.');
        return null;
      }
      if (to < from) {
        this.rangeError.set('The end date must be on or after the start date.');
        return null;
      }
      if (addDays(from, MAX_DAYS) < to) {
        this.rangeError.set('Choose at most 12 months.');
        return null;
      }
      query.from = from;
      query.to = to;
    }
    return query;
  }

  protected run(pageNo: number): void {
    const key = this.selected();
    const query = this.query();
    if (!key || query === null) {
      return;
    }
    this.loading.set(true);
    this.runError.set(null);
    this.api.runReport(key, { ...query, page: pageNo, size: PAGE_SIZE }).subscribe({
      next: (answer) => {
        this.page.set(answer);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.page.set(null);
        this.loading.set(false);
        this.runError.set(exportErrorMessage(error));
      },
    });
  }

  protected download(): void {
    const key = this.selected();
    const query = this.query();
    if (!key || query === null) {
      return;
    }
    this.downloadError.set(null);
    this.api.exportReport(key, query).subscribe({
      next: (blob) => saveBlob(blob, `sahaj-${key}-${istToday()}.csv`),
      error: (error: unknown) => this.downloadError.set(exportErrorMessage(error)),
    });
  }

  protected print(): void {
    window.print();
  }
}
