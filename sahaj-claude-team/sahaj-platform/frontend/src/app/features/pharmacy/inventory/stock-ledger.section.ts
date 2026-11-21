import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiError } from '../../../core/http/problem-detail';
import { addDays, formatDisplayDate, formatDisplayDateTimeOfInstant, istToday } from '../../../shared/format/ist-date';
import { provideIsoDateAdapter } from '../../../shared/format/iso-date-adapter';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage } from '../pharmacy-display.util';
import { STOCK_STATE_LABELS, StockLedgerPage } from '../pharmacy-inventory.model';
import { PharmacyApi } from '../pharmacy.api';
import { exportErrorMessage, saveBlob } from '../stockuse/stockuse.util';
import { expiryMonthYear, ledgerTransactionText } from './inventory.util';
import { PickedProduct, ProductPickerComponent } from './product-picker.component';

const PAGE_SIZE = 50;
/** The longest range the server accepts: 366 days ("Choose at most 12 months."). */
const MAX_DAYS = 366;

/**
 * Stock ledger (story 79-1 and 79-3: "each ledger line can be read later with the user who posted"; mockup 11 note
 * "Every step is recorded with user, store, time and reason"). One product and a date range; one line per movement,
 * oldest first: time, transaction, reference, store and state, batch, in, out, balance after the line, user and
 * second signer, and the line a correction points to. The operation is tagged SAH-79-18 in the contract (work package
 * H), so this screen shows only to a person whose context lists STOCK_REPORTS. Never shows a patient name; the server
 * sends the admission number for patient-wise consumption.
 */
