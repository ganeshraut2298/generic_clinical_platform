import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { ReasonChipsComponent } from '../indent/reason-chips.component';
import { PickedProduct, ProductPickerComponent } from '../inventory/product-picker.component';
import { saveErrorMessage } from '../pharmacy-display.util';
import { ActiveReason } from '../pharmacy-inventory.model';
import { Transfer } from '../pharmacy-stockuse.model';
import { PharmacyApi } from '../pharmacy.api';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';

export interface TransferFormData {
  /** The store the person works in: it is the sending or the receiving store of the transfer (OQ-79-20b). */
  storeId: string;
  storeName: string;
  /** Stores the person may choose as the other side: the stores of the context, and the unit's stores for a person with setup access. */
  stores: { id: string; name: string; central: boolean }[];
}

export const TRANSFER_FORM_CONFIG = {
  panelClass: 'sheet-panel',
  width: '760px',
  maxWidth: '100vw',
  ariaLabelledBy: 'transfer-form-title',
} as const;

interface FormLine {
  product: PickedProduct;
  qty: string;
  error: string | null;
}

const LINE_LIMIT = 200;

/**
 * "+ New transfer" (story 79-15, mockup 12): From store, To store, Reason (Non-moving here, needed there; Urgent
 * shortage; Store closure) and the items with a quantity. Never to the Central Store ("Use Return to Central Store").
 * The path store is the sending or the receiving store. Nothing moves until the sending store dispatches. The quantity
 * is checked against the sending store's Available stock by the server, which answers with the figure.
 */
