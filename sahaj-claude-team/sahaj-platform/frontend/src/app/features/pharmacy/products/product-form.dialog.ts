import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { IconComponent } from '../../../shared/icon/icon.component';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import { OwnProductFormComponent, ProductFormResult } from './own-product-form.component';

export type { ProductFormResult } from './own-product-form.component';

/** The Edit sheet shows one own product. Creating is the inline card of the Products tab. */
export interface ProductFormData {
  mode: 'edit';
  productId: string;
}

export const PRODUCT_FORM_CONFIG = {
  panelClass: 'sheet-panel',
  width: '720px',
  maxWidth: '100vw',
  ariaLabelledBy: 'product-form-title',
} as const;

/**
 * "Edit" sheet of an own product (story 78-6). The mockup shows only the "Edit" button, so the sheet
 * holds the same form as the card "Create own product" (one component, no copy) in its sheet layout.
 */
@Component({
  selector: 'app-product-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, OwnProductFormComponent],
  styles: [SHEET_COLUMN_STYLES],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="product-form-title">Edit own product</h2>
        <p>The code and the source cannot be changed.</p>
      </div>
      <button mat-icon-button type="button" (click)="close()" aria-label="Close">
        <app-icon name="x" />
      </button>
    </div>
    <app-own-product-form
      mode="edit"
      layout="sheet"
      labelledBy="product-form-title"
      [productId]="data.productId"
      (finished)="finish($event)"
      (cancelled)="close()"
    />
  `,
})
export class ProductFormDialog {
  private readonly ref = inject<MatDialogRef<ProductFormDialog, ProductFormResult | null>>(MatDialogRef);
  protected readonly data = inject<ProductFormData>(MAT_DIALOG_DATA);

  protected close(): void {
    this.ref.close(null);
  }

  protected finish(result: ProductFormResult): void {
    this.ref.close(result);
  }
}
