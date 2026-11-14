import { isComponentEmpty, isComponentFilled } from '../components/component-values.logic';
import { EmrFormDefinition, EmrFormField, EmrFormFieldType, EmrFormOption } from '../emr-phase3.model';

/**
 * Pure rules of a clinic form while it is filled or previewed (SAH-59-1 to SAH-59-5; ADR-022 D11):
 * show-if, scores, bands, ranges and the Done rule. The server applies the same rules; these give
 * the answer live while the doctor types. Scales, cut-offs, bands and normal ranges are values
 * from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-9).
 */

export type FormValues = Record<string, unknown>;

/** Types that show a control the doctor fills. Display-only and computed types have none. */
const NOT_FILLED_BY_DOCTOR: ReadonlySet<EmrFormFieldType> = new Set([
  'SCORE',
  'SCORE_BAND',
  'SECTION_HEADING',
  'INFO_NOTE',
]);

/** Photo, file, signature, handwriting pad and the tooth chart are shown disabled with "Later". */
export const LATER_TYPES: ReadonlySet<EmrFormFieldType> = new Set([
  'SIGNATURE',
  'PHOTO',
  'FILE_UPLOAD',
  'HANDWRITING_PAD',
  'TOOTH_CHART',
]);

export const CHOICE_TYPES: ReadonlySet<EmrFormFieldType> = new Set(['SINGLE_CHOICE', 'MULTI_CHOICE', 'DROPDOWN']);
export const SHOW_IF_SOURCE_TYPES: ReadonlySet<EmrFormFieldType> = new Set([
  'YES_NO',
  'SINGLE_CHOICE',
  'MULTI_CHOICE',
  'DROPDOWN',
]);
export const COMPONENT_TYPES: ReadonlySet<EmrFormFieldType> = new Set([
  'BODY_MAP',
  'MEDICINE_LIST',
  'LAB_TESTS',
  'DIAGNOSIS',
]);

export const YES_NO_OPTIONS: readonly EmrFormOption[] = [
  { key: 'yes', label: 'Yes', score: 0 },
  { key: 'no', label: 'No', score: 0 },
];

/** The options a field offers; Yes / No has the fixed keys yes and no (scores may be set on them). */
export function optionsOf(field: EmrFormField): readonly EmrFormOption[] {
  if (field.type === 'YES_NO') {
    const own = field.options ?? [];
    return YES_NO_OPTIONS.map((o) => ({ ...o, score: own.find((x) => x.key === o.key)?.score ?? 0 }));
  }
  return field.options ?? [];
}

export function isFillable(field: EmrFormField): boolean {
  return !NOT_FILLED_BY_DOCTOR.has(field.type) && !LATER_TYPES.has(field.type);
}

/** Visible by its show-if rule. A chain is evaluated top to bottom: a hidden source hides its dependants. */
export function isVisible(field: EmrFormField, fields: readonly EmrFormField[], values: FormValues): boolean {
  const rule = field.showIf;
  if (!rule) {
    return true;
  }
  const index = fields.indexOf(field);
  const source = fields.slice(0, index < 0 ? fields.length : index).find((f) => f.key === rule.fieldKey);
  if (!source || !isVisible(source, fields, values)) {
    return false;
  }
  const v = values[source.key];
  if (source.type === 'YES_NO') {
    return (v === true && rule.optionKey === 'yes') || (v === false && rule.optionKey === 'no');
  }
  return Array.isArray(v) ? v.includes(rule.optionKey) : v === rule.optionKey;
}

function isBlank(v: unknown): boolean {
  return v === null || v === undefined || v === '' || (Array.isArray(v) && v.length === 0);
}

export function isFieldFilled(field: EmrFormField, value: unknown): boolean {
  if (COMPONENT_TYPES.has(field.type)) {
    return isComponentFilled(value);
  }
  if (field.type === 'VITALS_BLOCK') {
    return typeof value === 'object' && value !== null && Object.values(value).some((x) => typeof x === 'number');
  }
  if (field.type === 'YES_NO') {
    return typeof value === 'boolean';
  }
  if (typeof value === 'string') {
    return value.trim() !== '';
  }
  return !isBlank(value);
}

// ---- scores and bands ----

function optionScore(field: EmrFormField, value: unknown): number {
  const options = optionsOf(field);
  if (field.type === 'MULTI_CHOICE') {
    const picked = Array.isArray(value) ? (value as string[]) : [];
    return options.filter((o) => picked.includes(o.key)).reduce((sum, o) => sum + o.score, 0);
  }
  if (field.type === 'YES_NO') {
    return typeof value === 'boolean' ? (options.find((o) => o.key === (value ? 'yes' : 'no'))?.score ?? 0) : 0;
  }
  return options.find((o) => o.key === value)?.score ?? 0;
}

/**
 * The total of a Score field: the scores of the chosen options of its sources (all chosen options of
 * a Multiple choice) plus the points of the rules that apply. Null (blank) until at least one source
 * or rule field is answered. A source hidden by its rule adds nothing.
 */
export function scoreTotal(field: EmrFormField, fields: readonly EmrFormField[], values: FormValues): number | null {
  const score = field.score;
  if (!score) {
    return null;
  }
  let answered = false;
  let total = 0;
  for (const key of score.sourceFieldKeys) {
    const source = fields.find((f) => f.key === key);
    if (!source || !isVisible(source, fields, values)) {
      continue;
    }
    const v = values[key];
    if (isFieldFilled(source, v)) {
      answered = true;
    }
    total += optionScore(source, v);
  }
  for (const rule of score.rules) {
    const source = fields.find((f) => f.key === rule.fieldKey);
    if (!source || !isVisible(source, fields, values)) {
      continue;
    }
    const v = values[rule.fieldKey];
    if (rule.kind === 'NUMBER_BELOW') {
      if (typeof v === 'number') {
        answered = true;
        if (v < rule.threshold) {
          total += rule.points;
        }
      }
    } else if (Array.isArray(v)) {
      answered = true;
      if (v.length < rule.threshold) {
        total += rule.points;
      }
    }
  }
  return answered ? total : null;
}

