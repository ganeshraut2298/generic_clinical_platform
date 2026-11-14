import { formatDisplayDate } from '../../../shared/format/ist-date';
import { pinLine } from '../components/body-map.logic';
import { isComponentValue } from '../components/component-values.logic';
import { LIST_KINDS, choiceLabel } from '../components/emr-list.logic';
import { howOften, whenLabel } from '../components/prescription.logic';
import { EmrFieldDefinition, EmrLayoutSection } from '../emr.model';
import {
  EmrBodyMapValue,
  EmrCasePaperContext,
  EmrCasePaperHeading,
  EmrListItem,
  EmrListValue,
  EmrPrescriptionValue,
  PrintLanguage,
} from '../emr-phase3.model';
import { EmrSpecialtyReference, EmrSpecialtyView } from '../specialty/specialty.model';
import { specialtyComponentLines } from './specialty-lines.logic';
import { FormValues, isFillable as isFormFillable, isVisible, optionsOf } from '../forms/form.logic';

/**
 * The live case paper (SAH-55; mockup #CasePaper), composed in the browser from the values on
 * screen by one shared pure function, so it changes as the doctor types and needs no save
 * (ADR-022 D9). The ten headings in three languages come from the server context (the mockup's
 * wording, tagged until a language reviewer checks them). Only sections with Print on prescription
 * reach the paper; inactive list items are left out; nothing is stored or logged.
 */

export const PAPER_HEADINGS: readonly EmrCasePaperHeading[] = [
  'CHIEF_COMPLAINTS',
  'ALLERGIES',
  'MEDICAL_HISTORY',
  'EXAMINATION',
  'DIAGNOSIS',
  'INVESTIGATIONS',
  'MEDICINES',
  'PROCEDURES',
  'ADVICE',
  'FOLLOW_UP',
];

export const NOT_RECORDED_YET = 'Not recorded yet';
export const REG_NOT_SET = 'Reg. no. not set';
export const NEW_PATIENT = 'New patient';
export const FOOTER_CAPITALS = 'Medicines in generic names, capital letters (NMC).';
export const FOOTER_ABHA = "This visit is saved as an OP consultation record and can be shared to the patient's ABHA.";
export const FOOTER_SIGNATURE = 'Not valid without signature.';
export const SHARE_NOTE = 'Needs the WhatsApp and ABHA services';
export const PRINT_BLOCKED_NOTE = 'Resolve the allergy alert first';

export const LANGUAGE_CHOICES: readonly { key: PrintLanguage; label: string }[] = [
  { key: 'EN', label: 'English' },
  { key: 'MR', label: 'मराठी' },
  { key: 'HI', label: 'हिंदी' },
];

export const LANGUAGE_NAME: Record<PrintLanguage, string> = { EN: 'English', MR: 'Marathi', HI: 'Hindi' };

export interface PaperRx {
  n: number;
  name: string;
  how: string;
  days: string;
  note: string;
}

export interface PaperRow {
  heading: EmrCasePaperHeading;
  label: string;
  lines: string[];
  rx: PaperRx[];
  filled: boolean;
}

export interface PaperModel {
  rows: PaperRow[];
  filledCount: number;
  vitalsLine: string | null;
  mlc: boolean;
}

export interface PaperInput {
  context: EmrCasePaperContext;
  sections: readonly EmrLayoutSection[];
  values: Readonly<Record<string, Record<string, unknown>>>;
  language: PrintLanguage;
  capitals: boolean;
  /** Marathi and Hindi advice text when the master has it; null means English is used. */
  adviceText: (item: EmrListItem, language: PrintLanguage) => string | null;
  /** The reprint's computed profile block and reference labels (ADR-025 D15); the live paper leaves them out. */
  specialty?: EmrSpecialtyView | null;
  specialtyReference?: EmrSpecialtyReference | null;
}

