import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { newClientKey } from '../../../shared/client-key';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EMR_COMPONENT_CONTEXT } from '../emr-context';
import { EmrIpdOrderOptions, EmrIpdOrderRow, EmrIpdOrdersValue } from '../emr-finish.model';
import { EmrApi } from '../emr.api';

export const IPD_ORDERS_HINT = 'Checks allergies, duplicates and interactions. A pharmacist checks every order before the first dose.';
export const IPD_ORDERS_MAX = 20;

export function ipdRowsOf(value: unknown): EmrIpdOrderRow[] {
  const v = value as Partial<EmrIpdOrdersValue> | null;
  return v && v.component === 'IPD_ORDERS' && Array.isArray(v.rows) ? v.rows : [];
}

export function withIpdRows(rows: EmrIpdOrderRow[]): EmrIpdOrdersValue {
  return { component: 'IPD_ORDERS', schemaVersion: 1, rows };
}

/**
 * The round's Prescription (SAH-67-1; ADR-025 D8): new medicines are picked from the IPD catalogue
 * (the same list as the IPD EMR sheet) with one of its frequencies. Nothing is ordered here: each
 * row is checked at "Check before completing" and placed by the IPD order path when the note is
 * signed. A row already ordered cannot be changed or removed. The EMR holds no medicine-order rule
 * of its own (OQ-43-24). Medicine names are clinical data: never logged or stored in the browser.
 */
@Component({
  selector: 'app-ipd-orders',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    h3 {
      margin: 0 0 4px;
      font-size: 1rem;
    }
    .star {
      color: var(--crit);
    }
    .picker {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: 10px;
      margin: 10px 0;
    }
    .picker label {
      display: flex;
      flex-direction: column;
      gap: 4px;
      font-size: 0.8rem;
      font-weight: 600;
      color: var(--ink-2);
    }
    .picker select {
      min-height: 44px;
      min-width: 200px;
    }
    .add {
      min-height: 44px;
      padding: 0 16px;
      border: 1px solid var(--pri);
      border-radius: var(--r-sm);
      background: var(--pri);
      color: var(--pri-ink);
      font-weight: 600;
      cursor: pointer;
    }
    .add:disabled {
      opacity: 0.55;
      cursor: default;
    }
    ul {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    li {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 8px 12px;
      padding: 8px 12px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--surface);
    }
    li .name {
      font-weight: 600;
    }
    li .rm {
      margin-left: auto;
      min-height: 44px;
      min-width: 44px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--surface);
      color: var(--ink-2);
      cursor: pointer;
    }
    .ordered {
      color: var(--good);
      font-weight: 600;
    }
  `,
  template: `
    <section [attr.aria-labelledby]="inputId() + '-t'">
      <h3 [id]="inputId() + '-t'">
        {{ title() }}@if (required()) {<span class="star" aria-hidden="true"> *</span><span class="sr-only"> (required)</span>}
      </h3>
      <p class="faint t-xs" data-testid="ipd-hint">{{ hint }}</p>

      @if (!disabled()) {
        @if (loadError(); as message) {
          <p class="err-text" role="alert">
            {{ message }}
            <button type="button" class="linkbtn" data-act="retry-options" (click)="load()">Try again</button>
          </p>
        } @else if (options(); as o) {
          <div class="picker">
            <label>
              <span>Medicine</span>
              <select class="input" [id]="inputId()" data-act="drug" [value]="drug()" (change)="onDrug($event)">
                <option value="">Choose a medicine</option>
                @for (d of o.drugs; track d.key) {
                  <option [value]="d.key">{{ d.name }}{{ d.highAlert ? ' · high-alert' : '' }}</option>
                }
              </select>
            </label>
            <label>
              <span>How often</span>
              <select class="input" data-act="frequency" [value]="frequency()" (change)="onFrequency($event)">
                <option value="">Choose how often</option>
                @for (f of o.frequencies; track f.key) {
                  <option [value]="f.key">{{ f.label }}</option>
                }
              </select>
            </label>
            <button type="button" class="add" data-act="add-order" [disabled]="!canAdd()" (click)="add()">
              <app-icon name="plus" [size]="14" /> Add
            </button>
          </div>
        } @else {
          <p class="faint" aria-busy="true">Loading the medicine list</p>
        }
      }

      @if (rows().length === 0) {
        <p class="faint t-xs" data-testid="ipd-empty">No new medicines for this round.</p>
      } @else {
        <ul aria-label="New medicines for this round">
          @for (r of rows(); track r.id) {
            <li data-testid="ipd-row">
              <span class="name">{{ drugName(r.drugKey) }}</span>
              <span>{{ frequencyLabel(r.frequencyKey) }}</span>
              @if (r.orderId) {
                <span class="ordered">Ordered{{ orderedAt(r) }}</span>
              } @else if (!disabled()) {
                <button type="button" class="rm" data-act="remove-order" [attr.aria-label]="'Remove ' + drugName(r.drugKey)" (click)="remove(r.id)">
                  <app-icon name="x" [size]="14" />
                </button>
              }
            </li>
          }
        </ul>
      }
    </section>
  `,
})
export class IpdOrdersComponent implements OnInit {
  private readonly api = inject(EmrApi);
  private readonly ctx = inject(EMR_COMPONENT_CONTEXT, { optional: true });
  private readonly destroyRef = inject(DestroyRef);

  readonly title = input('Prescription');
  readonly required = input(false);
  readonly value = input<unknown>(null);
  readonly disabled = input(false);
  readonly inputId = input.required<string>();

  readonly valueChange = output<EmrIpdOrdersValue>();

  protected readonly hint = IPD_ORDERS_HINT;
  protected readonly options = signal<EmrIpdOrderOptions | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly drug = signal('');
  protected readonly frequency = signal('');
  protected readonly rows = computed(() => ipdRowsOf(this.value()));
  protected readonly canAdd = computed(
    () => this.drug() !== '' && this.frequency() !== '' && this.rows().length < IPD_ORDERS_MAX && !this.disabled(),
  );

  ngOnInit(): void {
    if (!this.disabled()) {
      this.load();
    }
  }

  protected load(): void {
    const id = this.ctx?.recordId();
    if (!id) {
      return;
    }
    this.loadError.set(null);
    this.api
      .getIpdOrderOptions(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (options) => this.options.set(options),
        error: () => this.loadError.set('We could not load the medicine list. Try again.'),
      });
  }

  protected drugName(key: string): string {
    return this.options()?.drugs.find((d) => d.key === key)?.name ?? key;
  }

  protected frequencyLabel(key: string): string {
    return this.options()?.frequencies.find((f) => f.key === key)?.label ?? key;
  }

  protected orderedAt(row: EmrIpdOrderRow): string {
    return row.orderedAt ? ` · ${formatDisplayDateTimeOfInstant(row.orderedAt)}` : '';
  }

  protected onDrug(event: Event): void {
    this.drug.set((event.target as HTMLSelectElement).value);
  }

  protected onFrequency(event: Event): void {
    this.frequency.set((event.target as HTMLSelectElement).value);
  }

  protected add(): void {
    if (!this.canAdd()) {
      return;
    }
    const row: EmrIpdOrderRow = { id: newClientKey(), drugKey: this.drug(), frequencyKey: this.frequency() };
    this.valueChange.emit(withIpdRows([...this.rows(), row]));
    this.drug.set('');
    this.frequency.set('');
  }

  protected remove(id: string): void {
    const row = this.rows().find((r) => r.id === id);
    if (!row || row.orderId || this.disabled()) {
      return;
    }
    this.valueChange.emit(withIpdRows(this.rows().filter((r) => r.id !== id)));
  }
}