export interface BandResult {
  label: string;
  /** 0 for the lowest band up to count - 1; the colour moves from green to red with the rank. */
  rank: number;
  count: number;
  tone: 'good' | 'warn' | 'crit';
}

/** The band with the highest "from" not above the total. */
export function bandOf(field: EmrFormField, fields: readonly EmrFormField[], values: FormValues): BandResult | null {
  const band = field.band;
  if (!band) {
    return null;
  }
  const source = fields.find((f) => f.key === band.sourceFieldKey);
  if (!source) {
    return null;
  }
  const total = scoreTotal(source, fields, values);
  if (total === null) {
    return null;
  }
  const sorted = [...band.bands].sort((a, b) => a.from - b.from);
  let hit = -1;
  sorted.forEach((b, i) => {
    if (total >= b.from) {
      hit = i;
    }
  });
  if (hit < 0) {
    return null;
  }
  const count = sorted.length;
  const position = count <= 1 ? 0 : Math.round((hit / (count - 1)) * 2);
  return { label: sorted[hit].label, rank: hit, count, tone: position === 0 ? 'good' : position === 1 ? 'warn' : 'crit' };
}

// ---- numbers ----

export type NumberCheck =
  | { kind: 'outside-limits'; message: string }
  | { kind: 'abnormal'; message: string }
  | null;

/** Outside min and max: an error, not accepted. Inside them but outside the normal range: accepted, flagged "Abnormal". */
export function checkNumber(field: EmrFormField, value: unknown): NumberCheck {
  if (field.type !== 'NUMBER' || typeof value !== 'number' || !Number.isFinite(value)) {
    return null;
  }
  const { min, max, normalFrom, normalTo } = field;
  if ((min != null && value < min) || (max != null && value > max)) {
    return { kind: 'outside-limits', message: `Outside ${min ?? '…'}–${max ?? '…'}` };
  }
  if ((normalFrom != null && value < normalFrom) || (normalTo != null && value > normalTo)) {
    return { kind: 'abnormal', message: 'Abnormal' };
  }
  return null;
}

/** Field key to a plain sentence, for values the server would refuse (hard limits). */
export function formProblems(def: EmrFormDefinition, values: FormValues): Record<string, string> {
  const out: Record<string, string> = {};
  for (const field of def.fields) {
    if (!isVisible(field, def.fields, values)) {
      continue;
    }
    const check = checkNumber(field, values[field.key]);
    if (check?.kind === 'outside-limits') {
      out[field.key] = check.message;
    }
  }
  return out;
}

// ---- Done and the status line ----

export function requiredVisible(def: EmrFormDefinition, values: FormValues): EmrFormField[] {
  return def.fields.filter((f) => f.required && isFillable(f) && isVisible(f, def.fields, values));
}

export function missingRequired(def: EmrFormDefinition, values: FormValues): EmrFormField[] {
  return requiredVisible(def, values).filter((f) => !isFieldFilled(f, values[f.key]));
}

export function abnormalCount(def: EmrFormDefinition, values: FormValues): number {
  return def.fields.filter(
    (f) => isVisible(f, def.fields, values) && checkNumber(f, values[f.key])?.kind === 'abnormal',
  ).length;
}

/**
 * Done (SAH-47-4 applied to a form): every required shown field filled; with none required, at least
 * one shown field filled.
 */
export function isFormDone(def: EmrFormDefinition, values: FormValues): boolean {
  const required = requiredVisible(def, values);
  if (required.length > 0) {
    return required.every((f) => isFieldFilled(f, values[f.key]));
  }
  return def.fields.some((f) => isFillable(f) && isVisible(f, def.fields, values) && isFieldFilled(f, values[f.key]));
}

/** The Preview status line (SAH-59-5). */
export function statusLine(def: EmrFormDefinition, values: FormValues, saveTried: boolean): string {
  const required = requiredVisible(def, values);
  const missing = missingRequired(def, values);
  if (saveTried && missing.length > 0) {
    return `${missing.length} required field(s) missing: ${missing.map((f) => f.label).join(', ')}`;
  }
  const abnormal = abnormalCount(def, values);
  if (abnormal > 0) {
    return `${abnormal} value(s) outside the normal range`;
  }
  return `${required.length - missing.length} of ${required.length} required fields filled`;
}

/** Builder line: "12 fields · 2 with logic · 3 scored" (display fields do not count as fields). */
export function fieldCountLine(fields: readonly EmrFormField[]): string {
  const real = fields.filter((f) => f.type !== 'SECTION_HEADING' && f.type !== 'INFO_NOTE').length;
  const logic = fields.filter((f) => f.showIf).length;
  const scored = fields.filter((f) => f.type === 'SCORE' || f.type === 'SCORE_BAND').length;
  return `${real} fields · ${logic} with logic · ${scored} scored`;
}

/** Values to save: hidden fields are dropped (the server does the same) and empty values are left out. */
export function valuesToSave(def: EmrFormDefinition, values: FormValues): FormValues {
  const out: FormValues = {};
  for (const field of def.fields) {
    if (!isFillable(field) || !isVisible(field, def.fields, values)) {
      continue;
    }
    const v = values[field.key];
    const stored = COMPONENT_TYPES.has(field.type) ? !isComponentEmpty(v) : isFieldFilled(field, v);
    if (stored) {
      out[field.key] = v;
    }
  }
  return out;
}
