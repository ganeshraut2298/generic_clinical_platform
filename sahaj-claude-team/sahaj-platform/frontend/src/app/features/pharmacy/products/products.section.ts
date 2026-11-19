import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subscription, debounceTime, distinctUntilChanged } from 'rxjs';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { sheetToast } from '../../../shared/ui/sheet-toast';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, saveErrorMessage } from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import { ProductRow } from '../pharmacy.model';
import { LOCAL_SETTINGS_CONFIG, LocalSettingsData, LocalSettingsDialog } from './local-settings.dialog';
import { OwnProductFormComponent, ProductFormResult } from './own-product-form.component';
import { PRODUCT_FORM_CONFIG, ProductFormData, ProductFormDialog } from './product-form.dialog';
import {
  SAMPLE_NOTE,
  hsnGstText,
  inUnitChip,
  productSubline,
  rowActions,
  saltLine,
  scheduleChip,
  uomText,
} from './product.util';

const PAGE_SIZE = 25;

/**
 * "Products" section of Inventory setup (mockup 16 tab "Products"; stories 78-5 and 78-6): the card
 * "Product master" with its legend chips, the search box "Searches global + own products" and the
 * table, then the owner's buttons. Reading is open to every reader of setup; Adopt, Local settings,
 * Edit and the card "Create own product" are drawn only when the context lists SETUP_EDIT (the server refuses
 * anyone else). The list loads in pages of 25; fewer than 2 characters shows the first page unfiltered.
 */