@Component({
  selector: 'app-transfer-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, MatFormFieldModule, MatSelectModule, IconComponent, ProductPickerComponent, ReasonChipsComponent],
  styles: [
    `
      table {
        min-width: 560px;
      }
      .num {
        text-align: right;
      }
      input.cell {
        min-height: 44px;
        width: 100px;
        padding: 6px 8px;
        border: 1px solid var(--line);
        border-radius: var(--r-sm);
        background: var(--glass-hi);
        font: inherit;
      }
      .row-err {
        color: var(--crit);
        font-size: 0.78rem;
      }
    `,
    SHEET_COLUMN_STYLES,
  ],
  template: `
    <div class="sheet-h">
      <div>
        <h2 id="transfer-form-title">New transfer</h2>
        <p>Store to store and branch to branch</p>
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>
    <div class="sheet-b">
      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>From store</mat-label>
        <mat-select data-testid="f-from" [value]="fromId()" (selectionChange)="fromId.set($event.value)">
          @for (s of options(); track s.id) {
            <mat-option [value]="s.id">{{ s.name }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>To store</mat-label>
        <mat-select data-testid="f-to" [value]="toId()" (selectionChange)="toId.set($event.value)">
          @for (s of options(); track s.id) {
            @if (!s.central) {
              <mat-option [value]="s.id">{{ s.name }}</mat-option>
            }
          }
        </mat-select>
      </mat-form-field>
      @if (storeError(); as text) {
        <p class="row-err" role="alert" data-testid="transfer-store-error">{{ text }}</p>
      }
      <span class="field-label">Reason</span>
      <app-reason-chips
        [reasons]="reasons()"
        [value]="reasonKey()"
        label="Reason for the transfer"
        testIdPrefix="transfer-reason-"
        (chosen)="reasonKey.set($event.reasonKey)"
      />
      @if (reasonError(); as text) {
        <p class="row-err" role="alert" data-testid="transfer-reason-error">{{ text }}</p>
      }
      <app-product-picker label="Add an item" (picked)="add($event)" />
      @if (lines().length > 0) {
        <div class="tbl-wrap">
          <table data-testid="transfer-lines">
            <thead>
              <tr>
                <th scope="col">Item</th>
                <th scope="col" class="num">Transfer qty</th>
              </tr>
            </thead>
            <tbody>
              @for (line of lines(); track line.product.productId; let n = $index) {
                <tr [attr.data-testid]="'transfer-line-' + n">
                  <td>{{ line.product.name }} {{ line.product.strength }}</td>
                  <td class="num">
                    <input
                      class="cell"
                      inputmode="numeric"
                      [value]="line.qty"
                      [attr.aria-label]="'Transfer quantity of ' + line.product.name"
                      [attr.data-testid]="'transfer-qty-' + n"
                      (input)="setQty(n, $any($event.target).value)"
                    />
                    <button mat-button type="button" [attr.aria-label]="'Remove ' + line.product.name" (click)="remove(n)">Remove</button>
                    @if (line.error; as text) {
                      <div class="row-err" role="alert">{{ text }}</div>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      } @else {
        <p class="note" data-testid="transfer-no-lines">No items yet. Add the items to move.</p>
      }
      <p class="note">Receiving works exactly like an indent: any mapped user at the receiving store accepts or rejects with a reason.</p>
      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="transfer-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </div>
    <div class="sheet-f">
      <button mat-stroked-button type="button" (click)="close()">Cancel</button>
      <button mat-flat-button type="button" data-testid="transfer-submit" [disabled]="saving()" (click)="submit()">
        Request transfer
      </button>
    </div>
  `,
})
export class TransferFormDialog implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<TransferFormDialog, Transfer | null>>(MatDialogRef);
  protected readonly data = inject<TransferFormData>(MAT_DIALOG_DATA);

  protected readonly fromId = signal('');
  protected readonly toId = signal('');
  protected readonly reasonKey = signal<string | null>(null);
  protected readonly reasons = signal<ActiveReason[]>([]);
  protected readonly lines = signal<FormLine[]>([]);
  protected readonly storeError = signal<string | null>(null);
  protected readonly reasonError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);

  private readonly clientKey = newClientKey();

  protected readonly options = computed(() => this.data.stores);

  ngOnInit(): void {
    this.fromId.set(this.data.storeId);
    this.api.activeReasons(['transfer']).subscribe({
      next: (answer) => this.reasons.set(answer.lists.find((l) => l.listKey === 'transfer')?.reasons ?? []),
      error: () => this.reasons.set([]),
    });
  }

  protected add(product: PickedProduct): void {
    this.lines.update((list) =>
      list.some((l) => l.product.productId === product.productId) || list.length >= LINE_LIMIT
        ? list
        : [...list, { product, qty: '', error: null }],
    );
  }

  protected remove(index: number): void {
    this.lines.update((list) => list.filter((_, i) => i !== index));
  }

  protected setQty(index: number, qty: string): void {
    this.lines.update((list) => list.map((l, i) => (i === index ? { ...l, qty, error: null } : l)));
  }

  protected submit(): void {
    if (this.saving()) {
      return;
    }
    this.formError.set(null);
    this.storeError.set(null);
    this.reasonError.set(null);
    const from = this.fromId();
    const to = this.toId();
    if (!from || !to) {
      this.storeError.set('Choose both stores.');
    } else if (from === to) {
      this.storeError.set('Choose two different stores.');
    } else if (from !== this.data.storeId && to !== this.data.storeId) {
      this.storeError.set(`Your store, ${this.data.storeName}, must be the sending or the receiving store.`);
    } else if (this.data.stores.find((s) => s.id === to)?.central) {
      this.storeError.set('Use Return to Central Store.');
    }
    if (!this.reasonKey()) {
      this.reasonError.set('Choose a reason.');
    }
    let bad = false;
    const checked = this.lines().map((l) => {
      const error = /^\d{1,6}$/.test(l.qty.trim()) && Number(l.qty.trim()) >= 1 ? null : 'Enter a whole number from 1';
      bad = bad || error !== null;
      return { ...l, error };
    });
    this.lines.set(checked);
    if (this.storeError() || this.reasonError() || bad) {
      return;
    }
    if (checked.length === 0) {
      this.formError.set('Add at least one item.');
      return;
    }
    this.saving.set(true);
    this.api
      .createTransfer(this.data.storeId, {
        clientKey: this.clientKey,
        fromStoreId: from,
        toStoreId: to,
        reasonKey: this.reasonKey() ?? '',
        note: null,
        lines: checked.map((l) => ({ productId: l.product.productId, quantity: Number(l.qty.trim()) })),
      })
      .subscribe({
        next: (transfer) => {
          this.saving.set(false);
          this.ref.close(transfer);
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
