import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { PickedProduct, ProductPickerComponent } from '../inventory/product-picker.component';
import { ApiError } from '../../../core/http/problem-detail';
import { saveErrorMessage } from '../pharmacy-display.util';
import { Indent, IndentLineInput, IndentPriority } from '../pharmacy-indent.model';
import { PharmacyApi } from '../pharmacy.api';
import { SHEET_COLUMN_STYLES } from '../shared/sheet-column.styles';
import { COUNTING_IN_PROGRESS } from './indent.util';

export interface IndentFormData {
  storeId: string;
  storeName: string;
  /** "Acting as <name> · mapped to <store> · can raise indents for <store>". */
  actingAs: string;
  /** The issuing store, when it is known (a saved draft). */
  toStoreName: string | null;
  /** A saved draft of the caller to change; absent for "+ New indent". */
  indent?: Indent | null;
}

export const INDENT_FORM_CONFIG = {
  panelClass: 'sheet-panel',
  width: '860px',
  maxWidth: '100vw',
  ariaLabelledBy: 'indent-form-title',
} as const;

export const INDENT_LINE_LIMIT = 200;

interface FormLine {
  productId: string;
  name: string;
  detail: string;
  schedule: string | null;
  qty: string;
  /** This store's Available quantity of the product; null while it is loading; 'counting' for a blind caller. */
  stock: number | 'counting' | null;
  error: string | null;
}

/**
 * "Raise indent" (story 79-9, mockup 10 step 1): the line "Acting as ...", From and To (both read-only), Priority and the
 * table Item, "<store> stock", Requested. "Save draft" keeps the indent without a number; "Submit for HOD approval" takes
 * its number and sends it to the rule of the unit. Not built (Later): the columns "Avg use / day" and "Suggested". One key
 * per opened sheet keeps a double tap to one indent. A failed save keeps what was typed.
 */
