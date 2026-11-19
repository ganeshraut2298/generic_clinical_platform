import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { catchError, debounceTime, distinctUntilChanged, of, switchMap, tap } from 'rxjs';
import { loadErrorMessage } from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import { ProductRow } from '../pharmacy.model';

/** A product the unit can hold stock of: its own record exists (adopted or own). */
export interface PickedProduct {
  productId: string;
  code: string;
  name: string;
  strength: string;
  form: string;
}

/**
 * Product search used by the stock screens (79-3, 79-4, the ledger and the adjustment form). It finds a product by
 * name, salt, alias, barcode or code (the server does the search, as in SAH-78-5) and lists only products the unit
 * has adopted or owns: a global product that is not adopted has no stock and is not offered.
 */
@Component({
  selector: 'app-product-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule],
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    ul.results {
      list-style: none;
      margin: 6px 0 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    ul.results button {
      width: 100%;
      min-height: 44px;
      text-align: left;
      padding: 8px 12px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
      font-weight: 600;
    }
    ul.results button:hover {
      border-color: var(--pri);
    }
    ul.results small {
      display: block;
      font-weight: 500;
      color: var(--ink-3);
    }
  `,
  template: `
    <mat-form-field appearance="outline" subscriptSizing="dynamic" class="wide">
      <mat-label>{{ label() }}</mat-label>
      <input
        matInput
        type="search"
        autocomplete="off"
        [formControl]="query"
        [placeholder]="placeholder()"
        data-testid="product-search"
      />
    </mat-form-field>
    @if (error(); as text) {
      <p class="note" role="alert" data-testid="product-search-error">{{ text }}</p>
    } @else if (searching()) {
      <p class="loading" role="status">Searching</p>
    } @else if (searched() && results().length === 0) {
      <p class="note" data-testid="product-search-empty">No product found.</p>
    }
    @if (results().length > 0) {
      <ul class="results" data-testid="product-results">
        @for (row of results(); track row.productId) {
          <li>
            <button type="button" [attr.data-testid]="'pick-' + row.code" (click)="pick(row)">
              {{ row.name }}
              <small>{{ row.strength }} · {{ row.form }} · {{ row.code }}</small>
            </button>
          </li>
        }
      </ul>
    }
  `,
})
export class ProductPickerComponent {
  private readonly api = inject(PharmacyApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly label = input('Product');
  readonly placeholder = input('Search by name, salt, code or barcode');
  readonly picked = output<PickedProduct>();

  protected readonly query = new FormControl('', { nonNullable: true });
  protected readonly results = signal<PickedProduct[]>([]);
  protected readonly searching = signal(false);
  protected readonly searched = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.query.valueChanges
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        tap((text) => {
          this.error.set(null);
          if (text.trim().length < 2) {
            this.results.set([]);
            this.searched.set(false);
          }
        }),
        switchMap((text) => {
          const q = text.trim();
          if (q.length < 2) {
            return of(null);
          }
          this.searching.set(true);
          return this.api.searchProducts({ q, page: 0, size: 10 }).pipe(
            catchError((error: unknown) => {
              this.error.set(loadErrorMessage(error));
              return of(null);
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((page) => {
        this.searching.set(false);
        if (page === null) {
          return;
        }
        this.searched.set(true);
        this.results.set(
          page.items
            .filter((row): row is ProductRow & { productId: string } => row.productId !== null)
            .map((row) => ({
              productId: row.productId,
              code: row.code,
              name: row.name,
              strength: row.strength,
              form: row.form,
            })),
        );
      });
  }

  protected pick(product: PickedProduct): void {
    this.picked.emit(product);
    this.results.set([]);
    this.searched.set(false);
    this.query.setValue('', { emitEvent: false });
  }
}
