import { AbstractControl, ValidationErrors } from '@angular/forms';
import { istToday } from '../../../shared/format/ist-date';
import {
  DebitNoteStatus,
  EligibleReturnItem,
  GoodsReceiptLine,
  GoodsReceiptStatus,
  PurchaseOrderStatus,
  ReturnSource,
} from '../pharmacy-buying.model';
import { Chip } from '../pharmacy-display.util';

// ---------------------------------------------------------------- money (BR-9, OQ-79-13)

/** A rate or percent string ("70.5", "70.5000") as a whole number of ten-thousandths; null when it is not a number. */
export function toTenThousandths(value: string): bigint | null {
  const text = value.trim();
  if (!/^\d{1,9}(\.\d{1,4})?$/.test(text)) {
    return null;
  }
  const [whole, fraction = ''] = text.split('.');
  return BigInt(whole) * 10000n + BigInt(fraction.padEnd(4, '0'));
}

function divideHalfUp(numerator: bigint, denominator: bigint): bigint {
  return (numerator * 2n + denominator) / (denominator * 2n);
}

/** The line amount in paise: quantity times rate, rounded half up to the paisa. Null for a rate that is not valid. */
export function lineAmountPaise(qty: number, rate: string): number | null {
  const rate4 = toTenThousandths(rate);
  if (rate4 === null || !Number.isInteger(qty) || qty < 0) {
    return null;
  }
  return Number(divideHalfUp(BigInt(qty) * rate4, 100n));
}

/** GST in paise on an amount in paise at a percent of at most 2 decimals, rounded half up. */
export function gstOnPaise(amountPaise: number, gstPercent: string): number | null {
  const percent4 = toTenThousandths(gstPercent);
  if (percent4 === null) {
    return null;
  }
  return Number(divideHalfUp(BigInt(amountPaise) * percent4, 1000000n));
}

/** Credit of a debit note line: quantity times rate times credit %, rounded half up to the paisa. */
export function creditPaise(qty: number, rate: string, creditPercent: string): number | null {
  const rate4 = toTenThousandths(rate);
  const percent4 = toTenThousandths(creditPercent);
  if (rate4 === null || percent4 === null || !Number.isInteger(qty) || qty < 0) {
    return null;
  }
  return Number(divideHalfUp(BigInt(qty) * rate4 * percent4, 100000000n));
}

/** Whole paise from a typed rupee amount ("1,25,000.50"); null when it is not an amount with at most 2 decimals. */
export function paiseFromRupees(value: string): number | null {
  const text = value.replace(/[₹,\s]/g, '');
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(text)) {
    return null;
  }
  const [whole, fraction = ''] = text.split('.');
  return Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
}

// ---------------------------------------------------------------- statuses

const PO_STATUS_LABELS: Record<PurchaseOrderStatus, string> = {
  DRAFT: 'Draft',
  WAITING_APPROVAL: 'Waiting for approval',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  SENT: 'Sent to vendor',
  PARTLY_RECEIVED: 'Partly received',
  RECEIVED: 'Received',
  CLOSED_SHORT: 'Received',
  CANCELLED: 'Cancelled',
  DISCARDED: 'Discarded',
};

/** The chip of a purchase order: "Received · 2 short" when closed short; overdue is a mark, not a status. */
export function poStatusChip(status: PurchaseOrderStatus, shortLineCount = 0): Chip {
  const base = PO_STATUS_LABELS[status];
  switch (status) {
    case 'DRAFT':
      return { text: base, css: 'p-wait' };
    case 'WAITING_APPROVAL':
      return { text: base, css: 'p-warn' };
    case 'APPROVED':
    case 'SENT':
    case 'PARTLY_RECEIVED':
      return { text: base, css: 'p-blue' };
    case 'RECEIVED':
      return { text: base, css: 'p-good' };
    case 'CLOSED_SHORT':
      return { text: `${base} · ${shortLineCount} short`, css: 'p-good' };
    case 'REJECTED':
    case 'CANCELLED':
    case 'DISCARDED':
      return { text: base, css: 'p-crit' };
  }
}

export function overdueText(days: number | null): string | null {
  return days === null ? null : `Overdue ${days} ${days === 1 ? 'day' : 'days'}`;
}

const GRN_STATUS_LABELS: Record<GoodsReceiptStatus, Chip> = {
  DRAFT: { text: 'Draft', css: 'p-wait' },
  WAITING_APPROVAL: { text: 'Waiting for approval', css: 'p-warn' },
  APPROVED: { text: 'Approved', css: 'p-blue' },
  POSTED: { text: 'Posted', css: 'p-good' },
  DISCARDED: { text: 'Discarded', css: 'p-crit' },
};

