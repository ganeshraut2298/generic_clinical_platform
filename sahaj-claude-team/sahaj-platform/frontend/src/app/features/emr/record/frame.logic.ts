import { EmrLayoutSection, EmrPart } from '../emr.model';
import { EmrSpecialtyProfile, EmrSpecialtyView, EmrStillNeededItem } from '../specialty/specialty.model';
import { ProfileChrome } from '../specialty/specialty-registry';

/**
 * The words of the doctor screen frame (SAH-75-1, SAH-75-2, ADR-026 D1): sentence templates filled from
 * the published layout's section names and the visit's data. The design's example names are never
 * hard-coded; a section the layout does not have is not named. Pure functions, tested alone.
 * Values from the EMR mockup, not clinically reviewed.
 */

/** "Still needed: a, b." with the names in lower case, as drawn. */
export function stillNeededText(names: readonly string[]): string {
  return `Still needed: ${names.join(', ')}.`;
}

export interface BadgeCounts {
  readonly done: number;
  readonly total: number;
}

/** "n of m required" (every profile but oncology) or "n of m done" (oncology steps). */
export function badgeText(word: ProfileChrome['badgeWord'], counts: BadgeCounts): string {
  return `${counts.done} of ${counts.total} ${word}`;
}

/** A tick replaces the number when every counted section of the part is done. */
export function badgeFull(counts: BadgeCounts): boolean {
  return counts.done >= counts.total;
}

const ONCO_STEP_KEYS: Readonly<Record<EmrPart, readonly string[]>> = {
  ASSESSMENT: ['onc_tox', 'onc_bsa', 'ONC_DECISION'],
  TREATMENT: ['diagnosis', 'onc_regimen'],
};

/**
 * Oncology counts steps, not required flags ("n of 3 done" in step 1: side effect grades, systemic exam,
 * today's decision; "n of 2 done" in step 2: diagnosis, the chemo order or the hold). A step the layout
 * does not have is not counted; a step still in the footer list is not done (O-8).
 */
export function oncoCounts(part: EmrPart, layout: readonly EmrLayoutSection[], items: readonly EmrStillNeededItem[]): BadgeCounts {
  const keys = ONCO_STEP_KEYS[part];
  const has = (key: string): boolean => (key === 'ONC_DECISION' ? layout.some((s) => s.key === 'onc_tox' || s.key === 'onc_labs') : layout.some((s) => s.key === key));
  const total = keys.filter(has).length;
  const missing = items.filter((i) => keys.includes(i.key) && has(i.key)).length;
  return { done: Math.max(0, total - missing), total };
}

/**
 * The number ahead of a card title ("3 · Pre-chemo labs"): the position of the card among the numbered cards that
 * the layout has, in the layout's order, so a clinic that moved or removed a card never sees a wrong number.
 * Null for a card that is not numbered.
 */
export function cardNumber(numbered: readonly string[] | undefined, layoutKeys: readonly string[], key: string): number | null {
  if (!numbered?.includes(key)) {
    return null;
  }
  const present = layoutKeys.filter((k) => numbered.includes(k));
  const index = present.indexOf(key);
  return index < 0 ? null : index + 1;
}

export interface AssessmentBarInput {
  readonly profile: EmrSpecialtyProfile | null;
  /** Lower-case names of the required Assessment sections not done. */
  readonly missing: readonly string[];
  readonly done: boolean;
  readonly hasComplaints: boolean;
  readonly view: EmrSpecialtyView | null;
}

const NEXT_AFTER_ASSESSMENT: Partial<Record<EmrSpecialtyProfile, string>> = {
  gm: 'diagnosis, medicines, tests and services',
  dental: 'diagnosis, chart, plan and payments',
};

/** The dark bar under the Assessment part (mockup aFooter): what is still needed, or what comes next. */
export function assessmentBar(i: AssessmentBarInput): string {
  if (i.profile === 'surgery') {
    const fit = i.view?.surgery?.fitness.text?.replace(/\.$/, '') ?? '';
    return `${i.hasComplaints ? '' : 'Add complaints. '}${fit}${fit ? '.' : ''}`.trim();
  }
  const next = i.profile ? NEXT_AFTER_ASSESSMENT[i.profile] : undefined;
  if (!next) {
    return i.done ? 'Assessment done.' : 'Still needed in assessment. You can still go ahead.';
  }
  return i.done ? `Assessment done. Next: ${next}.` : `Still needed in assessment: ${i.missing.join(', ')}. You can still go ahead.`;
}

