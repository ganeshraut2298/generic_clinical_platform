import { Chip } from '../pharmacy-display.util';
import {
  ActingAs,
  AdjustmentStatus,
  DecideLockReason,
  HistoryEvent,
  InboxAction,
  InboxChip,
  LedgerTransaction,
  StockBatchRow,
  StockStatusChip,
  StoreStockStatus,
} from '../pharmacy-inventory.model';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** Batch expiry as the mockup shows it, month and year: `2028-03-31` becomes `Mar 2028` (OQ-79-7). */
export function expiryMonthYear(iso: string): string {
  const [year, month] = iso.split('-').map(Number);
  return `${MONTHS[month - 1]} ${year}`;
}

/** `35 min`, `2 h`, `3 d` from whole minutes (mockup 14 column "Waiting"). */
export function waitingText(minutes: number): string {
  if (minutes < 60) {
    return `${minutes} min`;
  }
  const hours = Math.floor(minutes / 60);
  return hours < 48 ? `${hours} h` : `${Math.floor(hours / 24)} d`;
}

/** The "Type" chip of the inbox (mockup 14), with the urgent mark for an urgent indent. */
const INBOX_CHIP_LABELS: Record<InboxChip, string> = {
  INDENT: 'Indent',
  PURCHASE_ORDER: 'Purchase order',
  RATE_INCREASE: 'Rate increase',
  VENDOR_RETURN: 'Vendor return',
  TRANSFER: 'Transfer',
  WRITE_OFF: 'Write-off',
  STOCK_ADJUSTMENT: 'Stock adjustment',
  AUDIT_VARIANCE: 'Audit variance',
  DISCOUNT: 'Discount',
  SALES_RETURN: 'Sales return',
};

export function inboxChipText(chip: InboxChip, urgent: boolean): string {
  const label = INBOX_CHIP_LABELS[chip];
  return urgent ? `${label} · urgent` : label;
}

export const INBOX_ACTION_LABELS: Record<InboxAction, string> = {
  REVIEW: 'Review',
  REJECT: 'Reject',
  RECOUNT: 'Recount',
  APPROVE: 'Approve',
};

const ACTING_AS_LABELS: Record<ActingAs, string> = {
  HOD: 'HOD',
  DELEGATE: 'Delegate',
  OWNER: 'Owner',
  ESCALATED: 'Escalated',
};

export function actingAsText(value: HistoryEvent['actingAs']): string {
  if (!value) {
    return '';
  }
  switch (value) {
    case 'CENTRAL_STORE':
      return 'Central Store';
    case 'STORE':
      return 'Store';
    case 'PHARMACIST':
      return 'Pharmacist';
    default:
      return ACTING_AS_LABELS[value];
  }
}

/** Why Approve is not offered, in plain words (story 79-2); null when the caller can decide. */
export function decideLockText(reason: DecideLockReason | null): string | null {
  switch (reason) {
    case 'SELF_APPROVAL':
      return 'You cannot approve your own request.';
    case 'ALREADY_APPROVED_LEVEL_1':
      return 'Another person must approve the second level.';
    case 'NOT_APPROVER':
      return 'This is not waiting for your decision.';
    case 'FINISHED':
      return 'This request is already decided.';
    default:
      return null;
  }
}

/** Status chips of a batch row (mockup 06): Saleable, Expires in n d, Expired · quarantined, New MRP and the states. */
export function batchStatusChips(row: StockBatchRow): Chip[] {
  return row.statusChip.map((chip) => statusChip(chip, row.daysToExpiry));
}

function statusChip(chip: StockStatusChip, daysToExpiry: number): Chip {
  switch (chip) {
    case 'SALEABLE':
      return { text: 'Saleable', css: 'p-good' };
    case 'EXPIRES_IN':
      return { text: `Expires in ${daysToExpiry} d`, css: 'p-warn' };
    case 'EXPIRED_QUARANTINED':
      return { text: 'Expired · quarantined', css: 'p-crit' };
    case 'NEW_MRP':
      return { text: 'New MRP', css: 'p-blue' };
    case 'IN_TRANSIT':
      return { text: 'In transit', css: 'p-wait' };
    case 'PENDING_RETURN':
      return { text: 'Pending return', css: 'p-wait' };
    case 'QUARANTINE':
      return { text: 'Quarantine', css: 'p-crit' };
  }
}