export function grnStatusChip(status: GoodsReceiptStatus): Chip {
  return GRN_STATUS_LABELS[status];
}

const DN_STATUS_LABELS: Record<DebitNoteStatus, Chip> = {
  DRAFT: { text: 'Draft', css: 'p-wait' },
  WAITING_APPROVAL: { text: 'Waiting for approval', css: 'p-warn' },
  REJECTED: { text: 'Rejected', css: 'p-crit' },
  CREATED: { text: 'Created', css: 'p-blue' },
  PICKED: { text: 'Picked and packed', css: 'p-blue' },
  HANDED_OVER: { text: 'Handed to vendor rep', css: 'p-blue' },
  CREDIT_NOTE_RECEIVED: { text: 'Credit note received', css: 'p-good' },
  CANCELLED: { text: 'Cancelled', css: 'p-crit' },
  DISCARDED: { text: 'Discarded', css: 'p-crit' },
};

export function dnStatusChip(status: DebitNoteStatus): Chip {
  return DN_STATUS_LABELS[status];
}

// ---------------------------------------------------------------- receiving checks (mockup 08 "Check" column)

/** The Check chips of a receipt line: "OK", "Rate ↑ 6.1% vs PO", "Short 4 vs PO", "MRP changed", "PTR above MRP". */
export function lineCheckChips(line: Pick<GoodsReceiptLine, 'checks' | 'ratePercent' | 'rateBasis' | 'shortQty'>): Chip[] {
  return line.checks.map((check) => {
    switch (check) {
      case 'OK':
        return { text: 'OK', css: 'p-good' };
      case 'RATE_UP': {
        const percent = (line.ratePercent ?? '').replace(/^\+/, '');
        const basis = line.rateBasis === 'LAST' ? 'vs last' : 'vs PO';
        return { text: `Rate ↑ ${percent} ${basis}`.replace('  ', ' '), css: 'p-warn' };
      }
      case 'SHORT':
        return { text: `Short ${line.shortQty ?? 0} vs PO`, css: 'p-warn' };
      case 'MRP_CHANGED':
        return { text: 'MRP changed', css: 'p-blue' };
      case 'PTR_ABOVE_MRP':
        return { text: 'PTR above MRP', css: 'p-warn' };
    }
  });
}

// ---------------------------------------------------------------- expiry as month and year

const MONTH_NAMES = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** `2028-03` or `2028-03-31` as "Mar 2028" (OQ-79-7). */
export function monthYearText(value: string): string {
  const [year, month] = value.split('-').map(Number);
  return month >= 1 && month <= 12 ? `${MONTH_NAMES[month - 1]} ${year}` : value;
}

/** `2028-03-31` as the month input value `2028-03`. */
export function monthInputValue(isoDate: string): string {
  return isoDate.slice(0, 7);
}

/** True for a `YYYY-MM` that is this month or later in IST. */
export function expiryMonthOk(value: string, today: string = istToday()): boolean {
  return /^\d{4}-(0[1-9]|1[0-2])$/.test(value) && value >= today.slice(0, 7);
}

// ---------------------------------------------------------------- vendor return (79-8)

const SOURCE_LABELS: Record<ReturnSource, string> = {
  QUARANTINE_INDENT_REJECTION: 'Quarantine · indent rejection',
  QUARANTINE_DAMAGED: 'Quarantine · damaged',
  QUARANTINE_EXPIRED: 'Quarantine · expired',
  QUARANTINE_OTHER: 'Quarantine',
  NEAR_EXPIRY: 'Near expiry',
  GRN_SHORT: 'GRN short',
};

/** The Source chip of an eligible row: "Near expiry · 40 days", "GRN short · GRN-26-27/0012". */
export function sourceChipText(item: Pick<EligibleReturnItem, 'source' | 'daysToExpiry' | 'receiptNo'>): string {
  const base = SOURCE_LABELS[item.source];
  if (item.source === 'NEAR_EXPIRY' && item.daysToExpiry !== null) {
    return `${base} · ${item.daysToExpiry} days`;
  }
  if (item.source === 'GRN_SHORT' && item.receiptNo) {
    return `${base} · ${item.receiptNo}`;
  }
  return base;
}

export interface CreditLevels {
  /** "Credit given on return, %" */
  standard: string | null;
  /** "Credit for damaged on receipt"; null falls back to the standard credit. */
  damaged: string | null | undefined;
  /** "Credit after expiry"; null falls back to the standard credit. */
  expired: string | null | undefined;
}