@Component({
  selector: 'app-stock-ledger',
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
    ProductPickerComponent,
  ],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    form.filters {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      align-items: flex-start;
    }
    table {
      min-width: 980px;
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
    <section class="panel surf" aria-labelledby="sl-h" data-testid="stock-ledger">
      <h2 id="sl-h">Stock ledger</h2>
      <p class="note">Every movement is a line that is never edited. A correction is a new line that points to the one it corrects.</p>
      <app-product-picker (picked)="choose($event)" />

      @if (product(); as p) {
        <h3 data-testid="ledger-heading">Stock ledger · {{ p.name }} {{ p.strength }}</h3>
        <form class="filters" [formGroup]="form" (ngSubmit)="show()" novalidate>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Store</mat-label>
            <mat-select formControlName="storeId" data-testid="ledger-store">
              <mat-option value="">All my stores</mat-option>
              @for (s of stores(); track s.storeId) {
                <mat-option [value]="s.storeId">{{ s.name }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>From</mat-label>
            <input matInput [matDatepicker]="fromPicker" formControlName="from" data-testid="ledger-from" />
            <mat-datepicker-toggle matIconSuffix [for]="fromPicker" aria-label="Choose the start date" />
            <mat-datepicker #fromPicker />
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>To</mat-label>
            <input matInput [matDatepicker]="toPicker" formControlName="to" data-testid="ledger-to" />
            <mat-datepicker-toggle matIconSuffix [for]="toPicker" aria-label="Choose the end date" />
            <mat-datepicker #toPicker />
          </mat-form-field>
          <button mat-flat-button type="submit" data-testid="ledger-show" [disabled]="loading()">Show</button>
          <button mat-stroked-button type="button" data-testid="ledger-csv" [disabled]="loading() || data() === null" (click)="exportCsv()">Download CSV</button>
        </form>
        @if (exportError(); as text) {
          <p class="note" role="alert" data-testid="ledger-csv-error">{{ text }}</p>
        }
        @if (rangeError(); as text) {
          <p class="note" role="alert" data-testid="ledger-range-error">{{ text }}</p>
        }

        @if (loading()) {
          <p class="loading" role="status">Loading the ledger</p>
        } @else if (loadError(); as text) {
          <div class="notice err" role="alert" data-testid="ledger-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
            <button mat-stroked-button type="button" (click)="load(page())">Try again</button>
          </div>
        } @else if (data(); as d) {
          @if (d.items.length === 0) {
            <app-empty data-testid="ledger-empty">No movements of this product in these dates.</app-empty>
          } @else {
            <div class="tbl-wrap" data-testid="ledger-table">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Time</th>
                    <th scope="col">Transaction</th>
                    <th scope="col">Reference</th>
                    <th scope="col">Store · state</th>
                    <th scope="col">Batch</th>
                    <th scope="col" class="num">In</th>
                    <th scope="col" class="num">Out</th>
                    <th scope="col" class="num">Balance</th>
                    <th scope="col">User</th>
                  </tr>
                </thead>
                <tbody>
                  @for (line of d.items; track line.ledgerId) {
                    <tr [attr.data-testid]="'ledger-' + line.ledgerId">
                      <td>{{ when(line.at) }}</td>
                      <td>
                        {{ txn(line.transaction) }}
                        @if (line.reversesLedgerId) {
                          <div class="sub" data-testid="reverses">Corrects an earlier line</div>
                        }
                      </td>
                      <td>{{ line.ref ?? '—' }}</td>
                      <td>{{ line.store.name }} · {{ states[line.state] }}</td>
                      <td>
                        {{ line.batch.batchNo }}
                        <div class="sub">{{ month(line.batch.expiryDate) }}</div>
                      </td>
                      <td class="num">{{ line.qtyIn ? line.qtyIn : '—' }}</td>
                      <td class="num">{{ line.qtyOut ? line.qtyOut : '—' }}</td>
                      <td class="num">{{ line.balance ?? '—' }}</td>
                      <td>
                        {{ line.user?.name ?? 'System' }}
                        @if (line.secondSigner; as signer) {
                          <div class="sub">Second signer: {{ signer.name }}</div>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            @if (d.total > PAGE_SIZE) {
              <div class="pager" data-testid="ledger-pager">
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
            <p class="sub">{{ range(d) }}</p>
          }
        }
      }
    </section>
  `,
})
export class StockLedgerSection {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  protected readonly PAGE_SIZE = PAGE_SIZE;
  protected readonly states = STOCK_STATE_LABELS;
  protected readonly stores = this.context.stores;

  protected readonly product = signal<PickedProduct | null>(null);
  protected readonly data = signal<StockLedgerPage | null>(null);
  protected readonly page = signal(0);
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly rangeError = signal<string | null>(null);
  protected readonly exportError = signal<string | null>(null);

  protected readonly form = new FormGroup({
    storeId: new FormControl('', { nonNullable: true }),
    from: new FormControl<string | null>(addDays(istToday(), -30)),
    to: new FormControl<string | null>(istToday()),
  });

  protected readonly pages = (total: number) => Math.max(1, Math.ceil(total / PAGE_SIZE));
  protected readonly txn = ledgerTransactionText;
  protected readonly month = expiryMonthYear;
  protected readonly when = (instant: string) => formatDisplayDateTimeOfInstant(instant);
  protected readonly range = (d: StockLedgerPage) => `${formatDisplayDate(d.from)} to ${formatDisplayDate(d.to)}`;
  protected readonly hasProduct = computed(() => this.product() !== null);

  protected choose(product: PickedProduct): void {
    this.product.set(product);
    this.data.set(null);
    this.show();
  }

  protected exportCsv(): void {
    const product = this.product();
    const { storeId, from, to } = this.form.getRawValue();
    if (!product || !from || !to) {
      return;
    }
    this.exportError.set(null);
    this.api.exportStockLedger({ productId: product.productId, storeId: storeId === '' ? undefined : storeId, from, to }).subscribe({
      next: (blob) => saveBlob(blob, `sahaj-stock-ledger-${from}-${to}.csv`),
      error: (error: unknown) => this.exportError.set(exportErrorMessage(error)),
    });
  }

  protected show(): void {
    const { from, to } = this.form.getRawValue();
    this.rangeError.set(null);
    if (!from || !to) {
      this.rangeError.set('Choose the dates.');
      return;
    }
    if (to < from) {
      this.rangeError.set('The end date must be on or after the start date.');
      return;
    }
    if (addDays(from, MAX_DAYS) < to) {
      this.rangeError.set('Choose at most 12 months.');
      return;
    }
    this.load(0);
  }

  protected load(page: number): void {
    const product = this.product();
    const { storeId, from, to } = this.form.getRawValue();
    if (!product || !from || !to) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.api
      .stockLedger({
        productId: product.productId,
        storeId: storeId === '' ? undefined : storeId,
        from,
        to,
        page,
        size: PAGE_SIZE,
      })
      .subscribe({
        next: (answer) => {
          this.data.set(answer);
          this.page.set(answer.page);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.data.set(null);
          this.loading.set(false);
          // The server's sentence for a wrong range is kept ("Choose at most 12 months.").
          this.loadError.set(
            error instanceof ApiError && error.status === 400 && error.problem.detail
              ? error.problem.detail
              : loadErrorMessage(error),
          );
        },
      });
  }
}
