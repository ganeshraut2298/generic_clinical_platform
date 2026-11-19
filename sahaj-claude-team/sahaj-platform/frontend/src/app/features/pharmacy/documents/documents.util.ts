import { AbstractControl, ValidationErrors } from '@angular/forms';
import { istToday } from '../../../shared/format/ist-date';
import { DocumentSeriesInput, PrintDocType, PrintFixedField, PrintTemplate } from '../pharmacy.model';

// ---------------------------------------------------------------- numbering (78-10)

const DOC_TYPE_LABELS: Record<string, string> = {
  PURCHASE_ORDER: 'Purchase order',
  GRN: 'Goods receipt (GRN)',
  INDENT: 'Indent',
  TRANSFER: 'Stock transfer',
  BILL: 'Bill',
  DEBIT_NOTE: 'Debit note',
  STOCK_AUDIT: 'Stock audit',
  STORE_RETURN: 'Store return',
  DAMAGE_REPORT: 'Damage report',
  STOCK_ADJUSTMENT: 'Stock adjustment',
};

/** The name of a series. A type the client does not know yet is shown in plain words, never dropped. */
export function docTypeLabel(docType: string): string {
  if (DOC_TYPE_LABELS[docType]) {
    return DOC_TYPE_LABELS[docType];
  }
  const words = docType.toLowerCase().replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export const PREFIX_PATTERN = /^[A-Za-z0-9/-]{1,6}$/;
export const MAX_NUMBER_LENGTH = 16;
/** "26-27/" */
const FY_PART_LENGTH = 6;

/** The financial year part of today (April to March, IST): "26-27" from 24 Oct 2026. */
export function financialYearPart(today: string = istToday()): string {
  const [year, month] = today.split('-').map(Number);
  const start = month >= 4 ? year : year - 1;
  const two = (n: number) => String(n % 100).padStart(2, '0');
  return `${two(start)}-${two(start + 1)}`;
}

export interface SeriesDraft {
  prefix: string;
  financialYearPart: boolean;
  sequenceLength: number;
  restartsEachFinancialYear: boolean;
}

/** The live example: prefix, the financial year when on, and the first number padded to the length. */
export function seriesExample(draft: SeriesDraft, today: string = istToday()): string {
  const year = draft.financialYearPart ? `${financialYearPart(today)}/` : '';
  return `${draft.prefix}${year}${'1'.padStart(draft.sequenceLength, '0')}`;
}

export function renderedLength(draft: SeriesDraft): number {
  return draft.prefix.length + (draft.financialYearPart ? FY_PART_LENGTH : 0) + draft.sequenceLength;
}

export const SERIES_MESSAGES = {
  prefix: 'Use 1 to 6 letters, digits, - or /',
  length: 'Choose 4 to 8 digits',
  tooLong: 'The number must be 16 characters or fewer',
  restart: 'Show the financial year when numbers restart every 1 April',
  samePrefix: 'Two document types cannot use the same prefix',
  locked: 'Numbers have already been issued this year.',
} as const;

export interface SeriesErrors {
  prefix: string | null;
  sequenceLength: string | null;
  restart: string | null;
  tooLong: string | null;
}

/** The field rules of `DocumentSeriesInput`, with the contract's sentences. The server checks again. */
export function validateSeries(draft: SeriesDraft): SeriesErrors {
  const prefixOk = PREFIX_PATTERN.test(draft.prefix);
  const lengthOk =
    Number.isInteger(draft.sequenceLength) && draft.sequenceLength >= 4 && draft.sequenceLength <= 8;
  return {
    prefix: prefixOk ? null : SERIES_MESSAGES.prefix,
    sequenceLength: lengthOk ? null : SERIES_MESSAGES.length,
    restart:
      draft.restartsEachFinancialYear && !draft.financialYearPart ? SERIES_MESSAGES.restart : null,
    tooLong:
      prefixOk && lengthOk && renderedLength(draft) > MAX_NUMBER_LENGTH ? SERIES_MESSAGES.tooLong : null,
  };
}

export function seriesInput(version: number, draft: SeriesDraft): DocumentSeriesInput {
  return {
    version,
    prefix: draft.prefix,
    financialYearPart: draft.financialYearPart,
    sequenceLength: draft.sequenceLength,
    restartsEachFinancialYear: draft.restartsEachFinancialYear,
  };
}

// ---------------------------------------------------------------- print templates (78-10)

export const PRINT_DOC_LABELS: Record<PrintDocType, string> = {
  INVOICE: 'Invoice',
  ISSUE_SLIP: 'Issue slip',
  DEBIT_NOTE: 'Debit note',
};

export const FIXED_FIELD_LABELS: Record<PrintFixedField, string> = {
  UNIT_NAME: 'Unit name',
  DOCUMENT_NO: 'Document number',
  DATE: 'Date',
  GSTIN: 'GSTIN',
  DRUG_LICENCE_NO: 'Drug licence number',
  HSN: 'HSN',
  BATCH: 'Batch',
  EXPIRY: 'Expiry',
  MRP: 'MRP',
  GST_AMOUNTS: 'GST amounts',
};

export const HEADER_LINE_LIMIT = 80;
export const FOOTER_LIMIT = 200;

export function headerLineValidator(control: AbstractControl): ValidationErrors | null {
  return String(control.value ?? '').length > HEADER_LINE_LIMIT ? { maxlength: true } : null;
}

/**
 * What the missing unit settings say on an invoice (story 78-10): "Set GSTIN in Unit settings", and that
 * invoices cannot be printed until it is set. Empty when the template can be printed.
 */
export function missingNotes(template: PrintTemplate): string[] {
  if (template.printable || template.missing.length === 0) {
    return [];
  }
  const notes: string[] = template.missing.map((m) =>
    m === 'GSTIN' ? 'Set GSTIN in Unit settings' : 'Set the drug licence number in Unit settings',
  );
  notes.push(`${PRINT_DOC_LABELS[template.docType]}s cannot be printed until it is set.`);
  return notes;
}

export interface PreviewDraft {
  headerLines: string[];
  footerNote: string;
  paperSize: 'A4' | 'A5';
}

export interface PreviewLine {
  label: string;
  value: string;
}

export interface PreviewModel {
  title: string;
  header: string[];
  fields: PreviewLine[];
  footer: string;
  paperSize: 'A4' | 'A5';
}

/**
 * The sample the preview and the print view show. Every value is a sample: no real patient or stock data is
 * used, and the document number is marked "Sample" (story 78-10). Unit name comes from the clinic profile.
 */
export function previewModel(
  template: PrintTemplate,
  draft: PreviewDraft,
  unitName: string,
  dateText: string,
): PreviewModel {
  const typed = draft.headerLines.map((l) => l.trim()).filter((l) => l !== '');
  const header = typed.length > 0 ? typed : template.defaultHeaderLines;
  const fields: PreviewLine[] = template.fixedFields.map((field) => {
    switch (field) {
      case 'UNIT_NAME':
        return { label: FIXED_FIELD_LABELS[field], value: unitName };
      case 'DATE':
        return { label: FIXED_FIELD_LABELS[field], value: dateText };
      default:
        return { label: FIXED_FIELD_LABELS[field], value: 'Sample' };
    }
  });
  return {
    title: `${PRINT_DOC_LABELS[template.docType]} · Sample`,
    header,
    fields,
    footer: draft.footerNote.trim(),
    paperSize: safePaperSize(draft.paperSize),
  };
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** Only these two paper sizes reach the generated page; anything else prints as A4 (security I13). */
export function safePaperSize(value: unknown): 'A4' | 'A5' {
  return value === 'A5' ? 'A5' : 'A4';
}

/** A self-contained page for the browser print view. All text is escaped; no file comes from the server. */
export function printHtml(model: PreviewModel): string {
  const header = model.header.map((l) => `<div>${escapeHtml(l)}</div>`).join('');
  const rows = model.fields
    .map((f) => `<tr><th>${escapeHtml(f.label)}</th><td>${escapeHtml(f.value)}</td></tr>`)
    .join('');
  const footer = model.footer ? `<p class="foot">${escapeHtml(model.footer)}</p>` : '';
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'"><title>${escapeHtml(model.title)}</title><style>@page{size:${safePaperSize(model.paperSize)}}body{font-family:sans-serif;margin:24px}h1{font-size:18px}table{border-collapse:collapse;width:100%}th,td{border:1px solid #999;padding:6px;text-align:left}.foot{margin-top:16px;font-size:12px}</style></head><body><div>${header}</div><h1>${escapeHtml(model.title)}</h1><table>${rows}</table>${footer}</body></html>`;
}
