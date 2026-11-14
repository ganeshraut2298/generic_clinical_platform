import {
  EmrFieldDefinition,
  EmrLayoutSection,
  EmrPart,
  EmrPartProgress,
  EmrRecordSection,
  EmrSectionStatus,
  EmrValueKind,
} from '../emr.model';
import { isComponentEmpty, isComponentFilled, isComponentValue, sanitizeComponentValue } from '../components/component-values.logic';
import { formProblems, isFormDone } from '../forms/form.logic';

/**
 * Pure rules of the EMR screen (SAH-47). The server computes Done, the part badges and "Still
 * needed" when a record is read or saved; these functions give the same answer from the values on
 * screen so the doctor sees it change as they type (OQ-45-25, OQ-45-26).
 */

export type SectionValues = Record<string, unknown>;
export type ValuesBySection = Record<string, SectionValues>;

/** COVERED fields are replaced by a shared component: never rendered, stored or required (ADR-022 D2). */
const NOT_FILLABLE: ReadonlySet<EmrValueKind> = new Set(['DISPLAY_ONLY', 'LATER', 'COVERED']);

export function isFillable(field: Pick<EmrFieldDefinition, 'valueKind'>): boolean {
  return !NOT_FILLABLE.has(field.valueKind);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Empty means null, an empty string, an empty array, or an object with nothing in it (SAH-47-3). */
export function isEmptyValue(value: unknown): boolean {
  if (value === null || value === undefined) {
    return true;
  }
  if (typeof value === 'string') {
    return value.trim() === '';
  }
  if (Array.isArray(value)) {
    return value.length === 0;
  }
  if (isComponentValue(value)) {
    return isComponentEmpty(value);
  }
  if (isRecord(value)) {
    if ('value' in value) {
      return isEmptyValue(value['value']);
    }
    if ('text' in value) {
      return isEmptyValue(value['text']);
    }
    return Object.keys(value).length === 0;
  }
  if (typeof value === 'number') {
    return Number.isNaN(value);
  }
  return false;
}

/** Done: every required fillable field filled; with none required, at least one field filled. */
export function isSectionDone(
  section: Pick<EmrLayoutSection, 'fields'> & Partial<Pick<EmrLayoutSection, 'source' | 'form'>>,
  values: SectionValues,
  copyPending: boolean,
): boolean {
  if (copyPending) {
    return false;
  }
  if ('source' in section && section.source === 'FORM' && section.form) {
    return isFormDone(section.form, values);
  }
  const filled = (f: EmrFieldDefinition): boolean =>
    f.valueKind === 'COMPONENT' ? isComponentFilled(values[f.key]) : !isEmptyValue(values[f.key]);
  const fillable = section.fields.filter(isFillable);
  const required = fillable.filter((f) => f.required);
  if (required.length > 0) {
    return required.every(filled);
  }
  return fillable.some(filled);
}

export interface SectionState {
  readonly key: string;
  readonly part: EmrPart;
  readonly required: boolean;
  readonly done: boolean;
  readonly status: EmrSectionStatus;
}

export function sectionStates(
  layout: readonly EmrLayoutSection[],
  values: Readonly<ValuesBySection>,
  copyPending: (key: string) => boolean,
  /** A specialty section (ADR-023) answers Done by its own rule; null leaves the generic rule. */
  doneOverride?: (section: EmrLayoutSection, values: SectionValues) => boolean | null,
): SectionState[] {
  return layout.map((section) => {
    const own = doneOverride ? doneOverride(section, values[section.key] ?? {}) : null;
    const done = own === null ? isSectionDone(section, values[section.key] ?? {}, copyPending(section.key)) : !copyPending(section.key) && own;
    return {
      key: section.key,
      part: section.part,
      required: section.required,
      done,
      status: done ? 'DONE' : section.required ? 'REQUIRED' : 'OPTIONAL',
    };
  });
}

export function partProgress(states: readonly SectionState[]): EmrPartProgress[] {
  return (['ASSESSMENT', 'TREATMENT'] as const).map((part) => {
    const inPart = states.filter((s) => s.part === part);
    const required = inPart.filter((s) => s.required);
    const requiredDone = required.filter((s) => s.done).length;
    return {
      part,
      requiredDone,
      requiredTotal: required.length,
      complete: requiredDone === required.length,
      sectionsDone: inPart.filter((s) => s.done).length,
      sectionsTotal: inPart.length,
    };
  });
}

/** Required sections that are not done, in layout order. */
export function stillNeeded(states: readonly SectionState[]): string[] {
  return states.filter((s) => s.required && !s.done).map((s) => s.key);
}

/** The footer line: red "Still needed: a, b." or green "Ready to complete." (names in lower case). */
export function footerText(needed: readonly string[], nameOf: (key: string) => string): string {
  if (needed.length === 0) {
    return 'Ready to complete.';
  }
  return `Still needed: ${needed.map((key) => nameOf(key).toLowerCase()).join(', ')}.`;
}

/** The Done mark of a part tab: a tick when every required section is done, also with 0 required. */
export function partBadge(progress: Pick<EmrPartProgress, 'requiredDone' | 'requiredTotal'>): string {
  return `${progress.requiredDone} of ${progress.requiredTotal} required`;
}

/** Server answer as states, used while nothing on screen is unsaved. */
export function statesFromServer(
  layout: readonly EmrLayoutSection[],
  sections: readonly EmrRecordSection[],
): SectionState[] {
  const byKey = new Map(sections.map((s) => [s.key, s]));
  return layout.map((section) => {
    const status = byKey.get(section.key)?.status ?? (section.required ? 'REQUIRED' : 'OPTIONAL');
    return {
      key: section.key,
      part: section.part,
      required: section.required,
      done: status === 'DONE',
      status,
    };
  });
}

// ---- values that changed ----

export function sameValue(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

/** The values the server stores: keys with an empty value are left out (a cleared field is removed). */
export function compact(values: SectionValues): SectionValues {
  const out: SectionValues = {};
  for (const [key, value] of Object.entries(values)) {
    if (!isEmptyValue(value)) {
      out[key] = sanitizeComponentValue(value);
    }
  }
  return out;
}

// ---- validation (limits of docs/api/opd.yaml EmrValueKind) ----

const DEFAULT_NUMBER_MIN = -1_000_000;
const DEFAULT_NUMBER_MAX = 1_000_000;
const TEXT_MAX = 2000;
const RICH_TEXT_MAX = 10000;
const CHOICE_FREE_MAX = 200;
const LIST_MAX = 50;
const CELL_MAX = 500;
const UNIT_MAX = 20;

function numberProblem(value: unknown, field: EmrFieldDefinition): string | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return 'Enter a number.';
  }
  const decimals = String(value).split('.')[1]?.length ?? 0;
  if (decimals > 4) {
    return 'Use at most 4 decimal places.';
  }
  const min = field.min ?? DEFAULT_NUMBER_MIN;
  const max = field.max ?? DEFAULT_NUMBER_MAX;
  if (value < min || value > max) {
    return `Enter a number from ${min} to ${max}.`;
  }
  return null;
}

