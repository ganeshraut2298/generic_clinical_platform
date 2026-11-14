import {
  CHOICE_TYPES,
  SHOW_IF_SOURCE_TYPES,
  isFillable,
  optionsOf,
} from '../../emr/forms/form.logic';
import {
  EmrFormDefinition,
  EmrFormField,
  EmrFormFieldType,
  EmrFormOption,
  EmrFormSettings,
  EmrFormState,
  EmrFormSummary,
  EmrFormView,
} from '../../emr/emr-phase3.model';

/**
 * Pure rules of the form builder (SAH-59-1 to SAH-59-4; mockup #FormBuilder): the palette, new
 * fields with their defaults, moving, copying and deleting fields, the show-if and score sources,
 * and the checks before a form is sent for approval or published. The server checks the same
 * rules again; these give the owner the answer while building.
 */

export interface PaletteItem {
  readonly type: EmrFormFieldType;
  readonly label: string;
  readonly icon: string;
  readonly hint: string;
}

export interface PaletteGroup {
  readonly label: string;
  readonly items: readonly PaletteItem[];
}

const p = (type: EmrFormFieldType, label: string, icon: string, hint = ''): PaletteItem => ({ type, label, icon, hint });

/** The 23 field types in three groups, in the mockup's order and words. */
export const PALETTE: readonly PaletteGroup[] = [
  {
    label: 'Basic fields',
    items: [
      p('SHORT_TEXT', 'Short text', 'Aa'),
      p('LONG_TEXT', 'Long text', '¶'),
      p('NUMBER', 'Number', '123'),
      p('DATE', 'Date', 'Date'),
      p('TIME', 'Time', 'Time'),
      p('YES_NO', 'Yes / No', 'Y/N'),
      p('SINGLE_CHOICE', 'Single choice', '1 of'),
      p('MULTI_CHOICE', 'Multiple choice', 'Any'),
      p('DROPDOWN', 'Dropdown', 'List'),
    ],
  },
  {
    label: 'Clinical',
    items: [
      p('VITALS_BLOCK', 'Vitals block', 'BP', 'BP, pulse, SpO2, temperature, weight, height'),
      p('BODY_MAP', 'Body map', 'Body', 'Uses the shared body map: pins, findings, what happened'),
      p('TOOTH_CHART', 'Tooth chart', 'Teeth', 'Uses the shared FDI tooth chart'),
      p('MEDICINE_LIST', 'Medicine list', 'Rx', 'Medicine, dose, frequency, days; allergy check'),
      p('LAB_TESTS', 'Lab tests', 'Lab', 'Pick tests; results come back here'),
      p('DIAGNOSIS', 'Diagnosis (ICD-10)', 'ICD', 'Search diagnosis with ICD-10 code'),
      p('SCORE', 'Score (Σ)', 'Σ', 'Adds up the scores of the fields you pick'),
      p('SCORE_BAND', 'Score band', 'Band', 'Names the range a score falls in'),
    ],
  },
  {
    label: 'Layout and sign-off',
    items: [
      p('SECTION_HEADING', 'Section heading', 'H'),
      p('INFO_NOTE', 'Info note', 'i'),
      p('SIGNATURE', 'Signature', 'Sign', 'Doctor signs on tablet or with stylus'),
      p('PHOTO', 'Photo / image', 'Img', 'Camera or gallery; kept with the record'),
      p('FILE_UPLOAD', 'File upload', 'File', 'PDF or image from outside'),
      p('HANDWRITING_PAD', 'Handwriting pad', 'Pen', 'Write freely with a stylus'),
    ],
  },
];

export const TYPE_LABEL: Record<EmrFormFieldType, string> = Object.fromEntries(
  PALETTE.flatMap((g) => g.items.map((i) => [i.type, i.label])),
) as Record<EmrFormFieldType, string>;

export const TYPE_ICON: Record<EmrFormFieldType, string> = Object.fromEntries(
  PALETTE.flatMap((g) => g.items.map((i) => [i.type, i.icon])),
) as Record<EmrFormFieldType, string>;

export const TYPE_HINT: Record<EmrFormFieldType, string> = Object.fromEntries(
  PALETTE.flatMap((g) => g.items.map((i) => [i.type, i.hint])),
) as Record<EmrFormFieldType, string>;

export const FIELDS_MAX = 80;
export const LABEL_MAX = 200;
export const OPTIONS_MAX = 30;
export const BANDS_MAX = 10;
export const RULES_MAX = 10;
export const FORM_NAME_MAX = 80;

