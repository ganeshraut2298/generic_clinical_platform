import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage } from '../pharmacy-display.util';
import { ProductStockByStore, StoreStateRow } from '../pharmacy-inventory.model';
import { PharmacyApi } from '../pharmacy.api';
import { BatchFocus } from './batch-inventory.section';
import { stateQty, storeStockStatusText } from './inventory.util';
import { PickedProduct, ProductPickerComponent } from './product-picker.component';

type Scope = 'MY_STORE' | 'ALL_STORES';

/**
 * Store stock, tab "Current stock" (story 79-4, mockup 11): pick a product, then "My store" or "All stores". One row per
 * store the person can reach shows Available, In transit (in), Pending return, Quarantine, Reorder level and Status,
 * and the last row "Unit total". Only Available stock can be used. A store name opens that store's batches and
 * expiry for the product (79-3), only for a store the person can reach. Read only. The tabs "Ward consumption" and
 * "Return / damaged" of the mockup are later stories (79-14, 79-16).
 */
@Component({
  selector: 'app-store-stock',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, EmptyStateComponent, ProductPickerComponent],
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
    .num {
      text-align: right;
      white-space: nowrap;
    }
    table {
      min-width: 720px;
    }
    tfoot td {
      font-weight: 700;
    }
    .linkbtn {
      border: 0;
      background: transparent;
      padding: 0;
      min-height: 44px;
      text-align: left;
      font-weight: 600;
      color: var(--pri-text);
      text-decoration: underline;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="ss-h" data-testid="store-stock">
      <h2 id="ss-h">Store stock</h2>
      <p class="note">Pick a product to see its stock in every store.</p>
      <app-product-picker (picked)="choose($event)" />

      @if (product(); as p) {
        <div class="head">
          <h3 data-testid="stock-heading">{{ heading() }}</h3>
          <div class="seg" role="group" aria-label="Stores shown">
            @if (hasWorkingStore()) {
              <button
                type="button"
                data-testid="scope-my"
                [attr.aria-pressed]="scope() === 'MY_STORE'"
                (click)="setScope('MY_STORE')"
              >
                My store
              </button>
            }
            <button
              type="button"
              data-testid="scope-all"
              [attr.aria-pressed]="scope() === 'ALL_STORES'"
              (click)="setScope('ALL_STORES')"
            >
              All stores
            </button>
          </div>
        </div>

        @if (loading()) {
          <p class="loading" role="status">Loading stock</p>
        } @else if (loadError(); as text) {
          <div class="notice err" role="alert" data-testid="stock-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
            <button mat-stroked-button type="button" (click)="load(p.productId)">Try again</button>
          </div>
        } @else if (answer(); as a) {
          @if (a.empty) {
            <app-empty data-testid="stock-empty">No stock of this product yet.</app-empty>
          } @else {
            <div class="tbl-wrap" data-testid="stock-table">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Store</th>
                    <th scope="col" class="num">Available</th>
                    <th scope="col" class="num">In transit (in)</th>
                    <th scope="col" class="num">Pending return</th>
                    <th scope="col" class="num">Quarantine</th>
                    <th scope="col" class="num">Reorder level</th>
                    <th scope="col">Status</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of a.rows; track row.store?.storeId) {
                    <tr [attr.data-testid]="'store-row-' + row.store?.storeId">
                      <td>
                        @if (row.store; as s) {
                          <button
                            type="button"
                            class="linkbtn"
                            [attr.data-testid]="'open-batches-' + s.storeId"
                            (click)="openBatches(s.storeId, p.productId)"
                          >
                            {{ s.name }}
                          </button>
                        }
                      </td>
                      <td class="num">{{ qty(row.available) }}</td>
                      <td class="num">{{ qty(row.inTransit) }}</td>
                      <td class="num">{{ qty(row.pendingReturn) }}</td>
                      <td class="num">{{ qty(row.quarantine) }}</td>
                      <td class="num">{{ row.reorderLevel ?? '—' }}</td>
                      <td [attr.data-testid]="'status-' + row.store?.storeId">{{ status(row) }}</td>
                    </tr>
                  }
                </tbody>
                @if (showTotal()) {
                  <tfoot>
                    <tr data-testid="unit-total">
                      <td>Unit total</td>
                      <td class="num">{{ qty(a.total.available) }}</td>
                      <td class="num">{{ qty(a.total.inTransit) }}</td>
                      <td class="num">{{ qty(a.total.pendingReturn) }}</td>
                      <td class="num">{{ qty(a.total.quarantine) }}</td>
                      <td class="num">—</td>
                      <td>—</td>
                    </tr>
                  </tfoot>
                }
              </table>
            </div>
            <p class="note">Only Available stock can be issued, sold or consumed. Click a store to see batches and expiry.</p>
          }
        }
      }
    </section>
  `,
})
export class StoreStockSection {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  /** A store name was chosen: the Inventory page opens that store's batches for the product (79-3). */
  readonly batchesRequested = output<BatchFocus>();

  protected readonly product = signal<PickedProduct | null>(null);
  protected readonly scope = signal<Scope>('ALL_STORES');
  protected readonly answer = signal<ProductStockByStore | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly qty = stateQty;
  protected readonly hasWorkingStore = computed(() => this.context.workingStoreId() !== null);
  protected readonly showTotal = computed(() => this.scope() === 'ALL_STORES');
  protected readonly heading = computed(() => {
    const p = this.product();
    if (!p) {
      return '';
    }
    const name = `${p.name} ${p.strength}`.trim();
    return this.scope() === 'ALL_STORES'
      ? `${name} · all stores`
      : `${name} · ${this.context.workingStore()?.name ?? ''}`;
  });

  protected status(row: StoreStateRow): string {
    return storeStockStatusText(row.reorderLevel === null ? 'OK' : row.status);
  }

  protected choose(product: PickedProduct): void {
    this.product.set(product);
    this.load(product.productId);
  }

  protected setScope(scope: Scope): void {
    this.scope.set(scope);
    const p = this.product();
    if (p) {
      this.load(p.productId);
    }
  }

  protected load(productId: string): void {
    this.loading.set(true);
    this.loadError.set(null);
    const working = this.context.workingStoreId();
    const request =
      this.scope() === 'MY_STORE' && working
        ? this.api.productStockMyStore(working, productId)
        : this.api.productStockAllStores(productId);
    request.subscribe({
      next: (answer) => {
        this.answer.set(answer);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.answer.set(null);
        this.loading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }

  protected openBatches(storeId: string, productId: string): void {
    this.batchesRequested.emit({ storeId, productId });
  }
}
