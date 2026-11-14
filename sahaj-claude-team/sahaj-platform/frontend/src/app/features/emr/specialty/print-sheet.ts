import { ageText } from '../../../shared/format/patient-age';
import { EmrCasePaperContext } from '../emr-phase3.model';
import { printPaper } from '../paper/print-paper';

/**
 * A one-page sheet printed by the browser (the home exercise programme, the physiotherapy progress
 * report, the dental estimate): a clinic header, a title, the patient line and headed lists. The sheet
 * is built from text nodes only, copied into the print container, and removed when printing is done:
 * nothing is stored, sent or logged (SAH-63-4, BR45-5).
 */
export interface PrintSheet {
  clinicName: string;
  clinicLines: string[];
  title: string;
  patientLine: string;
  sections: { heading: string; lines: string[] }[];
  footer?: string;
}

export function buildSheet(doc: Document, sheet: PrintSheet): HTMLElement {
  const root = doc.createElement('section');
  const add = (tag: string, text: string): HTMLElement => {
    const el = doc.createElement(tag);
    el.textContent = text;
    root.appendChild(el);
    return el;
  };
  add('h1', sheet.clinicName);
  for (const line of sheet.clinicLines.filter((l) => l)) {
    add('p', line);
  }
  add('h2', sheet.title);
  add('p', sheet.patientLine);
  for (const s of sheet.sections) {
    add('h3', s.heading);
    if (s.lines.length === 0) {
      add('p', '—');
    }
    for (const line of s.lines) {
      add('p', line);
    }
  }
  if (sheet.footer) {
    add('p', sheet.footer);
  }
  return root;
}

/** Builds the sheet and opens the browser print dialog. False when the browser could not print. */
export function printSheet(doc: Document, sheet: PrintSheet): boolean {
  const win = doc.defaultView;
  if (!win) {
    return false;
  }
  return printPaper(doc, win, buildSheet(doc, sheet), 'A4');
}

/**
 * The clinic header and the patient line of a printed sheet, from the case paper context (screen and
 * print only). The Paediatrics sheet prints the age as the header does (months for a young child).
 */
export function contextSheet(context: EmrCasePaperContext | null, paediatrics: boolean, title: string, sections: PrintSheet['sections'], footer?: string): PrintSheet {
  const p = context?.patient;
  const age = paediatrics && p?.ageDetail ? ageText(p.ageDetail, 'header') : p?.age != null ? `${p.age} y` : null;
  const patientLine = p ? [p.name, age, p.sex, p.uhid].filter((x) => x !== null && x !== undefined && x !== '').join(' · ') : '';
  return {
    clinicName: context?.letterhead.clinicName ?? '',
    clinicLines: [context?.letterhead.address ?? '', context?.letterhead.phone ?? ''],
    title,
    patientLine,
    sections,
    footer,
  };
}
