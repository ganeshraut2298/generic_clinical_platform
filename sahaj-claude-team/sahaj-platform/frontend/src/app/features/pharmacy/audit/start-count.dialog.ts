import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { PickedProduct, ProductPickerComponent } from '../inventory/product-picker.component';
import { saveErrorMessage } from '../pharmacy-display.util';
import { StockAudit } from '../pharmacy-stockcount.model';
import { PharmacyApi } from '../pharmacy.api';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';

export interface StartCountData {
  storeId: string;
  storeName: string;
}

export const START_COUNT_CONFIG = {
  panelClass: 'sheet-panel',
  width: '560px',
  maxWidth: '100vw',
  ariaLabelledBy: 'start-count-title',
} as const;

const MAX_PRODUCTS = 200;

/**
 * "Start stock count" (story 79-17, OQ-79-26b and d): count every batch with Available stock in the store, or only the
 * products chosen (1 to 200). The system quantity is shown to nobody at this step. Another open count of the same store
 * is refused by the server ("A stock count is already open for this store.").
 */
@Component({
  selector: 'app-start-count',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, IconComponent, ProductPickerComponent],
  styles: [
    `
      .scopes {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .scopes label {
        display: flex;
        gap: 8px;
        align-items: center;
        min-height: 44px;
      }
      .picked {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
      }
      .picked button {
        min-height: 44px;
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="start-count-title">Start stock count</h2>
        <p>{{ data.storeName }}</p>
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>
    <div class="sheet-b">
      <fieldset class="scopes">
        <legend>What to count</legend>
        <label>
          <input type="radio" name="scope" data-testid="scope-all" [checked]="scope() === 'ALL_AVAILABLE'" (change)="scope.set('ALL_AVAILABLE')" />
          Every batch with Available stock
        </label>
        <label>
          <input type="radio" name="scope" data-testid="scope-products" [checked]="scope() === 'PRODUCTS'" (change)="scope.set('PRODUCTS')" />
          Only the products I choose
        </label>
      </fieldset>
      @if (scope() === 'PRODUCTS') {
        <app-product-picker label="Add a product" (picked)="add($event)" />
        <div class="picked" data-testid="start-products">
          @for (p of products(); track p.productId) {
            <button mat-stroked-button type="button" [attr.aria-label]="'Remove ' + p.name" (click)="remove(p.productId)">
              {{ p.name }} {{ p.strength }} ✕
            </button>
          }
        </div>
      }
      <p class="note">Blind count: nobody sees the system quantity while counting. The counter enters what is on the shelf.</p>
      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="start-count-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </div>
    <div class="sheet-f">
      <button mat-stroked-button type="button" (click)="close()">Cancel</button>
      <button mat-flat-button type="button" data-testid="start-count-go" [disabled]="saving() || !ready()" (click)="start()">
        Start count
      </button>
    </div>
  `,
})
export class StartCountDialog {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<StartCountDialog, StockAudit | null>>(MatDialogRef);
  protected readonly data = inject<StartCountData>(MAT_DIALOG_DATA);

  protected readonly scope = signal<'ALL_AVAILABLE' | 'PRODUCTS'>('ALL_AVAILABLE');
  protected readonly products = signal<PickedProduct[]>([]);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);

  private readonly clientKey = newClientKey();

  protected readonly ready = computed(() => this.scope() === 'ALL_AVAILABLE' || this.products().length > 0);

  protected add(product: PickedProduct): void {
    this.products.update((list) =>
      list.some((p) => p.productId === product.productId) || list.length >= MAX_PRODUCTS ? list : [...list, product],
    );
  }

  protected remove(productId: string): void {
    this.products.update((list) => list.filter((p) => p.productId !== productId));
  }

  protected start(): void {
    if (this.saving() || !this.ready()) {
      return;
    }
    this.formError.set(null);
    this.saving.set(true);
    this.api
      .startStockAudit(this.data.storeId, {
        clientKey: this.clientKey,
        scope: this.scope(),
        productIds: this.scope() === 'PRODUCTS' ? this.products().map((p) => p.productId) : null,
      })
      .subscribe({
        next: (audit) => {
          this.saving.set(false);
          this.ref.close(audit);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
        },
      });
  }

  protected close(): void {
    this.ref.close(null);
  }
}