function numberOf(value: unknown): number | null {
  const raw = typeof value === 'object' && value !== null && 'value' in value ? (value as { value: unknown }).value : value;
  return typeof raw === 'number' && Number.isFinite(raw) ? raw : null;
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/** "BP 142/90 · P 82 · SpO₂ 98%" from the Vitals fields that are filled. */
export function vitalsLine(values: Readonly<Record<string, unknown>>): string | null {
  const parts: string[] = [];
  const sys = numberOf(values['bp_systolic']);
  const dia = numberOf(values['bp_diastolic']);
  if (sys !== null && dia !== null) {
    parts.push(`BP ${sys}/${dia}`);
  } else if (sys !== null) {
    parts.push(`BP ${sys}`);
  }
  const pulse = numberOf(values['pulse']);
  if (pulse !== null) {
    parts.push(`P ${pulse}`);
  }
  const spo2 = numberOf(values['spo2']);
  if (spo2 !== null) {
    parts.push(`SpO₂ ${spo2}%`);
  }
  const temp = numberOf(values['temperature']);
  if (temp !== null) {
    parts.push(`T ${temp}°F`);
  }
  const weight = numberOf(values['weight']);
  if (weight !== null) {
    parts.push(`Wt ${weight} kg`);
  }
  return parts.length > 0 ? parts.join(' · ') : null;
}

function listLines(value: EmrListValue, input: PaperInput): string[] {
  const spec = LIST_KINDS[value.kind];
  return value.items
    .filter((i) => i.active)
    .map((i) => {
      switch (value.kind) {
        case 'COMPLAINTS':
          return `${i.text} · ${choiceLabel(spec.attr1, i.attr1)}`;
        case 'DIAGNOSIS':
          return i.code ? `${i.text} (${i.code})` : i.text;
        case 'ADVICE': {
          const lang = (i.attr1 === 'MR' || i.attr1 === 'HI' ? i.attr1 : 'EN') as PrintLanguage;
          return (lang === 'EN' ? null : input.adviceText(i, lang)) ?? i.text;
        }
        case 'SERVICES': {
          const qty = Number(i.attr1);
          return qty > 1 ? `${i.text} × ${qty}` : i.text;
        }
        default:
          return i.text;
      }
    });
}

function rxModel(value: EmrPrescriptionValue, capitals: boolean): PaperRx[] {
  return value.rows.map((r, i) => ({
    n: i + 1,
    name: capitals ? r.name.toUpperCase() : r.name,
    how: [howOften(r), r.doseText ?? ''].filter(Boolean).join(' · '),
    days: `${r.days} ${r.days === 1 ? 'day' : 'days'}`,
    note: whenLabel(r.when),
  }));
}

function bodyLines(value: EmrBodyMapValue): string[] {
  const lines = value.pins.map(pinLine);
  return value.pins.some((p) => p.mlc) ? ['MLC case', ...lines] : lines;
}

export function scalarText(field: EmrFieldDefinition, value: unknown): string | null {
  if (value === null || value === undefined) {
    return null;
  }
  switch (field.valueKind) {
    case 'TEXT':
      return typeof value === 'string' && value.trim() ? value.trim() : null;
    case 'NUMBER':
      return typeof value === 'number' ? String(value) : null;
    case 'NUMBER_UNIT': {
      const n = numberOf(value);
      const unit = isObject(value) && typeof value['unit'] === 'string' ? value['unit'] : '';
      return n === null ? null : `${n} ${unit}`.trim();
    }
    case 'DATE':
      return typeof value === 'string' && value ? formatDisplayDate(value) : null;
    case 'BOOLEAN':
      return typeof value === 'boolean' ? (value ? 'Yes' : 'No') : null;
    case 'CHOICE':
      return typeof value === 'string' && value
        ? (field.options.find((o) => o.key === value)?.label ?? value)
        : null;
    case 'MULTI_CHOICE': {
      const picked = Array.isArray(value) ? (value as string[]) : [];
      return picked.length > 0 ? picked.map((k) => field.options.find((o) => o.key === k)?.label ?? k).join(', ') : null;
    }
    case 'TABLE': {
      const rows = Array.isArray(value) ? value.filter(isObject) : [];
      const texts = rows
        .map((r) => Object.values(r).filter((c) => typeof c === 'string' && c.trim()).join(' '))
        .filter((t) => t);
      return texts.length > 0 ? texts.join('; ') : null;
    }
    case 'STRUCTURED':
      return isObject(value) && typeof value['text'] === 'string' && value['text'].trim() ? String(value['text']).trim() : null;
    default:
      return null;
  }
}

function sectionLines(section: EmrLayoutSection, values: Record<string, unknown>, input: PaperInput): { lines: string[]; rx: PaperRx[] } {
  const lines: string[] = [];
  let rx: PaperRx[] = [];
  if (section.source === 'FORM' && section.form) {
    const def = section.form;
    for (const f of def.fields) {
      if (!f.print || !isFormFillable(f) || !isVisible(f, def.fields, values as FormValues)) {
        continue;
      }
      const v = values[f.key];
      if (isComponentValue(v)) {
        if (v.component === 'BODY_MAP') {
          lines.push(...bodyLines(v));
        } else if (v.component === 'EMR_LIST') {
          lines.push(...listLines(v, input));
        } else if (v.component === 'PRESCRIPTION') {
          rx = rx.concat(rxModel(v, input.capitals));
        } else {
          lines.push(...specialtyComponentLines(v, input.specialty, input.specialtyReference));
        }
        continue;
      }
      let text: string | null = null;
      if (typeof v === 'boolean') {
        text = v ? 'Yes' : 'No';
      } else if (Array.isArray(v) && v.length > 0) {
        text = v.map((k) => optionsOf(f).find((o) => o.key === k)?.label ?? String(k)).join(', ');
      } else if (typeof v === 'string' && v.trim()) {
        text = optionsOf(f).find((o) => o.key === v)?.label ?? v.trim();
      } else if (typeof v === 'number') {
        text = f.unit ? `${v} ${f.unit}` : String(v);
      }
      if (text) {
        lines.push(`${f.label}: ${text}`);
      }
    }
    return { lines, rx };
  }
  for (const field of section.fields) {
    const v = values[field.key];
    if (field.valueKind === 'COVERED' || v === undefined || v === null) {
      continue;
    }
    if (field.valueKind === 'COMPONENT' && isComponentValue(v)) {
      if (v.component === 'EMR_LIST') {
        lines.push(...listLines(v, input));
      } else if (v.component === 'PRESCRIPTION') {
        rx = rx.concat(rxModel(v, input.capitals));
      } else if (v.component === 'BODY_MAP') {
        lines.push(...bodyLines(v));
      } else {
        lines.push(...specialtyComponentLines(v, input.specialty, input.specialtyReference));
      }
    } else if (field.valueKind === 'ITEM_LIST' && Array.isArray(v)) {
      lines.push(...v.filter(isObject).map((e) => String(e['text'] ?? '')).filter((t) => t));
    } else {
      const text = scalarText(field, v);
      if (text) {
        lines.push(section.fields.length > 1 && field.valueKind !== 'TEXT' ? `${field.label}: ${text}` : text);
      }
    }
  }
  return { lines, rx };
}

export function composePaper(input: PaperInput): PaperModel {
  const headingOf = new Map(input.context.sectionHeadings.map((h) => [h.sectionKey, h.heading]));
  const names = new Map(input.context.headings.map((h) => [h.heading, h]));
  const byHeading = new Map<EmrCasePaperHeading, { lines: string[]; rx: PaperRx[] }>();
  let vitals: string | null = null;
  let mlc = false;
  for (const section of input.sections) {
    if (!section.print || !section.shown) {
      continue;
    }
    const heading = headingOf.get(section.key) ?? 'NONE';
    if (heading === 'NONE') {
      continue;
    }
    const values = input.values[section.key] ?? {};
    if (heading === 'VITALS_LINE') {
      vitals = vitalsLine(values) ?? vitals;
      continue;
    }
    const { lines, rx } = sectionLines(section, values, input);
    mlc = mlc || lines.includes('MLC case');
    const acc = byHeading.get(heading) ?? { lines: [], rx: [] };
    acc.lines.push(...lines);
    acc.rx.push(...rx);
    byHeading.set(heading, acc);
  }
  const rows: PaperRow[] = PAPER_HEADINGS.map((heading) => {
    const acc = byHeading.get(heading) ?? { lines: [], rx: [] };
    const entry = names.get(heading);
    const label = entry ? (input.language === 'MR' ? entry.mr : input.language === 'HI' ? entry.hi : entry.en) : heading;
    // Medicines are numbered separately; their rows are renumbered when several sections add rows.
    const rx = acc.rx.map((r, i) => ({ ...r, n: i + 1 }));
    return { heading, label, lines: acc.lines, rx, filled: acc.lines.length > 0 || rx.length > 0 };
  });
  return { rows, filledCount: rows.filter((r) => r.filled).length, vitalsLine: vitals, mlc };
}

/** "Hide empty": headings without content disappear from the screen and the print; the count does not change. */
export function visibleRows(model: PaperModel, hideEmpty: boolean): PaperRow[] {
  return hideEmpty ? model.rows.filter((r) => r.filled) : model.rows;
}

export function subtitle(model: PaperModel | null): string {
  return `${model?.filledCount ?? 0} of 10 filled · live`;
}

/** "Not valid without signature." goes away once the record is signed (SAH-51-6). */
export function footerLines(capitals: boolean, abhaSharing: boolean, signed = false): string[] {
  return [...(capitals ? [FOOTER_CAPITALS] : []), ...(abhaSharing ? [FOOTER_ABHA] : []), ...(signed ? [] : [FOOTER_SIGNATURE])];
}

/** "Ready to complete. Prescription prints in Marathi with English drug names." (SAH-48-3). */
export function readyFooter(language: PrintLanguage | null): string {
  return language ? `Ready to complete. Prescription prints in ${LANGUAGE_NAME[language]} with English drug names.` : 'Ready to complete.';
}