/**
 * Where Credit % starts (story 79-8, DEC-078 item 5): from the damaged level for a line damaged on receipt, from the
 * expired level for an expired batch, the lower of the two when both apply, otherwise the standard credit. An empty
 * level counts as the standard credit before the two are compared. The person may change it afterwards.
 */
export function startingCreditPercent(
  item: Pick<EligibleReturnItem, 'source' | 'daysToExpiry' | 'defaultCreditPercent'>,
  levels: CreditLevels,
): string {
  const standard = levels.standard ?? item.defaultCreditPercent ?? '0';
  const damaged = item.source === 'QUARANTINE_DAMAGED';
  const expired = item.source === 'QUARANTINE_EXPIRED' || (item.daysToExpiry !== null && item.daysToExpiry < 0);
  const damagedLevel = levels.damaged ?? standard;
  const expiredLevel = levels.expired ?? standard;
  if (damaged && expired) {
    return (toTenThousandths(damagedLevel) ?? 0n) <= (toTenThousandths(expiredLevel) ?? 0n)
      ? damagedLevel
      : expiredLevel;
  }
  if (damaged) {
    return damagedLevel;
  }
  if (expired) {
    return expiredLevel;
  }
  return standard;
}

/** "return policy: up to 90 days before expiry, 100% credit; damaged on receipt, 100%; after expiry, 50%". */
export function policyLine(
  policy: { returnDaysBeforeExpiry: number | null; creditPercent: string | null },
  levels: CreditLevels,
): string {
  const parts: string[] = [];
  if (policy.returnDaysBeforeExpiry !== null || policy.creditPercent !== null) {
    const days =
      policy.returnDaysBeforeExpiry !== null ? `up to ${policy.returnDaysBeforeExpiry} days before expiry` : 'any time';
    const credit = policy.creditPercent !== null ? `, ${trimPercent(policy.creditPercent)}% credit` : '';
    parts.push(`return policy: ${days}${credit}`);
  } else {
    parts.push('no return policy set');
  }
  if (levels.damaged !== null && levels.damaged !== undefined) {
    parts.push(`damaged on receipt, ${trimPercent(levels.damaged)}%`);
  }
  if (levels.expired !== null && levels.expired !== undefined) {
    parts.push(`after expiry, ${trimPercent(levels.expired)}%`);
  }
  return parts.join('; ');
}

/** "70.0000" as "70", "70.5000" as "70.5", "10" stays "10": a rate or percent as the box shows it. */
export function trimRate(value: string): string {
  return value.includes('.') ? value.replace(/0+$/, '').replace(/\.$/, '') : value;
}

/** "100.00" as "100", "12.50" as "12.5". */
export function trimPercent(value: string): string {
  return value.includes('.') ? value.replace(/0+$/, '').replace(/\.$/, '') : value;
}

// ---------------------------------------------------------------- validators (match the contract limits)

/** A whole number from min to max typed as text. Empty is invalid. */
export function wholeQty(min: number, max: number) {
  return (control: AbstractControl): ValidationErrors | null => {
    const text = String(control.value ?? '').trim();
    if (!/^\d+$/.test(text)) {
      return { qty: true };
    }
    const n = Number(text);
    return n >= min && n <= max ? null : { qty: true };
  };
}

/** A rate above 0 with up to 4 decimals. */
export function positiveRate(control: AbstractControl): ValidationErrors | null {
  const rate4 = toTenThousandths(String(control.value ?? ''));
  return rate4 !== null && rate4 > 0n ? null : { rate: true };
}

/** A percent from 0 to 100 with up to 2 decimals. */
export function percentRange(control: AbstractControl): ValidationErrors | null {
  const text = String(control.value ?? '').trim();
  if (!/^\d{1,3}(\.\d{1,2})?$/.test(text)) {
    return { percent: true };
  }
  return Number(text) <= 100 ? null : { percent: true };
}

/** A date from today on (IST), as `YYYY-MM-DD`. Empty is allowed to the caller's own rule. */
export function notBeforeToday(today: string = istToday()) {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = control.value as string | null;
    return !value || value >= today ? null : { past: true };
  };
}

export const PO_MESSAGES = {
  vendor: 'Choose a vendor',
  deliveryRequired: 'Enter the delivery date',
  deliveryPast: 'Delivery date must be today or later',
  creditDays: 'Enter 0 to 365 days',
  qty: 'Enter a whole number from 1 to 999999',
  rate: 'Enter a rate above 0, up to 4 decimals',
  lines: 'Add at least one item',
  duplicate: 'Already on this purchase order',
  tooMany: 'A purchase order has at most 200 lines',
} as const;

export const PO_LINE_LIMIT = 200;