/** A plain sentence when the value is not allowed for the field, else null. Empty values are always fine. */
export function fieldProblem(field: EmrFieldDefinition, value: unknown): string | null {
  if (isEmptyValue(value)) {
    return null;
  }
  switch (field.valueKind) {
    case 'NUMBER':
      return numberProblem(value, field);
    case 'NUMBER_UNIT': {
      if (!isRecord(value)) {
        return 'Enter a number.';
      }
      const unit = value['unit'];
      if (typeof unit === 'string' && unit.length > UNIT_MAX) {
        return `Use at most ${UNIT_MAX} characters for the unit.`;
      }
      return numberProblem(value['value'], field);
    }
    case 'TEXT': {
      const max = field.type === 'RICH_TEXT' ? RICH_TEXT_MAX : TEXT_MAX;
      return typeof value === 'string' && value.length > max ? `Use at most ${max} characters.` : null;
    }
    case 'DATE':
      return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) ? null : 'Enter a date.';
    case 'CHOICE':
      return typeof value === 'string' && value.length > CHOICE_FREE_MAX
        ? `Use at most ${CHOICE_FREE_MAX} characters.`
        : null;
    case 'MULTI_CHOICE':
      return Array.isArray(value) && value.length > LIST_MAX ? `Pick at most ${LIST_MAX}.` : null;
    case 'ITEM_LIST':
      if (Array.isArray(value) && value.length > LIST_MAX) {
        return `Add at most ${LIST_MAX}.`;
      }
      return null;
    case 'TABLE': {
      if (!Array.isArray(value)) {
        return null;
      }
      if (value.length > LIST_MAX) {
        return `Add at most ${LIST_MAX} rows.`;
      }
      const tooLong = value.some(
        (row) => isRecord(row) && Object.values(row).some((cell) => typeof cell === 'string' && cell.length > CELL_MAX),
      );
      return tooLong ? `Use at most ${CELL_MAX} characters in a cell.` : null;
    }
    case 'COMPONENT':
      return componentProblem(value);
    case 'STRUCTURED': {
      const text = isRecord(value) ? value['text'] : null;
      return typeof text === 'string' && text.length > TEXT_MAX ? `Use at most ${TEXT_MAX} characters.` : null;
    }
    default:
      return null;
  }
}

const COMPONENT_LIMITS = { EMR_LIST: 50, PRESCRIPTION: 30, BODY_MAP: 60 } as const;