@Component({
  selector: 'app-indent-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, MatFormFieldModule, MatSelectModule, IconComponent, ProductPickerComponent],
  styles: [
    `
      .meta {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
        gap: 12px;
      }
      .ro {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .ro dt {
        font-size: 0.78rem;
        font-weight: 600;
        color: var(--ink-3);
      }
      .ro dd {
        margin: 0;
        min-height: 44px;
        display: flex;
        align-items: center;
      }
      .scroll {
        overflow-x: auto;
      }
      table {
        min-width: 560px;
      }
      .num {
        text-align: right;
        white-space: nowrap;
      }
      .sub {
        font-size: 0.78rem;
        color: var(--ink-3);
      }
      input.cell {
        min-height: 44px;
        width: 100%;
        min-width: 80px;
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
        <h2 id="indent-form-title">{{ data.indent ? 'Edit draft indent' : 'Raise indent' }}</h2>
        <p data-testid="indent-acting-as">{{ data.actingAs }}</p>
      </div>
      <button mat-icon-button type="button" aria-label="Close" (click)="close()">
        <app-icon name="x" />
      </button>
    </div>

    <div class="sheet-b">
      <dl class="meta">
        <div class="ro">
          <dt>From (requesting store)</dt>
          <dd data-testid="indent-from">{{ data.storeName }}</dd>
        </div>
        <div class="ro">
          <dt>To (issuing store)</dt>
          <dd data-testid="indent-to">{{ data.toStoreName ?? 'Shown after you save the draft' }}</dd>
        </div>
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Priority</mat-label>
          <mat-select data-testid="f-indent-priority" [value]="priority()" (selectionChange)="priority.set($event.value)">
            <mat-option value="ROUTINE">Routine</mat-option>
            <mat-option value="URGENT">Urgent</mat-option>
          </mat-select>
        </mat-form-field>
      </dl>

      @if (lines().length < limit) {
        <app-product-picker label="Add a product" (picked)="add($event)" />
      }
      @if (duplicate(); as text) {
        <p class="row-err" role="alert" data-testid="indent-duplicate">{{ text }}</p>
      }

      @if (lines().length === 0) {
        <p class="sub" data-testid="indent-no-lines">
          @if (showLinesError()) {
            Add at least one item
          } @else {
            No items yet. Search for a product to add it.
          }
        </p>
      } @else {
        <div class="scroll">
          <table data-testid="indent-lines">
            <thead>
              <tr>
                <th scope="col">Item</th>
                <th scope="col" class="num">{{ data.storeName }} stock</th>
                <th scope="col" class="num">Requested</th>
                <th scope="col"><span class="sr-only">Remove</span></th>
              </tr>
            </thead>
            <tbody>
              @for (line of lines(); track line.productId; let i = $index) {
                <tr [attr.data-testid]="'indent-line-' + i">
                  <td>
                    {{ line.name }}
                    @if (line.schedule) {
                      <span class="pill p-warn" [attr.data-testid]="'indent-schedule-' + i">Schedule {{ line.schedule }}</span>
                    }
                    <div class="sub">{{ line.detail }}</div>
                  </td>
                  <td class="num" [attr.data-testid]="'indent-stock-' + i">{{ stockText(line) }}</td>
                  <td class="num">
                    <input
                      class="cell"
                      inputmode="numeric"
                      autocomplete="off"
                      [value]="line.qty"
                      [attr.aria-label]="'Requested quantity of ' + line.name"
                      [attr.data-testid]="'indent-qty-' + i"
                      (input)="setQty(i, $any($event.target).value)"
                    />
                    @if (line.error; as text) {
                      <div class="row-err" role="alert">{{ text }}</div>
                    }
                  </td>
                  <td>
                    <button
                      mat-icon-button
                      type="button"
                      [attr.aria-label]="'Remove ' + line.name"
                      [attr.data-testid]="'indent-remove-' + i"
                      (click)="remove(i)"
                    >
                      <app-icon name="x" />
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }

      @if (formError(); as text) {
        <div class="notice err" role="alert" data-testid="indent-form-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </div>

    <div class="sheet-f">
      @if (data.indent) {
        <button mat-stroked-button type="button" data-testid="indent-discard" [disabled]="saving()" (click)="discard()">
          Discard draft
        </button>
      }
      <button mat-stroked-button type="button" data-testid="indent-save-draft" [disabled]="saving()" (click)="save(false)">
        Save draft
      </button>
      <button mat-flat-button type="button" data-testid="indent-submit" [disabled]="saving()" (click)="save(true)">
        Submit for HOD approval
      </button>
    </div>
  `,
})
export class IndentFormDialog {
  private readonly api = inject(PharmacyApi);
  private readonly ref = inject<MatDialogRef<IndentFormDialog, Indent | null>>(MatDialogRef);
  protected readonly data = inject<IndentFormData>(MAT_DIALOG_DATA);
  protected readonly limit = INDENT_LINE_LIMIT;

  protected readonly priority = signal<IndentPriority>(this.data.indent?.priority ?? 'ROUTINE');
  protected readonly lines = signal<FormLine[]>(
    (this.data.indent?.lines ?? []).map((l) => ({
      productId: l.product.productId,
      name: l.product.name,
      detail: `${l.product.strength} · ${l.product.form} · per ${l.product.issueUnit}`,
      schedule: l.product.schedule === 'NONE' ? null : l.product.schedule,
      qty: String(l.requestedQty),
      stock: l.requestingStoreAvailable === null ? 'counting' : l.requestingStoreAvailable,
      error: null,
    })),
  );
  protected readonly duplicate = signal<string | null>(null);
  protected readonly showLinesError = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly count = computed(() => this.lines().length);

  private draft: Indent | null = this.data.indent ?? null;
  private readonly createKey = newClientKey();
  private readonly submitKey = newClientKey();

  protected stockText(line: FormLine): string {
    if (line.stock === 'counting') {
      return COUNTING_IN_PROGRESS;
    }
    return line.stock === null ? '—' : String(line.stock);
  }

  protected add(picked: PickedProduct): void {
    this.duplicate.set(null);
    if (this.lines().some((l) => l.productId === picked.productId)) {
      this.duplicate.set('Already on this indent');
      return;
    }
    this.showLinesError.set(false);
    this.lines.update((list) => [
      ...list,
      {
        productId: picked.productId,
        name: picked.name,
        detail: `${picked.strength} · ${picked.form}`,
        schedule: null,
        qty: '',
        stock: null,
        error: null,
      },
    ]);
    this.api.productStock(this.data.storeId, picked.productId).subscribe({
      next: (stock) => {
        const available = stock.batches.filter((b) => b.state === 'AVAILABLE');
        const blind = available.some((b) => b.qty === null);
        const total = available.reduce((sum, b) => sum + (b.qty ?? 0), 0);
        this.lines.update((list) =>
          list.map((l) =>
            l.productId === picked.productId
              ? {
                  ...l,
                  stock: blind ? 'counting' : total,
                  schedule: stock.product.schedule === 'NONE' ? null : stock.product.schedule,
                  detail: `${stock.product.strength} · ${stock.product.form} · per ${stock.product.issueUnit}`,
                }
              : l,
          ),
        );
      },
      error: () => undefined,
    });
  }

  protected setQty(index: number, value: string): void {
    this.lines.update((list) => list.map((l, i) => (i === index ? { ...l, qty: value, error: null } : l)));
  }

  protected remove(index: number): void {
    this.lines.update((list) => list.filter((_, i) => i !== index));
  }

  protected close(): void {
    this.ref.close(null);
  }

  private validate(): IndentLineInput[] | null {
    this.showLinesError.set(this.lines().length === 0);
    let bad = false;
    const checked = this.lines().map((l) => {
      const text = l.qty.trim();
      const ok = /^[0-9]+$/.test(text) && Number(text) >= 1 && Number(text) <= 999999;
      bad = bad || !ok;
      return { ...l, error: ok ? null : 'Enter a whole number from 1 to 999999' };
    });
    this.lines.set(checked);
    if (bad || checked.length === 0) {
      return null;
    }
    return checked.map((l) => ({ productId: l.productId, requestedQty: Number(l.qty.trim()) }));
  }

  /** Saves the draft; with `submit` it is then submitted, which takes the indent number. */
  protected save(submit: boolean): void {
    if (this.saving()) {
      return;
    }
    this.formError.set(null);
    const lines = this.validate();
    if (lines === null) {
      return;
    }
    this.saving.set(true);
    const current = this.draft;
    const request = current
      ? this.api.updateIndent(this.data.storeId, current.id, {
          version: current.version,
          priority: this.priority(),
          lines,
        })
      : this.api.createIndent(this.data.storeId, {
          clientKey: this.createKey,
          priority: this.priority(),
          lines,
        });
    request.subscribe({
      next: (saved) => {
        this.draft = saved;
        if (submit) {
          this.submitDraft(saved);
        } else {
          this.saving.set(false);
          this.ref.close(saved);
        }
      },
      error: (error: unknown) => this.fail(error),
    });
  }

  private submitDraft(saved: Indent): void {
    this.api
      .submitIndent(this.data.storeId, saved.id, { clientKey: this.submitKey, version: saved.version })
      .subscribe({
        next: (done) => {
          this.saving.set(false);
          this.ref.close(done);
        },
        error: (error: unknown) => {
          this.fail(error, ' The draft is kept in the list.');
        },
      });
  }

  protected discard(): void {
    const current = this.draft;
    if (!current || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.api.discardIndent(this.data.storeId, current.id, { version: current.version }).subscribe({
      next: (done) => {
        this.saving.set(false);
        this.ref.close(done);
      },
      error: (error: unknown) => this.fail(error),
    });
  }

  private fail(error: unknown, suffix = ''): void {
    this.saving.set(false);
    if (error instanceof ApiError && error.fieldErrors.length > 0) {
      this.formError.set(error.fieldErrors.map((f) => f.message).join(' ') + suffix);
      return;
    }
    this.formError.set(saveErrorMessage(error) + suffix);
  }
}

