import { EmrReprint } from '../emr-finish.model';
import { EmrLayoutSection } from '../emr.model';
import { PaperInput, PaperModel, composePaper } from '../paper/case-paper.logic';
import { vaccinationCard } from '../specialty/paeds/vaccination-card.logic';
import { PrintSheet, contextSheet } from '../specialty/print-sheet';

/**
 * The reprint of a signed prescription (SAH-51-6; ADR-025 D15) is composed by the same function as the
 * live case paper. Since opd.yaml 0.6.1 the answer carries the frozen layout of the printed sections
 * (field definitions, option labels, form definitions), the profile's computed block as of the signing
 * day and the profile's reference labels, so every stored key prints as its word, as on the doctor's
 * paper. Nothing is stored or logged.
 */

/** The printed sections in the answer's order, each taken from the frozen layout; the server already left out the rest. */
export function reprintSections(reprint: EmrReprint): EmrLayoutSection[] {
  const byKey = new Map(reprint.layout.sections.map((s) => [s.key, s]));
  return reprint.sections.flatMap((printed, i) => {
    const section = byKey.get(printed.key);
    return section ? [{ ...section, position: i, shown: true, print: true }] : [];
  });
}

export function reprintPaper(reprint: EmrReprint, adviceText: PaperInput['adviceText']): PaperModel {
  const values: Record<string, Record<string, unknown>> = {};
  for (const s of reprint.sections) {
    values[s.key] = s.values;
  }
  const language = reprint.context.chosen.language ?? reprint.context.clinicDefaults.language;
  return composePaper({
    context: { ...reprint.context, sectionHeadings: reprint.sections.map((s) => ({ sectionKey: s.key, heading: s.heading })) },
    sections: reprintSections(reprint),
    values,
    language,
    capitals: reprint.context.clinicDefaults.capitals,
    adviceText,
    specialty: reprint.specialty,
    specialtyReference: reprint.specialtyReference,
  });
}

/** "Print vaccination card" is offered on a Paediatrics reprint (SAH-62-4: front desk reprints a signed card here). */
export function canPrintVaccinationCard(reprint: EmrReprint): boolean {
  return reprint.specialty?.profile === 'paeds' && !!reprint.specialty.paeds;
}

/** The card of the signed visit, read as on the signing day (H20): every vaccine with its date or state, and the next due. */
export function reprintVaccinationCard(reprint: EmrReprint): PrintSheet | null {
  const paeds = reprint.specialty?.paeds;
  if (!paeds) {
    return null;
  }
  const lists = reprint.specialtyReference?.lists;
  const vaccines = lists?.iapVaccines ?? [];
  const groups = (lists?.iapGroups ?? []).map((g) => ({
    key: g.key,
    label: g.label,
    vaccines: vaccines.filter((v) => v.group === g.key).map((v) => ({ key: v.key, label: v.label })),
  }));
  // The pruned block (H32) may leave out any key but the immunization data; a missing key prints as "later" or nothing.
  const states = new Map((paeds.vaccines ?? []).map((v) => [v.vaccineKey, v]));
  return vaccinationCard({
    groups,
    stateOf: (key) => states.get(key)?.state ?? 'LATER',
    givenOn: (key) => states.get(key)?.givenOn,
    nextDue: paeds.nextDue ?? null,
    dateOfBirth: paeds.dateOfBirth ?? null,
    sheetOf: (title, sections, footer) => contextSheet(reprint.context, true, title, sections, footer),
  });
}