/** One decimal with a percent sign, or a dash where a batch has no purchase rate. */
export function marginText(margin: string | null): string {
  return margin === null ? '—' : `${margin}%`;
}

/** The "Status" column of "Store stock" (mockup 11). */
export function storeStockStatusText(status: StoreStockStatus | null): string {
  switch (status) {
    case 'OK':
      return 'OK';
    case 'BELOW_REORDER':
      return 'Below reorder · raise indent';
    case 'INDENT_IN_TRANSIT':
      return 'Indent in transit';
    default:
      return '—';
  }
}

/** A quantity in a state column; "—" where the server sent none (blind count, rule 24). Never computed with. */
export function stateQty(value: number | null): string {
  return value === null || value === undefined ? '—' : String(value);
}

/** A quantity with its unit; "—" alone where the server sent none (blind count, rule 24). */
export function qtyWithUnit(value: number | null, unit: string): string {
  return value === null || value === undefined ? '—' : `${value} ${unit}`;
}

/** `+4` or `−2` (true minus sign), as the adjustment form shows the difference. */
export function differenceText(difference: number): string {
  if (difference > 0) {
    return `+${difference}`;
  }
  return difference < 0 ? `−${Math.abs(difference)}` : '0';
}

export function adjustmentStatusChip(status: AdjustmentStatus): Chip {
  switch (status) {
    case 'POSTED':
      return { text: 'Posted', css: 'p-good' };
    case 'WAITING_APPROVAL':
      return { text: 'Waiting for approval', css: 'p-wait' };
    case 'REJECTED':
      return { text: 'Rejected', css: 'p-crit' };
  }
}

const EVENT_LABELS: Record<string, string> = {
  CREATED: 'Raised',
  SUBMITTED: 'Submitted',
  DECIDED: 'Decided',
  POSTED: 'Posted',
  CANCELLED: 'Cancelled',
};

export function historyEventText(event: string): string {
  return EVENT_LABELS[event] ?? event.charAt(0) + event.slice(1).toLowerCase().replace(/_/g, ' ');
}

const LEDGER_LABELS: Record<LedgerTransaction, string> = {
  OPENING_STOCK: 'Opening stock',
  GRN: 'GRN',
  EXPIRY_QUARANTINE: 'Expired · quarantined',
  STOCK_ADJUSTMENT: 'Stock adjustment',
  VENDOR_RETURN_HOLD: 'Vendor return · held',
  VENDOR_RETURN_RELEASE: 'Vendor return · released',
  VENDOR_RETURN: 'Vendor return',
  INDENT_DISPATCH: 'Indent dispatch',
  INDENT_RECEIPT_ACCEPTED: 'Indent receipt · accepted',
  INDENT_RECEIPT_REJECTED: 'Indent receipt · rejected',
  RETURN_ACCEPTED: 'Return accepted by CS',
  SHORTAGE_FOUND: 'Shortage found',
  SHORTAGE_WRITE_OFF: 'Shortage written off',
  STORE_RETURN: 'Return to CS',
  DAMAGE_QUARANTINE: 'Damaged · quarantined',
  DAMAGE_RELEASE: 'Damaged · released',
  DAMAGE_SEND_TO_CS: 'Damaged · sent to CS',
  WRITE_OFF: 'Write-off',
  TRANSFER_DISPATCH: 'Stock transfer · dispatch',
  TRANSFER_RECEIPT_ACCEPTED: 'Stock transfer · accepted',
  TRANSFER_RECEIPT_REJECTED: 'Stock transfer · rejected',
  WARD_CONSUMPTION: 'Ward consumption',
  WARD_CONSUMPTION_PATIENT: 'Ward consumption · patient',
  STOCK_AUDIT_ADJUSTMENT: 'Stock audit adjustment',
  COUNTER_SALE: 'Counter sale',
  COUNTER_SALE_CANCELLED: 'Counter sale · cancelled',
  SALES_RETURN: 'Sales return',
  IPD_ISSUE: 'IPD issue',
  WARD_RETURN_ACCEPTED: 'Ward return · accepted',
  WARD_RETURN_WASTAGE: 'Ward return · wastage',
};

/** The label of a ledger transaction; an unknown value of a later phase is shown as words, never hidden. */
export function ledgerTransactionText(transaction: string): string {
  return (
    LEDGER_LABELS[transaction as LedgerTransaction] ??
    transaction.charAt(0) + transaction.slice(1).toLowerCase().replace(/_/g, ' ')
  );
}
