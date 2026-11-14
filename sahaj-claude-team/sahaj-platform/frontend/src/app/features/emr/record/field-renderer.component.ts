import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { IconComponent } from '../../../shared/icon/icon.component';
import { BodyMapComponent } from '../components/body-map.component';
import { chipText, drawsAsTiles, toggleMany, toggleSingle } from '../components/chips.logic';
import { EmrListComponent } from '../components/emr-list.component';
import { FollowUpComponent } from '../components/follow-up.component';
import { IpdOrdersComponent } from '../components/ipd-orders.component';
import { PrescriptionComponent } from '../components/prescription.component';
import { EmrFieldDefinition, EmrItemListEntry, StarterKey, fieldTypeLabel } from '../emr.model';
import { isEmptyValue, vitalFlag } from './emr-record.logic';
import { ItemListFieldComponent } from './item-list-field.component';

type Row = Record<string, string>;

/**
 * The generic field renderer (SAH-47-3): one control for each stored kind of value (text, number,
 * number with unit, date, yes or no, choice, several choices, search list, table, plain-text
 * structured value). A photo, file or signature is drawn disabled with a "Later" note and stores
 * nothing until the documents service exists (OQ-45-28). A kind this client does not know shows
 * "Not available yet" and never fails. The label, meta text and required marker come from the
 * snapshot. Values are for the screen only.
 */
@Component({
  selector: 'app-field-renderer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, ItemListFieldComponent, EmrListComponent, PrescriptionComponent, BodyMapComponent, FollowUpComponent, IpdOrdersComponent],
  styleUrl: './field-renderer.component.scss',
  templateUrl: './field-renderer.component.html',
})
export class FieldRendererComponent {
  readonly field = input.required<EmrFieldDefinition>();
  readonly value = input<unknown>(null);
  readonly disabled = input(false);
  readonly error = input<string | null>(null);
  readonly specialty = input<StarterKey | null>(null);
  readonly idPrefix = input.required<string>();

  readonly valueChange = output<unknown>();

  protected readonly inputId = computed(() => `${this.idPrefix()}-${this.field().key}`);
  protected readonly typeLabel = computed(() => fieldTypeLabel(this.field().type));
  protected readonly flag = computed(() => vitalFlag(this.field().key, this.value()));
  protected readonly isLong = computed(() => this.field().type === 'RICH_TEXT');

  protected readonly text = computed(() => (typeof this.value() === 'string' ? (this.value() as string) : ''));
  protected readonly numberText = computed(() => {
    const v = this.value();
    return typeof v === 'number' && Number.isFinite(v) ? String(v) : '';
  });
  protected readonly unitNumber = computed(() => {
    const v = this.value() as { value?: unknown } | null;
    return v && typeof v.value === 'number' ? String(v.value) : '';
  });
  protected readonly unitText = computed(() => {
    const v = this.value() as { unit?: unknown } | null;
    return v && typeof v.unit === 'string' ? v.unit : '';
  });
  protected readonly boolValue = computed(() => this.value() === true);
  protected readonly multi = computed(() => (Array.isArray(this.value()) ? (this.value() as string[]) : []));
  protected readonly multiText = computed(() => this.multi().join(', '));
  protected readonly entries = computed(() => (Array.isArray(this.value()) ? (this.value() as EmrItemListEntry[]) : []));
  protected readonly rows = computed(() => (Array.isArray(this.value()) ? (this.value() as Row[]) : []));
  protected readonly structuredText = computed(() => {
    const v = this.value() as { text?: unknown } | null;
    return v && typeof v.text === 'string' ? v.text : '';
  });
  protected readonly columns = computed(() => {
    const cols = this.field().columns;
    return cols.length > 0 ? cols : [{ key: 'entry', label: 'Entry' }];
  });
  protected readonly filled = computed(() => !isEmptyValue(this.value()));

  protected emit(value: unknown): void {
    this.valueChange.emit(value);
  }

  protected onText(event: Event): void {
    const text = (event.target as HTMLInputElement | HTMLTextAreaElement).value;
    this.emit(text === '' ? null : text);
  }

  protected onNumber(event: Event): void {
    const raw = (event.target as HTMLInputElement).value;
    this.emit(raw === '' ? null : Number(raw));
  }

  protected onUnitNumber(event: Event): void {
    const raw = (event.target as HTMLInputElement).value;
    this.emitUnit(raw === '' ? null : Number(raw), this.unitText());
  }

  protected onUnit(event: Event): void {
    const unit = (event.target as HTMLInputElement | HTMLSelectElement).value;
    const current = this.value() as { value?: unknown } | null;
    this.emitUnit(typeof current?.value === 'number' ? current.value : null, unit);
  }

  private emitUnit(value: number | null, unit: string): void {
    this.emit(value === null && unit === '' ? null : { value, unit });
  }

  protected onToggle(): void {
    this.emit(!this.boolValue());
  }

  protected onChoice(event: Event): void {
    const v = (event.target as HTMLInputElement | HTMLSelectElement).value;
    this.emit(v === '' ? null : v);
  }

  /** CHIPS presentation (SAH-75-6): round chips with a tick when chosen; "None" clears the others and any other chip clears it. */
  protected readonly chips = computed(() => this.field().presentation === 'CHIPS');
  /** ADR-026 D13: a chip field whose options carry a hint draws as tiles (three columns, two below 600 px). */
  protected readonly tiles = computed(() => this.chips() && drawsAsTiles(this.field().options));
  protected chipLabel = chipText;

  protected togglePick(optionKey: string): void {
    const list = this.multi();
    this.emit(this.chips() ? toggleMany(this.field().options, list, optionKey) : list.includes(optionKey) ? list.filter((k) => k !== optionKey) : [...list, optionKey]);
  }

  /** A single-choice chip: pressing the chosen chip again clears the field. */
  protected pickSingle(optionKey: string): void {
    this.emit(toggleSingle(this.text() === '' ? null : this.text(), optionKey));
  }

  protected onMultiText(event: Event): void {
    const parts = (event.target as HTMLInputElement).value
      .split(',')
      .map((p) => p.trim())
      .filter((p) => p !== '');
    this.emit(parts);
  }

  protected onItems(items: EmrItemListEntry[]): void {
    this.emit(items);
  }

  protected onCell(rowIndex: number, columnKey: string, event: Event): void {
    const text = (event.target as HTMLInputElement).value;
    this.emit(this.rows().map((row, i) => (i === rowIndex ? { ...row, [columnKey]: text } : row)));
  }

  protected addRow(): void {
    this.emit([...this.rows(), {}]);
  }

  protected removeRow(index: number): void {
    this.emit(this.rows().filter((_, i) => i !== index));
  }

  protected onStructured(event: Event): void {
    const text = (event.target as HTMLTextAreaElement).value;
    this.emit(text === '' ? null : { text });
  }
}
