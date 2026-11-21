import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { ProductPickerComponent, PickedProduct } from '../inventory/product-picker.component';
import { expiryMonthYear } from '../inventory/inventory.util';
import { loadErrorMessage } from '../pharmacy-display.util';
import { ProductStock, StockBatchRow, StockState } from '../pharmacy-inventory.model';
import { PharmacyApi } from '../pharmacy.api';
import { blindQty } from './stockuse.util';

/**
 * "Item · batch" of mockup 11 (Return to Central Store, Report damaged / expired): the person finds the product, then
 * picks one of its batches in the allowed stock states. The quantity of a batch the person is blind to (a product on the
 * store's open audit) is a dash and the server still checks the limit (rule 24).
 */
@Component({
  selector: 'app-batch-pick',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatFormFieldModule, MatSelectModule, ProductPickerComponent],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .err {
      color: var(--crit);
    }
  `,
  template: `
    <app-product-picker label="Item" (picked)="choose($event)" />
    @if (error(); as text) {
      <p class="err" role="alert" data-testid="batch-pick-error">{{ text }}</p>
    }
    @if (stock(); as s) {
      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>Batch</mat-label>
        <mat-select placeholder="Choose a batch" [value]="selected()" data-testid="f-batch" (selectionChange)="pick($event.value)">
          @for (row of rows(); track key(row)) {
            <mat-option [value]="key(row)">
              {{ s.product.name }} · {{ row.batch.batchNo }} · {{ month(row.batch.expiryDate) }} · {{ stateText(row) }}
              {{ qty(row.qty) }}
            </mat-option>
          } @empty {
            <mat-option disabled>No batch to choose</mat-option>
          }
        </mat-select>
      </mat-form-field>
    }
  `,
})
export class BatchPickComponent {
  private readonly api = inject(PharmacyApi);

  readonly storeId = input.required<string>();
  /** The stock states a batch may be in to be offered. */
  readonly states = input<StockState[]>(['AVAILABLE']);
  /** Expired batches are offered only when true (a damage report may start from Quarantine). */
  readonly allowExpired = input(false);
  readonly picked = output<StockBatchRow>();

  protected readonly stock = signal<ProductStock | null>(null);
  protected readonly selected = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly month = expiryMonthYear;
  protected readonly qty = blindQty;

  protected rows(): StockBatchRow[] {
    return (this.stock()?.batches ?? []).filter(
      (r) => this.states().includes(r.state) && (this.allowExpired() || r.daysToExpiry >= 0),
    );
  }

  protected key(row: StockBatchRow): string {
    return `${row.batch.batchId}:${row.state}`;
  }

  protected stateText(row: StockBatchRow): string {
    return row.state === 'AVAILABLE' ? 'Available' : row.state === 'QUARANTINE' ? 'Quarantine' : row.state;
  }

  protected choose(product: PickedProduct): void {
    this.error.set(null);
    this.selected.set(null);
    this.api.productStock(this.storeId(), product.productId).subscribe({
      next: (stock) => this.stock.set(stock),
      error: (error: unknown) => {
        this.stock.set(null);
        this.error.set(loadErrorMessage(error));
      },
    });
  }

  protected pick(key: string): void {
    this.selected.set(key);
    const row = this.rows().find((r) => this.key(r) === key);
    if (row) {
      this.picked.emit(row);
    }
  }

  /** Forget the batch after a save, so that the next entry starts clean. */
  reset(): void {
    this.stock.set(null);
    this.selected.set(null);
  }
}
