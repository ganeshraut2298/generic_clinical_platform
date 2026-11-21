import { formatDisplayDate, formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { rateText, rupees } from '../opening-stock/opening-stock.util';
import { ApiError } from '../../../core/http/problem-detail';
import { Chip, loadErrorMessage } from '../pharmacy-display.util';
import {
  ReportCell,
  ReportColumn,
  STOCK_AUDIT_STATUS_LABELS,
  StockAuditStatus,
} from '../pharmacy-stockcount.model';
import { TRANSFER_STATUS_LABELS, TransferStatus, TransferStep } from '../pharmacy-stockuse.model';

/** A quantity the server may hide from a blind counter (contract rule 24): null is shown as a dash. */
export function blindQty(value: number | null): string {
  return value === null ? '—' : String(value);
}

/** `− ₹ 412.00` for a loss, `+ ₹ 90.00` for a gain, `—` when the figure is not given (blind count). */
export function signedRupees(paise: number | null): string {
  if (paise === null) {
    return '—';
  }
  if (paise === 0) {
    return rupees(0);
  }
  return `${paise < 0 ? '−' : '+'} ${rupees(Math.abs(paise))}`;
}

export function signedQty(diff: number): string {
  return diff === 0 ? '0' : `${diff < 0 ? '−' : '+'}${Math.abs(diff)}`;
}

/** The status chip of a transfer row, `Not received · 26 h` when it has been in transit too long (mockup 12). */
export function transferChip(status: TransferStatus, notReceivedHours: number | null): Chip {
  if (status === 'IN_TRANSIT' && notReceivedHours !== null) {
    return { text: `Not received · ${notReceivedHours} h`, css: 'p-crit' };
  }
  const text = TRANSFER_STATUS_LABELS[status];
  switch (status) {
    case 'RECEIVED':
      return { text, css: 'p-good' };
    case 'IN_TRANSIT':
      return { text, css: 'p-wait' };
    case 'WAITING_APPROVAL':
      return { text, css: 'p-wait' };
    case 'APPROVED':
      return { text, css: 'p-teal' };
    case 'REJECTED':
      return { text, css: 'p-crit' };
    case 'CANCELLED':
      return { text, css: 'p-blue' };
  }
}

export interface StepView {
  label: string;
  hint: string;
  done: boolean;
}

/** The step bar of a transfer: Requested, Approved, Dispatched, Received (mockup 12). */
export function transferSteps(step: TransferStep, fromName: string, toName: string): StepView[] {
  const order: TransferStep[] = ['REQUESTED', 'APPROVED', 'DISPATCHED', 'RECEIVED'];
  const at = order.indexOf(step);
  const steps = [
    { label: 'Requested', hint: `${toName} pharmacist` },
    { label: 'Approved', hint: `${fromName} in-charge` },
    { label: 'Dispatched', hint: 'In transit · runner' },
    { label: 'Received', hint: 'Accept / reject like an indent' },
  ];
  return steps.map((s, i) => ({ ...s, done: i <= at }));
}

export function auditChip(status: StockAuditStatus): Chip {
  const text = STOCK_AUDIT_STATUS_LABELS[status];
  switch (status) {
    case 'COUNTING':
      return { text, css: 'p-wait' };
    case 'RECOUNT':
      return { text, css: 'p-warn' };
    case 'WAITING_REVIEW':
    case 'WAITING_APPROVAL':
      return { text, css: 'p-wait' };
    case 'CLOSED':
      return { text, css: 'p-good' };
  }
}

/** One report cell as the screen shows it (contract: PAISE and INTEGER are numbers, the rest are strings). */
export function reportCellText(column: ReportColumn, cell: ReportCell): string {
  if (cell === null || cell === '') {
    return '—';
  }
  switch (column.kind) {
    case 'PAISE':
      return typeof cell === 'number' ? rupees(cell) : String(cell);
    case 'RATE':
      return rateText(String(cell));
    case 'PERCENT':
      return `${cell}%`;
    case 'DATE':
      return formatDisplayDate(String(cell));
    case 'DATE_TIME':
      return formatDisplayDateTimeOfInstant(String(cell));
    default:
      return String(cell);
  }
}

export function isNumericColumn(column: ReportColumn): boolean {
  return column.kind === 'INTEGER' || column.kind === 'PAISE' || column.kind === 'RATE' || column.kind === 'PERCENT';
}

/** Saves a CSV answer as a file; the name is `sahaj-<key>-<date>.csv` (contract). Nothing is kept in the browser. */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

/** A CSV that is too large answers 409 export-too-large with the sentence "Narrow the date range."; the server's words are shown. */
export function exportErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.problem.detail) {
    return error.problem.detail;
  }
  return loadErrorMessage(error);
}