/** Photo, file, signature and handwriting stay disabled until the documents service exists (OQ-45-27, OQ-45-28). */
export const LATER_FIELD_TYPES: ReadonlySet<EmrFormFieldType> = new Set([
  'SIGNATURE',
  'PHOTO',
  'FILE_UPLOAD',
  'HANDWRITING_PAD',
  'TOOTH_CHART',
]);

export const CAN_HAVE_HELP = (type: EmrFormFieldType): boolean => type !== 'INFO_NOTE';
/** A required field must be fillable: headings, notes, scores, bands and the "Later" types cannot be required. */
export const CAN_BE_REQUIRED = (type: EmrFormFieldType): boolean => isFillable({ type } as EmrFormField);
export const HAS_OPTIONS = (type: EmrFormFieldType): boolean => CHOICE_TYPES.has(type);
export const SCORES_OPTIONS = (type: EmrFormFieldType): boolean => CHOICE_TYPES.has(type) || type === 'YES_NO';
export const CAN_COPY_LAST = (type: EmrFormFieldType): boolean =>
  ['SHORT_TEXT', 'LONG_TEXT', 'NUMBER', 'DATE', 'TIME', 'YES_NO', 'SINGLE_CHOICE', 'MULTI_CHOICE', 'DROPDOWN'].includes(type);

export const WHO_CHOICES = [
  { key: 'DOCTOR', label: 'Doctor' },
  { key: 'NURSE', label: 'Nurse' },
  { key: 'RECEPTION', label: 'Reception' },
  { key: 'PATIENT', label: 'Patient' },
] as const;

export const VISIT_TYPE_CHOICES = [
  { key: 'NEW_CONSULT', label: 'New consult' },
  { key: 'FOLLOW_UP', label: 'Follow-up' },
  { key: 'PROCEDURE_VISIT', label: 'Procedure visit' },
  { key: 'TELECONSULT', label: 'Teleconsult' },
  { key: 'IPD_DAILY_ROUND', label: 'IPD daily round' },
] as const;

export const PRINT_LANGUAGE_CHOICES = [
  { key: 'EN', label: 'English' },
  { key: 'EN_MR', label: 'English + Marathi' },
  { key: 'EN_HI', label: 'English + Hindi' },
] as const;

export const PLACE_CHOICES = [
  { key: 'EMERGENCY', label: 'Emergency' },
  { key: 'IPD_WARD', label: 'IPD wards' },
] as const;

// ---- keys and new fields ----

function slug(text: string): string {
  const s = text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
  return /^[a-z]/.test(s) ? s : `f_${s}`;
}

/** A key that is unique in the form: `^[a-z][a-z0-9_]{0,39}$`. */
export function uniqueKey(base: string, fields: readonly EmrFormField[]): string {
  const taken = new Set(fields.map((f) => f.key));
  const root = (slug(base) || 'field').slice(0, 34);
  let n = 1;
  let key = root;
  while (taken.has(key)) {
    n += 1;
    key = `${root}_${n}`;
  }
  return key;
}

function options(...labels: string[]): EmrFormOption[] {
  return labels.map((label, i) => ({ key: `option_${i + 1}`, label, score: 0 }));
}

const WIDE: ReadonlySet<EmrFormFieldType> = new Set([
  'SECTION_HEADING',
  'INFO_NOTE',
  'LONG_TEXT',
  'VITALS_BLOCK',
  'BODY_MAP',
  'TOOTH_CHART',
  'MEDICINE_LIST',
  'LAB_TESTS',
  'DIAGNOSIS',
  'SIGNATURE',
  'PHOTO',
  'FILE_UPLOAD',
  'HANDWRITING_PAD',
]);

export function newField(type: EmrFormFieldType, fields: readonly EmrFormField[]): EmrFormField {
  const label = type === 'INFO_NOTE' ? 'Write an instruction for the person filling the form.' : TYPE_LABEL[type];
  const field: EmrFormField = {
    key: uniqueKey(type === 'INFO_NOTE' ? 'note' : label, fields),
    type,
    label,
    required: false,
    width: WIDE.has(type) ? 6 : 3,
    print: true,
    copyFromLast: false,
    whoCanFill: ['DOCTOR'],
  };
  if (CHOICE_TYPES.has(type)) {
    field.options = options('Option 1', 'Option 2', 'Option 3');
  }
  if (type === 'SCORE') {
    field.score = { sourceFieldKeys: scoreSourceCandidates(fields).filter((f) => hasScores(f)).map((f) => f.key), rules: [] };
  }
  if (type === 'SCORE_BAND') {
    const score = fields.find((f) => f.type === 'SCORE');
    field.band = {
      sourceFieldKey: score?.key ?? '',
      bands: [
        { from: 0, label: 'Low' },
        { from: 3, label: 'Moderate' },
        { from: 6, label: 'High' },
      ],
    };
  }
  return field;
}