@Component({
  selector: 'app-products',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    IconComponent,
    EmptyStateComponent,
    OwnProductFormComponent,
  ],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .legend {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }
    .search {
      display: block;
      max-width: 560px;
    }
    table {
      min-width: 900px;
    }
    .sub {
      font-size: 0.76rem;
      color: var(--ink-3);
    }
    .sample {
      font-size: 0.76rem;
      color: var(--warn);
    }
    .acts {
      white-space: nowrap;
      text-align: right;
    }
    .acts button + button {
      margin-left: 6px;
    }
    .sr-only {
      position: absolute;
      left: -9999px;
    }
    .pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
      margin-top: 10px;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="pm-h" data-testid="product-master">
      <div class="panel-h">
        <h2 id="pm-h">Product master</h2>
        <div class="legend">
          <span class="pill p-blue">Global · shared by all units</span>
          <span class="pill p-good">Own · this unit only</span>
        </div>
      </div>

      <mat-form-field appearance="outline" subscriptSizing="dynamic" class="search">
        <mat-label>Search products</mat-label>
        <input
          matInput
          type="search"
          [formControl]="query"
          autocomplete="off"
          maxlength="60"
          data-testid="product-search"
        />
        <mat-hint>Searches global + own products</mat-hint>
      </mat-form-field>

      @if (actionError(); as text) {
        <div class="notice err" role="alert" data-testid="product-action-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }

      @if (loading()) {
        <p class="loading" role="status" data-testid="products-loading">Loading products</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="products-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
        <div class="actions">
          <button mat-stroked-button type="button" (click)="reload()">Try again</button>
        </div>
      } @else if (rows().length === 0) {
        <app-empty>
          @if (searching()) {
            <span data-testid="products-none-match">No products match. Try the salt name.</span>
          } @else if (canEdit()) {
            <span data-testid="products-empty">No products yet. Adopt a global product or create your own.</span>
          } @else {
            <span data-testid="products-empty">No products yet.</span>
          }
        </app-empty>
      } @else {
        <div class="tbl-wrap" data-testid="product-table">
          <table>
            <thead>
              <tr>
                <th scope="col">Source</th>
                <th scope="col">Product</th>
                <th scope="col">Salt · strength · form</th>
                <th scope="col">UOM (buy → issue)</th>
                <th scope="col">HSN · GST</th>
                <th scope="col">Flags</th>
                <th scope="col">In this unit</th>
                <th scope="col"><span class="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              @for (row of rows(); track row.code) {
                <tr [attr.data-testid]="'product-' + row.code">
                  <td>
                    @if (row.source === 'GLOBAL') {
                      <span class="pill p-blue">Global</span>
                    } @else {
                      <span class="pill p-good">Own</span>
                    }
                  </td>
                  <td>
                    <strong>{{ row.name }}</strong>
                    <div class="sub">{{ subline(row) }}</div>
                    @if (row.sample) {
                      <div class="sample" data-testid="sample-note">{{ sampleNote }}</div>
                    }
                  </td>
                  <td>{{ salt(row) }}</td>
                  <td>{{ uom(row) }}</td>
                  <td>{{ hsnGst(row) }}</td>
                  <td>
                    @if (flag(row); as chip) {
                      <span class="pill" [class]="chip.css">{{ chip.text }}</span>
                    } @else {
                      —
                    }
                  </td>
                  <td>
                    <span class="pill" [class]="unit(row).css">{{ unit(row).text }}</span>
                  </td>
                  <td class="acts">
                    @for (action of actions(row); track action) {
                      @switch (action) {
                        @case ('adopt') {
                          <button
                            mat-flat-button
                            type="button"
                            class="btn-sm"
                            [attr.data-testid]="'adopt-' + row.code"
                            [attr.aria-label]="'Adopt ' + row.name"
                            [disabled]="adopting() === row.code"
                            (click)="adopt(row)"
                          >
                            Adopt
                          </button>
                        }
                        @case ('local-settings') {
                          <button
                            mat-stroked-button
                            type="button"
                            class="btn-sm"
                            [attr.data-testid]="'local-' + row.code"
                            [attr.aria-label]="'Local settings of ' + row.name"
                            (click)="openLocal(row)"
                          >
                            Local settings
                          </button>
                        }
                        @case ('edit') {
                          <button
                            mat-stroked-button
                            type="button"
                            class="btn-sm"
                            [attr.data-testid]="'edit-' + row.code"
                            [attr.aria-label]="'Edit ' + row.name"
                            (click)="openEdit(row)"
                          >
                            Edit
                          </button>
                        }
                      }
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        <div class="pager" data-testid="product-pager">
          <span role="status" data-testid="product-range">{{ rangeText() }}</span>
          <button
            mat-stroked-button
            type="button"
            data-testid="products-prev"
            [disabled]="page() === 0"
            (click)="go(page() - 1)"
          >
            Previous
          </button>
          <button
            mat-stroked-button
            type="button"
            data-testid="products-next"
            [disabled]="!hasNext()"
            (click)="go(page() + 1)"
          >
            Next
          </button>
        </div>
      }

      <p class="note">
        Global fields (salt, strength, HSN, GST, schedule) are read-only. Local settings cover reorder level
        per store, rack, preferred vendor and alias.
      </p>
    </section>

    @if (canEdit()) {
      <section class="panel surf" aria-labelledby="np-h" data-testid="create-product">
        <h2 id="np-h">Create own product</h2>
        <p class="note">A product only this unit uses. Check the drug schedule before saving.</p>
        <app-own-product-form mode="create" layout="card" labelledBy="np-h" (finished)="onFinished($event)" />
      </section>
    }
  `,
})
export class ProductsSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly context = inject(PharmacyContextStore);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly canEdit = this.context.canEditSetup;
  protected readonly query = new FormControl('', { nonNullable: true });
  protected readonly rows = signal<ProductRow[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly adopting = signal<string | null>(null);

  /** True when a search of 2 or more characters is on: the empty answer then says "No products match". */
  protected readonly searching = computed(() => this.activeQuery().length >= 2);
  private readonly activeQuery = signal('');
  private request: Subscription | null = null;

  protected readonly hasNext = computed(() => (this.page() + 1) * PAGE_SIZE < this.total());
  protected readonly rangeText = computed(() => {
    const from = this.page() * PAGE_SIZE + 1;
    const to = Math.min(this.total(), (this.page() + 1) * PAGE_SIZE);
    return `Showing ${from}–${to} of ${this.total()}`;
  });

  protected readonly sampleNote = SAMPLE_NOTE;
  protected readonly subline = productSubline;
  protected readonly salt = saltLine;
  protected readonly uom = uomText;
  protected readonly hsnGst = hsnGstText;
  protected readonly flag = (row: ProductRow) => scheduleChip(row.schedule);
  protected readonly unit = (row: ProductRow) => inUnitChip(row.inUnit);
  protected readonly actions = (row: ProductRow) => rowActions(row, this.canEdit());

  ngOnInit(): void {
    this.query.valueChanges
      .pipe(debounceTime(250), distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.search());
    this.load(0, true);
  }

  /** A new search starts on the first page. */
  protected search(): void {
    this.load(0, true);
  }

  protected reload(): void {
    this.load(this.page(), true);
  }

  protected go(page: number): void {
    if (page >= 0) {
      this.load(page, true);
    }
  }

  private load(page: number, showLoading: boolean): void {
    const typed = this.query.value.trim();
    // Under 2 characters the server answers the first page unfiltered, so no q is sent.
    const q = typed.length >= 2 ? typed : undefined;
    this.activeQuery.set(typed);
    if (showLoading) {
      this.loading.set(true);
    }
    this.loadError.set(null);
    this.request?.unsubscribe();
    this.request = this.api.searchProducts({ q, page, size: PAGE_SIZE }).subscribe({
      next: (answer) => {
        this.rows.set(answer.items);
        this.total.set(answer.total);
        this.page.set(answer.page);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }

  protected adopt(row: ProductRow): void {
    if (!row.catalogueProductId || this.adopting()) {
      return;
    }
    this.actionError.set(null);
    this.adopting.set(row.code);
    this.api.adoptProduct(row.catalogueProductId).subscribe({
      next: () => {
        this.adopting.set(null);
        this.load(this.page(), false);
        this.toast('Product adopted');
      },
      error: (error: unknown) => {
        this.adopting.set(null);
        this.actionError.set(saveErrorMessage(error));
        this.load(this.page(), false);
      },
    });
  }

  protected openEdit(row: ProductRow): void {
    if (row.productId) {
      this.openForm({ mode: 'edit', productId: row.productId });
    }
  }

  private openForm(data: ProductFormData): void {
    this.actionError.set(null);
    this.dialog
      .open(ProductFormDialog, { ...PRODUCT_FORM_CONFIG, data })
      .afterClosed()
      .subscribe((result: ProductFormResult | null | undefined) => {
        if (result) {
          this.onFinished(result);
        }
      });
  }

  /** The card or the Edit sheet ended with a change: show the product chosen, or read the list again. */
  protected onFinished(result: ProductFormResult): void {
    this.actionError.set(null);
    if (result.kind === 'use-own' && result.code) {
      this.query.setValue(result.code);
      return;
    }
    this.load(this.page(), false);
    this.toast(
      result.kind === 'adopted' ? 'Product adopted' : result.kind === 'created' ? 'Product created' : 'Product saved',
    );
  }

  protected openLocal(row: ProductRow): void {
    if (!row.productId) {
      return;
    }
    const data: LocalSettingsData = { productId: row.productId, productName: row.name };
    this.actionError.set(null);
    this.dialog
      .open(LocalSettingsDialog, { ...LOCAL_SETTINGS_CONFIG, data })
      .afterClosed()
      .subscribe((saved: unknown) => {
        if (saved) {
          this.load(this.page(), false);
          this.toast('Local settings saved');
        }
      });
  }

  private toast(text: string): void {
    this.snackBar.open(text, undefined, sheetToast(3000));
  }
}
