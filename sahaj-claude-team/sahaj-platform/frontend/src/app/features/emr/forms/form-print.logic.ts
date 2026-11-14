import { pinLine } from '../components/body-map.logic';
import { isComponentValue } from '../components/component-values.logic';
import { frequencyLabel } from '../components/prescription.logic';
import { EmrFormDefinition, EmrFormField, EmrFormPrintLanguage, EmrFormVitalsValue } from '../emr-phase3.model';
import { FormValues, bandOf, isVisible, optionsOf, scoreTotal } from './form.logic';

/**
 * What the printed form holds (SAH-59-5): the fields with "Print on paper" on that are shown, with
 * the values of the Preview, and the standard words in a second language when the form's print
 * language asks for it. Printing is the browser's (no PDF). The Marathi and Hindi words below are
 * UI words that the mockup does not give: they are tagged "not reviewed" and wait for a language
 * reviewer (DEC-039 answer 4 applies to the case paper headings only).
 */

export type PrintWord = 'patient' | 'ageSex' | 'uhid' | 'signature' | 'regNo';

// Not in the mockup. Tag: wording to be checked by a language reviewer before real use.
const WORDS: Record<PrintWord, { en: string; mr: string; hi: string }> = {
  patient: { en: 'Patient', mr: 'रुग्ण', hi: 'मरीज़' },
  ageSex: { en: 'Age / sex', mr: 'वय / लिंग', hi: 'आयु / लिंग' },
  uhid: { en: 'UHID', mr: 'UHID', hi: 'UHID' },
  signature: { en: 'Signature', mr: 'स्वाक्षरी', hi: 'हस्ताक्षर' },
  regNo: { en: 'Reg. no.', mr: 'नोंदणी क्र.', hi: 'पंजीकरण सं.' },
};

export function word(key: PrintWord, language: EmrFormPrintLanguage): string {
  const w = WORDS[key];
  if (language === 'EN_MR') {
    return w.mr === w.en ? w.en : `${w.en} / ${w.mr}`;
  }
  if (language === 'EN_HI') {
    return w.hi === w.en ? w.en : `${w.en} / ${w.hi}`;
  }
  return w.en;
}

/** The field label in English and, when the owner typed one, in the second language too. */
export function labelFor(field: EmrFormField, language: EmrFormPrintLanguage): string {
  const second = language === 'EN_MR' ? field.labelMr : language === 'EN_HI' ? field.labelHi : null;
  return second?.trim() ? `${field.label} / ${second.trim()}` : field.label;
}

export interface PrintRow {
  kind: 'heading' | 'note' | 'field';
  label: string;
  value: string;
}

const BLANK = '________________';

function optionLabel(field: EmrFormField, key: string): string {
  return optionsOf(field).find((o) => o.key === key)?.label ?? key;
}

function vitalsText(v: EmrFormVitalsValue): string {
  const parts: string[] = [];
  if (v.bpSystolic != null && v.bpDiastolic != null) {
    parts.push(`BP ${v.bpSystolic}/${v.bpDiastolic}`);
  }
  if (v.pulse != null) {
    parts.push(`P ${v.pulse}`);
  }
  if (v.spo2 != null) {
    parts.push(`SpO₂ ${v.spo2}%`);
  }
  if (v.temperatureF != null) {
    parts.push(`T ${v.temperatureF}°F`);
  }
  if (v.weightKg != null) {
    parts.push(`Wt ${v.weightKg} kg`);
  }
  if (v.heightCm != null) {
    parts.push(`Ht ${v.heightCm} cm`);
  }
  return parts.join(' · ');
}

function valueText(field: EmrFormField, def: EmrFormDefinition, values: FormValues): string {
  const v = values[field.key];
  switch (field.type) {
    case 'SCORE': {
      const total = scoreTotal(field, def.fields, values);
      return total === null ? '' : String(total);
    }
    case 'SCORE_BAND':
      return bandOf(field, def.fields, values)?.label ?? '';
    case 'YES_NO':
      return typeof v === 'boolean' ? (v ? 'Yes' : 'No') : '';
    case 'SINGLE_CHOICE':
    case 'DROPDOWN':
      return typeof v === 'string' ? optionLabel(field, v) : '';
    case 'MULTI_CHOICE':
      return Array.isArray(v) ? v.map((k) => optionLabel(field, String(k))).join(', ') : '';
    case 'NUMBER':
      return typeof v === 'number' ? (field.unit ? `${v} ${field.unit}` : String(v)) : '';
    case 'VITALS_BLOCK':
      return typeof v === 'object' && v !== null ? vitalsText(v as EmrFormVitalsValue) : '';
    case 'BODY_MAP':
      return isComponentValue(v) && v.component === 'BODY_MAP' ? v.pins.map(pinLine).join('; ') : '';
    case 'MEDICINE_LIST':
      return isComponentValue(v) && v.component === 'PRESCRIPTION'
        ? v.rows.map((r) => `${r.name} ${frequencyLabel(r.frequency).split(' ')[0]} ${r.days}d`).join('; ')
        : '';
    case 'LAB_TESTS':
    case 'DIAGNOSIS':
      return isComponentValue(v) && v.component === 'EMR_LIST' ? v.items.filter((i) => i.active).map((i) => i.text).join('; ') : '';
    default:
      return typeof v === 'string' ? v : '';
  }
}

export function printRows(def: EmrFormDefinition, values: FormValues): PrintRow[] {
  const language = def.settings.printLanguage;
  return def.fields
    .filter((f) => f.print && isVisible(f, def.fields, values))
    .map<PrintRow>((f) => {
      if (f.type === 'SECTION_HEADING') {
        return { kind: 'heading', label: labelFor(f, language), value: '' };
      }
      if (f.type === 'INFO_NOTE') {
        return { kind: 'note', label: labelFor(f, language), value: '' };
      }
      return { kind: 'field', label: labelFor(f, language), value: valueText(f, def, values) || BLANK };
    });
}