export function hasScores(field: EmrFormField): boolean {
  return optionsOf(field).some((o) => o.score !== 0);
}

/** Fields whose chosen option scores a Score can add up. */
export function scoreSourceCandidates(fields: readonly EmrFormField[]): EmrFormField[] {
  return fields.filter((f) => SCORES_OPTIONS(f.type));
}

/** Earlier Yes / No, Single choice, Multiple choice or Dropdown fields a show-if rule can point to. */
export function showIfCandidates(fields: readonly EmrFormField[], index: number): EmrFormField[] {
  return fields.slice(0, Math.max(0, index)).filter((f) => SHOW_IF_SOURCE_TYPES.has(f.type));
}

// ---- editing the list ----

export function insertAt(fields: readonly EmrFormField[], index: number, field: EmrFormField): EmrFormField[] {
  const out = [...fields];
  out.splice(Math.min(Math.max(0, index), out.length), 0, field);
  return out;
}

/** Index after the selected field, or the end. */
export function insertionIndex(fields: readonly EmrFormField[], selectedKey: string | null): number {
  const i = selectedKey ? fields.findIndex((f) => f.key === selectedKey) : -1;
  return i >= 0 ? i + 1 : fields.length;
}

export function duplicate(fields: readonly EmrFormField[], key: string): { fields: EmrFormField[]; copy: EmrFormField } | null {
  const i = fields.findIndex((f) => f.key === key);
  if (i < 0) {
    return null;
  }
  const source = fields[i];
  const copy: EmrFormField = {
    ...structuredClone(source),
    key: uniqueKey(source.key, fields),
    label: `${source.label} (copy)`.slice(0, LABEL_MAX),
  };
  // A copy keeps no rule that other fields depend on: it only points back to what is above it.
  return { fields: insertAt(fields, i + 1, copy), copy };
}

/** Fields that use `key` as a show-if or score source; deleting it is refused until they are changed. */
export function usersOf(fields: readonly EmrFormField[], key: string): EmrFormField[] {
  return fields.filter(
    (f) =>
      f.key !== key &&
      (f.showIf?.fieldKey === key ||
        f.score?.sourceFieldKeys.includes(key) ||
        f.score?.rules.some((r) => r.fieldKey === key) ||
        f.band?.sourceFieldKey === key),
  );
}

export function deleteBlockedMessage(fields: readonly EmrFormField[], key: string): string | null {
  const users = usersOf(fields, key);
  if (users.length === 0) {
    return null;
  }
  const names = users.map((f) => `“${f.label}”`).join(', ');
  return `Cannot delete yet. ${names} ${users.length === 1 ? 'uses' : 'use'} it. Remove that use first.`;
}

export function removeField(fields: readonly EmrFormField[], key: string): EmrFormField[] {
  return fields.filter((f) => f.key !== key);
}

/**
 * Moves a field up or down. A move that would put a show-if source below its dependent, or a field
 * above its own source, is refused (the form is never left with a rule that points to a later field).
 */
export function move(
  fields: readonly EmrFormField[],
  key: string,
  direction: -1 | 1,
): { fields: EmrFormField[]; message: string | null } {
  const i = fields.findIndex((f) => f.key === key);
  const j = i + direction;
  if (i < 0 || j < 0 || j >= fields.length) {
    return { fields: [...fields], message: null };
  }
  const next = [...fields];
  [next[i], next[j]] = [next[j], next[i]];
  const broken = next.find((f, idx) => f.showIf && !next.slice(0, idx).some((e) => e.key === f.showIf!.fieldKey));
  if (broken) {
    return { fields: [...fields], message: `“${broken.label}” must stay below the field its rule uses.` };
  }
  return { fields: next, message: null };
}

export function moveTo(fields: readonly EmrFormField[], key: string, toIndex: number): { fields: EmrFormField[]; message: string | null } {
  const from = fields.findIndex((f) => f.key === key);
  if (from < 0) {
    return { fields: [...fields], message: null };
  }
  const next = [...fields];
  const [item] = next.splice(from, 1);
  next.splice(from < toIndex ? toIndex - 1 : toIndex, 0, item);
  const broken = next.find((f, idx) => f.showIf && !next.slice(0, idx).some((e) => e.key === f.showIf!.fieldKey));
  if (broken) {
    return { fields: [...fields], message: `“${broken.label}” must stay below the field its rule uses.` };
  }
  return { fields: next, message: null };
}

