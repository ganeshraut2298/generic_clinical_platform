import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { BodyMapComponent } from '../components/body-map.component';
import { EmrListComponent } from '../components/emr-list.component';
import { PrescriptionComponent } from '../components/prescription.component';
import { StarterKey } from '../emr.model';
import { EmrFormDefinition, EmrFormField, EmrFormVitalsValue } from '../emr-phase3.model';
import {
  FormValues,
  LATER_TYPES,
  bandOf,
  checkNumber,
  isFieldFilled,
  isVisible,
  optionsOf,
  scoreTotal,
} from './form.logic';

export interface FormFieldChange {
  key: string;
  value: unknown;
}

const VITALS: readonly { key: keyof EmrFormVitalsValue; label: string; unit: string; min: number; max: number }[] = [
  { key: 'bpSystolic', label: 'BP systolic', unit: 'mmHg', min: 0, max: 400 },
  { key: 'bpDiastolic', label: 'BP diastolic', unit: 'mmHg', min: 0, max: 300 },
  { key: 'pulse', label: 'Pulse', unit: 'per min', min: 0, max: 400 },
  { key: 'spo2', label: 'SpO2', unit: '%', min: 0, max: 100 },
  { key: 'temperatureF', label: 'Temperature', unit: '°F', min: 80, max: 115 },
  { key: 'weightKg', label: 'Weight', unit: 'kg', min: 0, max: 400 },
  { key: 'heightCm', label: 'Height', unit: 'cm', min: 0, max: 260 },
];

export const LATER_NOTE = 'Later';

/**
 * A clinic form as the doctor fills it, in the EMR section and in the builder's Preview
 * (SAH-59-1, SAH-59-2, SAH-59-3, SAH-59-5): the six-column grid, show-if rules, live scores and
 * bands, number limits and the "Abnormal" flag, and the shared body map, medicine list, tests and
 * diagnosis components. Photo, file, signature, handwriting and the tooth chart are shown disabled
 * with a "Later" note and store nothing. Values are clinical data: never logged or stored here.
 */
@Component({
  selector: 'app-form-fill',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BodyMapComponent, EmrListComponent, PrescriptionComponent],
  styleUrl: './form-fill.component.scss',
  templateUrl: './form-fill.component.html',
})
export class FormFillComponent {
  readonly definition = input.required<EmrFormDefinition>();
  readonly values = input<FormValues>({});
  readonly disabled = input(false);
  readonly idPrefix = input.required<string>();
  /** Keys of required fields to mark "Required" (after a save was tried). */
  readonly requiredMissing = input<readonly string[]>([]);
  /** Messages for values the server would refuse, by field key. */
  readonly errors = input<Readonly<Record<string, string>>>({});
  readonly specialty = input<StarterKey | null>(null);

  readonly fieldChange = output<FormFieldChange>();

  protected readonly vitals = VITALS;
  protected readonly laterNote = LATER_NOTE;
  protected readonly later = LATER_TYPES;

  protected readonly shown = computed(() => {
    const def = this.definition();
    const values = this.values();
    return def.fields.filter((f) => isVisible(f, def.fields, values));
  });

  protected id(field: EmrFormField): string {
    return `${this.idPrefix()}-${field.key}`;
  }

  protected span(field: EmrFormField): string {
    return `span ${Math.min(6, Math.max(1, field.width))}`;
  }

  protected valueOf(field: EmrFormField): unknown {
    return this.values()[field.key] ?? null;
  }

  protected text(field: EmrFormField): string {
    const v = this.valueOf(field);
    return typeof v === 'string' ? v : '';
  }

  protected numberText(field: EmrFormField): string {
    const v = this.valueOf(field);
    return typeof v === 'number' ? String(v) : '';
  }

  protected options(field: EmrFormField): ReturnType<typeof optionsOf> {
    return optionsOf(field);
  }

  protected isPicked(field: EmrFormField, key: string): boolean {
    const v = this.valueOf(field);
    if (field.type === 'YES_NO') {
      return (v === true && key === 'yes') || (v === false && key === 'no');
    }
    return Array.isArray(v) ? v.includes(key) : v === key;
  }

  protected set(field: EmrFormField, value: unknown): void {
    if (!this.disabled()) {
      this.fieldChange.emit({ key: field.key, value });
    }
  }

  protected onText(field: EmrFormField, event: Event): void {
    const text = (event.target as HTMLInputElement | HTMLTextAreaElement).value;
    this.set(field, text === '' ? null : text);
  }

  protected onNumber(field: EmrFormField, event: Event): void {
    const raw = (event.target as HTMLInputElement).value;
    this.set(field, raw === '' ? null : Number(raw));
  }

  protected pick(field: EmrFormField, key: string): void {
    const v = this.valueOf(field);
    switch (field.type) {
      case 'YES_NO':
        this.set(field, v === (key === 'yes') ? null : key === 'yes');
        break;
      case 'MULTI_CHOICE': {
        const list = Array.isArray(v) ? (v as string[]) : [];
        const next = list.includes(key) ? list.filter((k) => k !== key) : [...list, key];
        this.set(field, next.length > 0 ? next : null);
        break;
      }
      default:
        this.set(field, v === key ? null : key);
    }
  }

  protected onSelect(field: EmrFormField, event: Event): void {
    const v = (event.target as HTMLSelectElement).value;
    this.set(field, v === '' ? null : v);
  }

  protected vitalText(field: EmrFormField, key: keyof EmrFormVitalsValue): string {
    const v = this.valueOf(field) as EmrFormVitalsValue | null;
    const n = v?.[key];
    return typeof n === 'number' ? String(n) : '';
  }

  protected onVital(field: EmrFormField, key: keyof EmrFormVitalsValue, event: Event): void {
    const raw = (event.target as HTMLInputElement).value;
    const current = { ...((this.valueOf(field) as EmrFormVitalsValue | null) ?? {}) };
    if (raw === '') {
      delete current[key];
    } else {
      current[key] = Number(raw);
    }
    this.set(field, Object.keys(current).length > 0 ? current : null);
  }

  protected flag(field: EmrFormField): ReturnType<typeof checkNumber> {
    return checkNumber(field, this.valueOf(field));
  }

  protected total(field: EmrFormField): number | null {
    return scoreTotal(field, this.definition().fields, this.values());
  }

  protected band(field: EmrFormField): ReturnType<typeof bandOf> {
    return bandOf(field, this.definition().fields, this.values());
  }

  protected sourceLabel(field: EmrFormField): string {
    const key = field.band?.sourceFieldKey;
    return this.definition().fields.find((f) => f.key === key)?.label ?? '';
  }

  protected isMissing(field: EmrFormField): boolean {
    return this.requiredMissing().includes(field.key) && !isFieldFilled(field, this.valueOf(field));
  }
}