export interface ReadyFooterInput {
  readonly profile: EmrSpecialtyProfile | null;
  readonly mode: string | null;
  /** "Marathi" or null while the case paper has not loaded. */
  readonly languageName: string | null;
  readonly view: EmrSpecialtyView | null;
  /** The doctor's "Next ANC at" weeks, if set. */
  readonly nextAncWeeks: number | null;
  readonly vaccinesToday: number;
  readonly medicines: number;
}

const READY = 'Ready to complete.';

/** The green sentence of the sticky footer when nothing is still needed (mockup footer, one per drawn department). */
export function readyFooterText(i: ReadyFooterInput): string {
  switch (i.profile) {
    case 'dental':
      return `${i.view?.dental?.doneTodayRowIds.length ?? 0} procedure(s) done today will be billed.`;
    case 'og': {
      if (i.mode === 'GYNAE') {
        return 'Gynae visit: menstrual history required.';
      }
      const warnings = i.view?.og?.alerts.length ?? 0;
      if (warnings > 0) {
        return `${warnings} warning(s) on this visit, reviewed before completing.`;
      }
      const weeks = i.view?.og?.gestationalAge?.weeks ?? null;
      const next = i.nextAncWeeks ?? (weeks !== null ? weeks + 3 : null);
      return next !== null && weeks !== null
        ? `Next ANC visit suggested at ${next} weeks (in ${Math.max(0, next - weeks)} weeks).`
        : READY;
    }
    case 'paeds':
      return `${i.vaccinesToday} vaccine(s) given today · ${i.medicines} medicine(s) prescribed`;
    case 'physio': {
      const n = i.view?.physio?.sessionNo ?? 1;
      return `Completing marks session ${n} as used and books session ${n + 1}.`;
    }
    case 'surgery':
      return `${READY} Admission letter and pre-op instructions will print.`;
    case 'onco':
      return `${READY} Cycle record, take-home medicines and calendar will print.`;
    default:
      return i.languageName ? `${READY} Prescription prints in ${i.languageName} with English drug names.` : READY;
  }
}

/** Dental: "Remove Amoxicillin before completing (penicillin allergy)." in place of the general allergy sentence. */
export function dentalAllergyFooter(drugName: string): string {
  return `Remove ${drugName} before completing (penicillin allergy).`;
}

/** The right-hand tab lines (SAH-75-1). */
export const RIGHT_TAB_SUBTITLE = { history: 'OPD + IPD', tools: 'Helpers for this visit' } as const;

export interface GmRecapInput {
  /** Complaint items with their duration, as "text (duration)" or just the text. */
  readonly complaints: readonly string[];
  readonly bp: string | null;
  readonly pulse: number | null;
  readonly pins: readonly { readonly mlc: boolean }[];
}

/**
 * The Treatment recap of General Medicine (mockup line 713): "From assessment: <complaints>, BP .., PR ..
 * (or general exam not recorded) · n local finding(s) incl. MLC". The words after "From assessment:" only.
 */
export interface SurgeryRecapInput {
  readonly complaints: readonly string[];
  readonly murphy: 'POSITIVE' | 'NEGATIVE' | null;
  readonly asa: string | null;
  readonly fitness: string;
}

/** General Surgery, Treatment part (SAH-75-11 G-S5): "<complaints> · Murphy <value> · ASA <value> · <fitness>". The words after "From assessment:" only. */
export function surgeryRecap(i: SurgeryRecapInput): string {
  const parts = [i.complaints.length > 0 ? i.complaints.join(', ') : 'no complaints'];
  if (i.murphy) {
    parts.push(`Murphy ${i.murphy === 'POSITIVE' ? 'positive' : 'negative'}`);
  }
  if (i.asa) {
    parts.push(`ASA ${i.asa}`);
  }
  parts.push(i.fitness);
  return parts.join(' · ');
}

export function gmRecap(i: GmRecapInput): string {
  const complaints = i.complaints.length > 0 ? i.complaints.join(', ') : 'no complaints';
  const exam = i.bp !== null && i.pulse !== null ? `BP ${i.bp}, PR ${i.pulse}` : 'general exam not recorded';
  const parts = [complaints, exam];
  if (i.pins.length > 0) {
    parts.push(`${i.pins.length} local finding(s)${i.pins.some((p) => p.mlc) ? ' incl. MLC' : ''}`);
  }
  return parts.join(' · ');
}