export function updateField(fields: readonly EmrFormField[], key: string, patch: Partial<EmrFormField>): EmrFormField[] {
  return fields.map((f) => (f.key === key ? { ...f, ...patch } : f));
}

/** A show-if rule on the first option of the picked source (the owner then picks the value). */
export function showIfFor(source: EmrFormField | undefined): EmrFormField['showIf'] {
  if (!source) {
    return null;
  }
  const first = optionsOf(source)[0];
  return first ? { fieldKey: source.key, optionKey: first.key } : null;
}

export function addOption(field: EmrFormField): EmrFormOption[] {
  const list = field.options ?? [];
  const n = list.length + 1;
  const taken = new Set(list.map((o) => o.key));
  let key = `option_${n}`;
  let k = n;
  while (taken.has(key)) {
    k += 1;
    key = `option_${k}`;
  }
  return [...list, { key, label: `Option ${n}`, score: 0 }].slice(0, OPTIONS_MAX);
}

export function stepScore(score: number, direction: 1 | -1): number {
  return Math.min(100, Math.max(-100, score + direction));
}

// ---- counts and chips ----

export function chipText(state: EmrFormState, versionNo: number): string {
  switch (state) {
    case 'WAITING_APPROVAL':
      return `v${versionNo} · waiting approval`;
    case 'LIVE':
      return `v${versionNo} · live`;
    case 'SENT_BACK':
      return `v${versionNo} · sent back`;
    case 'RETIRED':
      return `v${versionNo} · retired`;
    default:
      return `v${versionNo} · draft`;
  }
}

export function summaryChip(form: Pick<EmrFormSummary, 'state' | 'chipVersionNo'>): string {
  return chipText(form.state, form.chipVersionNo);
}

export function departmentsText(useIn: EmrFormSummary['useIn'], names: ReadonlyMap<string, string>): string {
  const parts = [
    ...useIn.departmentKeys.map((k) => names.get(k) ?? k),
    ...useIn.places.map((pl) => (pl === 'EMERGENCY' ? 'Emergency' : 'IPD wards')),
  ];
  return parts.length > 0 ? parts.join(', ') : 'No department yet';
}

// ---- checks ----

export const NO_FIELD_TEXT = 'Add at least one field';
export const NO_DEPARTMENT_TEXT = 'Pick at least one department in Form settings first.';

/** The checks before Send for approval or Publish: the first problem as a sentence, else null. */
export function publishProblem(name: string, settings: EmrFormSettings, fields: readonly EmrFormField[]): string | null {
  if (fields.length === 0) {
    return NO_FIELD_TEXT;
  }
  if (settings.useIn.departmentKeys.length + settings.useIn.places.length === 0) {
    return NO_DEPARTMENT_TEXT;
  }
  if (!name.trim()) {
    return 'Give the form a name.';
  }
  for (const f of fields) {
    if (f.type === 'SCORE_BAND') {
      const band = f.band;
      const source = fields.find((s) => s.key === band?.sourceFieldKey && s.type === 'SCORE');
      if (!band || !source) {
        return `“${f.label}” needs a score to take its band from.`;
      }
      const sorted = band.bands.map((b) => b.from);
      const ascending = sorted.every((v, i) => i === 0 || v > sorted[i - 1]);
      if (band.bands.length === 0 || !ascending || band.bands.some((b) => !b.label.trim())) {
        return `“${f.label}” needs bands in rising order, each with a label.`;
      }
    }
  }
  return null;
}

/** Plain-text label limits and the like, for the draft (a draft always saves; the server may still refuse). */
export function fieldProblems(fields: readonly EmrFormField[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const f of fields) {
    if (f.label.trim().length === 0) {
      out[f.key] = 'Give the field a label.';
    } else if (f.label.length > LABEL_MAX) {
      out[f.key] = `Use at most ${LABEL_MAX} characters.`;
    } else if (CHOICE_TYPES.has(f.type) && (f.options ?? []).length === 0) {
      out[f.key] = 'Add at least one option.';
    }
  }
  return out;
}

export function definitionOf(name: string, settings: EmrFormSettings, fields: readonly EmrFormField[]): EmrFormDefinition {
  return { schemaVersion: 1, name, settings, fields: [...fields] };
}

export function isDirty(view: EmrFormView, name: string, settings: EmrFormSettings, fields: readonly EmrFormField[]): boolean {
  const d = view.definition;
  return JSON.stringify([d.name, d.settings, d.fields]) !== JSON.stringify([name, settings, fields]);
}