function componentProblem(value: unknown): string | null {
  if (!isComponentValue(value)) {
    return null;
  }
  switch (value.component) {
    case 'EMR_LIST':
      return value.items.length > COMPONENT_LIMITS.EMR_LIST ? `Add at most ${COMPONENT_LIMITS.EMR_LIST}.` : null;
    case 'PRESCRIPTION':
      return value.rows.length > COMPONENT_LIMITS.PRESCRIPTION ? `Add at most ${COMPONENT_LIMITS.PRESCRIPTION} medicines.` : null;
    case 'BODY_MAP':
      return value.pins.length > COMPONENT_LIMITS.BODY_MAP ? `Add at most ${COMPONENT_LIMITS.BODY_MAP} pins.` : null;
    default:
      return null;
  }
}

export function sectionProblems(
  section: Pick<EmrLayoutSection, 'fields'> & Partial<Pick<EmrLayoutSection, 'source' | 'form'>>,
  values: SectionValues,
): Record<string, string> {
  if (section.source === 'FORM' && section.form) {
    return formProblems(section.form, values);
  }
  const out: Record<string, string> = {};
  for (const field of section.fields) {
    if (!isFillable(field)) {
      continue;
    }
    const problem = fieldProblem(field, values[field.key]);
    if (problem) {
      out[field.key] = problem;
    }
  }
  return out;
}

// ---- vitals limits (SAH-47-3, tagged rule: EMR mockup, accepted in DEC-039, not clinically reviewed) ----

export interface VitalFlag {
  readonly text: 'High' | 'Low' | 'Fever';
  readonly tone: 'crit' | 'warn';
}

function numberOf(value: unknown): number | null {
  const raw = isRecord(value) ? value['value'] : value;
  return typeof raw === 'number' && Number.isFinite(raw) ? raw : null;
}

/**
 * Adult limits from the mockup: BP systolic 140 or above High, below 90 Low; diastolic 90 or above
 * High; pulse above 100 High, below 60 Low; SpO2 below 95 Low; temperature 99.5 or above Fever,
 * below 96 Low; respiratory rate above 20 High. The field is found by its key; the flag is text as
 * well as colour. Children's limits stay the adult ones until SAH-62 (OQ-43-23).
 */
export function vitalFlag(fieldKey: string, value: unknown): VitalFlag | null {
  const n = numberOf(value);
  if (n === null) {
    return null;
  }
  const key = fieldKey.toLowerCase();
  if (/(^|_)(bp_?)?(systolic|sys)(_|$)/.test(key)) {
    return n >= 140 ? high() : n < 90 ? low() : null;
  }
  if (/(^|_)(bp_?)?(diastolic|dia)(_|$)/.test(key)) {
    return n >= 90 ? high() : null;
  }
  if (/(^|_)(pulse|pr|heart_rate)(_|$)/.test(key)) {
    return n > 100 ? high() : n < 60 ? low() : null;
  }
  if (/spo2/.test(key)) {
    return n < 95 ? low() : null;
  }
  if (/(^|_)(temp|temperature)(_|$)/.test(key)) {
    return n >= 99.5 ? { text: 'Fever', tone: 'crit' } : n < 96 ? low() : null;
  }
  if (/(^|_)(rr|resp|respiratory)(_|$)/.test(key)) {
    return n > 20 ? high() : null;
  }
  return null;
}

function high(): VitalFlag {
  return { text: 'High', tone: 'crit' };
}

function low(): VitalFlag {
  return { text: 'Low', tone: 'warn' };
}

// ---- header words ----

export const TITLE_BY_ENCOUNTER = {
  OPD_VISIT: 'Consultation',
  IPD_ROUND: 'Daily progress note',
  ER_ASSESSMENT: 'Emergency assessment',
} as const;

export function ordinal(n: number): string {
  const rem100 = n % 100;
  if (rem100 >= 11 && rem100 <= 13) {
    return `${n}th`;
  }
  switch (n % 10) {
    case 1:
      return `${n}st`;
    case 2:
      return `${n}nd`;
    case 3:
      return `${n}rd`;
    default:
      return `${n}th`;
  }
}

export function visitLine(type: string | null, ordinalNo: number | null): string | null {
  const word = type === 'NEW' ? 'New' : type === 'FOLLOW_UP' ? 'Follow-up' : type === 'REVISIT' ? 'Revisit' : type === 'EMERGENCY' ? 'Emergency' : null;
  if (!word) {
    return null;
  }
  return ordinalNo ? `${word}, ${ordinal(ordinalNo)} visit` : word;
}

export function lastVisitText(days: number | null): string | null {
  if (days === null) {
    return null;
  }
  return days === 0 ? 'Last visit: today' : `Last visit: ${days} ${days === 1 ? 'day' : 'days'} ago`;
}
