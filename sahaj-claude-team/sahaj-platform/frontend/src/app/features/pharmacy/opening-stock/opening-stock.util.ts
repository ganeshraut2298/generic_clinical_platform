import { formatIndian } from '../../../shared/format/inr';
import { Chip } from '../pharmacy-display.util';
import {
  IMPORT_STATUS_LABELS,
  ImportStatus,
  OpeningStockImport,
  REJECT_REASON_LABELS,
  RowIssueField,
} from '../pharmacy.model';

/** `₹ 1,25,000.00` from an amount held in paise: Indian grouping, always two decimals. */
export function rupees(paise: number): string {
  const total = Math.round(Math.abs(paise));
  const sign = paise < 0 && total !== 0 ? '-' : '';
  return `₹ ${sign}${formatIndian(Math.floor(total / 100))}.${String(total % 100).padStart(2, '0')}`;
}

/**
 * `₹ 45.50` from a rate string in rupees per issue unit ("45.5000" or "45.50"). Phase 1 input has at most 2
 * decimals; a value with more significant decimals keeps them, up to 4.
 */
export function rateText(rate: string | null): string {
  if (rate === null || rate === '') {
    return '—';
  }
  const [whole, fraction = ''] = rate.split('.');
  const trimmed = fraction.replace(/0+$/, '');
  const decimals = trimmed.length <= 2 ? trimmed.padEnd(2, '0') : trimmed;
  const digits = whole.replace(/^0+(?=\d)/, '');
  return `₹ ${formatIndian(Number(digits))}.${decimals}`;
}

export function importStatusChip(status: ImportStatus): Chip {
  const text = IMPORT_STATUS_LABELS[status];
  switch (status) {
    case 'APPROVED':
      return { text, css: 'p-good' };
    case 'WAITING_APPROVAL':
      return { text, css: 'p-wait' };
    case 'NOT_ACCEPTED':
    case 'REJECTED':
      return { text, css: 'p-crit' };
    case 'DRAFT':
    case 'REPLACED':
      return { text, css: 'p-teal' };
  }
}

const FIELD_LABELS: Record<RowIssueField, string> = {
  storeCode: 'Store code',
  productCode: 'Product code',
  batch: 'Batch',
  expiryDate: 'Expiry date',
  mrp: 'MRP',
  purchaseRate: 'Purchase rate',
  quantity: 'Quantity',
};

export function fieldLabel(field: RowIssueField): string {
  return FIELD_LABELS[field];
}

/** Why Approve is not offered to this person, in plain words (story 78-7); null when it is offered. */
export function approveLockText(item: OpeningStockImport): string | null {
  if (item.status !== 'WAITING_APPROVAL' || item.canApprove) {
    return null;
  }
  if (item.approveLockReason === 'SELF_UPLOADED') {
    return 'Another owner must approve this import.';
  }
  return 'Only an owner can approve this import.';
}

/** The decision line of a finished import: "Rejected · Wrong quantities". */
export function decisionText(item: OpeningStockImport): string | null {
  if (item.status === 'REJECTED' && item.rejectReasonKey) {
    const reason = REJECT_REASON_LABELS[item.rejectReasonKey];
    return item.rejectNote ? `${reason} · ${item.rejectNote}` : reason;
  }
  return null;
}

/** The chip of an import, with the Posted state (SAH-79-1): a posted import reads "Posted", not "Approved". */
export function importChip(item: Pick<OpeningStockImport, 'status' | 'posted'>): Chip {
  return item.posted ? { text: 'Posted', css: 'p-good' } : importStatusChip(item.status);
}
