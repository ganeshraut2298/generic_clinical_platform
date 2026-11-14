import { formatDisplayDate } from '../../../../shared/format/ist-date';
import { PrintSheet } from '../print-sheet';

/**
 * The vaccination card (SAH-62-4): every vaccine of the schedule with its date given or its state, and
 * the next due vaccine. One builder for the Immunization editor's "Print vaccination card" and for the
 * Paediatrics reprint of a signed visit (ADR-025 D15, D19), so the two cards read the same. The card
 * prints the child's date of birth; nothing is stored or logged.
 */

export type VaccineCardState = 'GIVEN' | 'TODAY' | 'DUE_TODAY' | 'OVERDUE' | 'LATER';

export interface VaccineCardGroup {
  key: string;
  label: string;
  vaccines: { key: string; label: string }[];
}

export function vaccineStateText(state: VaccineCardState, givenOn: string | null | undefined): string {
  if (state === 'TODAY') {
    return 'given today';
  }
  if (state === 'GIVEN') {
    return givenOn ? `given ${formatDisplayDate(givenOn)}` : 'given';
  }
  return state === 'DUE_TODAY' ? 'due today' : state === 'OVERDUE' ? 'overdue' : 'later';
}

export interface VaccinationCardInput {
  groups: readonly VaccineCardGroup[];
  stateOf: (vaccineKey: string) => VaccineCardState;
  givenOn: (vaccineKey: string) => string | null | undefined;
  nextDue: { groupLabel: string } | null | undefined;
  dateOfBirth: string | null | undefined;
  /** The clinic header and patient line (from the case paper context). */
  sheetOf: (title: string, sections: PrintSheet['sections'], footer?: string) => PrintSheet;
}

export function vaccinationCard(input: VaccinationCardInput): PrintSheet {
  const sections = input.groups.map((g) => ({
    heading: g.label,
    lines: g.vaccines.map((v) => `${v.label} — ${vaccineStateText(input.stateOf(v.key), input.givenOn(v.key))}`),
  }));
  const sheet = input.sheetOf('Vaccination card', sections, input.nextDue ? `Next due: ${input.nextDue.groupLabel}` : undefined);
  return input.dateOfBirth
    ? { ...sheet, patientLine: [sheet.patientLine, `Date of birth ${formatDisplayDate(input.dateOfBirth)}`].filter((x) => x).join(' · ') }
    : sheet;
}
