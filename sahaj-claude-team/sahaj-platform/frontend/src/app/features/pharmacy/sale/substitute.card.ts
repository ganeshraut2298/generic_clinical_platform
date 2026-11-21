import { ChangeDetectionStrategy, Component, OnInit, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { rateText } from '../opening-stock/opening-stock.util';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, problemSlug } from '../pharmacy-display.util';
import { ActiveReason, ProductRef } from '../pharmacy-inventory.model';
import { SubstituteOption, SubstituteOptions, SubstitutionInput } from '../pharmacy-sale.model';
import { PharmacyApi } from '../pharmacy.api';

export interface SubstitutePicked {
  product: ProductRef;
  substitution: SubstitutionInput;
}

/**
 * "Safe substitute" (SAH-80-7; mockup 02, shortcut F6). Only exact equivalents are listed: the same salt, strength and
 * form, and the same schedule, with stock in this store. The pharmacist picks an option, a reason chip and ticks
 * "Patient agrees"; without both nothing changes. The server decides the options and refuses a cashier.
 */
@Component({
  selector: 'app-substitute-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    table {
      min-width: 560px;
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }
    .chips button {
      min-height: 44px;
      padding: 0 14px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
      font: inherit;
      cursor: pointer;
    }
    .chips button[aria-pressed='true'] {
      border: 2px solid var(--pri);
      background: var(--pri-soft);
      color: var(--pri-text);
      font-weight: 700;
    }
    textarea {
      min-height: 56px;
      padding: 6px 10px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--glass-hi);
      font: inherit;
    }
    label.agree {
      display: flex;
      align-items: center;
      gap: 8px;
      min-height: 44px;
    }
    .err {
      color: var(--crit);
      font-size: 0.8rem;
    }
    .acts {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
  `,
  template: `
    <h4>Safe substitute</h4>
    @if (error(); as text) {
      <p class="err" role="alert" data-testid="sub-load-error">{{ text }}</p>
    } @else if (options(); as o) {
      <p data-testid="sub-sentence">{{ o.sentence }}</p>
      @if (o.items.length === 0) {
        <p class="note" data-testid="sub-none">No exact equivalent in stock.</p>
      } @else {
        <div class="tbl-wrap">
          <table data-testid="sub-table">
            <thead>
              <tr>
                <th scope="col">Option</th>
                <th scope="col" class="num">MRP/{{ o.product.issueUnit }}</th>
                <th scope="col" class="num">Stock</th>
                <th scope="col" class="num">Margin</th>
                <th scope="col"><span class="sr-only">Choose</span></th>
              </tr>
            </thead>
            <tbody>
              @for (item of o.items; track item.product.productId) {
                <tr [attr.data-testid]="'sub-row-' + item.product.productId">
                  <td>
                    {{ item.product.name }} {{ item.product.strength }}
                    @if (chosenId() === item.product.productId) {
                      <span class="pill p-good">selected</span>
                    }
                  </td>
                  <td class="num">{{ rate(item.mrp) }}</td>
                  <td class="num">{{ item.availableQty ?? '—' }}</td>
                  <td class="num">{{ item.marginPerUnit === null ? '—' : rate(item.marginPerUnit) }}</td>
                  <td>
                    <button mat-stroked-button type="button" [attr.data-testid]="'sub-pick-' + item.product.productId" (click)="choose(item)">
                      Choose
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        @if (chosen(); as c) {
          <p class="chips" role="status" data-testid="sub-checks">
            <span class="pill p-good">Salt ✓</span>
            <span class="pill p-good">Strength {{ c.checks.strength }} ✓</span>
            <span class="pill p-good">Form: {{ c.checks.form }} ✓</span>
          </p>
          <div class="chips" role="group" aria-label="Substitution reason">
            @for (r of reasons(); track r.reasonKey) {
              <button type="button" [attr.aria-pressed]="reasonKey() === r.reasonKey" [attr.data-testid]="'sub-reason-' + r.reasonKey" (click)="reasonKey.set(r.reasonKey)">
                {{ r.label }}
              </button>
            }
          </div>
          @if (needsNote()) {
            <textarea aria-label="Note" maxlength="300" data-testid="sub-note" [value]="note()" (input)="note.set($any($event.target).value)"></textarea>
          }
          <label class="agree">
            <input type="checkbox" data-testid="sub-agrees" [checked]="agrees()" (change)="agrees.set($any($event.target).checked)" />
            Patient agrees
          </label>
          <p class="note">Substitution reason and patient consent are stored on the bill.</p>
          @if (formError(); as text) {
            <p class="err" role="alert" data-testid="sub-error">{{ text }}</p>
          }
          <div class="acts">
            <button mat-flat-button type="button" data-testid="sub-confirm" (click)="confirm(c)">Use this substitute</button>
          </div>
        }
      }
    } @else {
      <p class="loading" role="status">Loading</p>
    }
    <div class="acts">
      <button mat-stroked-button type="button" data-testid="sub-close" (click)="closed.emit()">Close</button>
    </div>
  `,
})
export class SubstituteCard implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  readonly product = input.required<ProductRef>();
  readonly reasons = input.required<ActiveReason[]>();
  readonly picked = output<SubstitutePicked>();
  readonly closed = output<void>();

  protected readonly options = signal<SubstituteOptions | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly chosen = signal<SubstituteOption | null>(null);
  protected readonly reasonKey = signal('');
  protected readonly note = signal('');
  protected readonly agrees = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly rate = rateText;

  protected chosenId(): string | null {
    return this.chosen()?.product.productId ?? null;
  }

  protected needsNote(): boolean {
    return this.reasons().find((r) => r.reasonKey === this.reasonKey())?.needsNote === true;
  }

  ngOnInit(): void {
    const storeId = this.context.workingStoreId();
    if (!storeId) {
      return;
    }
    this.api.substituteOptions(storeId, this.product().productId).subscribe({
      next: (answer) => this.options.set(answer),
      error: (e: unknown) =>
        this.error.set(problemSlug(e) === 'pharmacist-required' ? 'A pharmacist must substitute.' : loadErrorMessage(e)),
    });
  }

  protected choose(item: SubstituteOption): void {
    this.chosen.set(item);
    this.formError.set(null);
  }

  protected confirm(item: SubstituteOption): void {
    const length = this.note().trim().length;
    if (this.reasonKey() === '' || !this.agrees() || (this.needsNote() && (length < 4 || length > 300))) {
      this.formError.set('Choose a reason and confirm the patient agrees.');
      return;
    }
    this.picked.emit({
      product: item.product,
      substitution: { reasonKey: this.reasonKey(), note: this.needsNote() ? this.note().trim() : null, patientAgrees: true },
    });
  }
}
