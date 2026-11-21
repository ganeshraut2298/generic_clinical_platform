import { rupees } from '../opening-stock/opening-stock.util';
import { PAYMENT_MODE_LABELS, PaymentInput, PaymentMode, QuoteBlocker, QuoteLine } from '../pharmacy-sale.model';

/** `₹ 532.00` typed as `532` or `532.5` becomes paise; anything else is null. */
export function parseRupees(text: string): number | null {
  const t = text.trim();
  if (!/^\d{1,8}(\.\d{1,2})?$/.test(t)) {
    return null;
  }
  const [whole, fraction = ''] = t.split('.');
  return Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
}

/** Payments must add up to the net payable (contract: no part-paid bill). */
export function paymentProblem(netPaise: number, payments: PaymentInput[]): string | null {
  if (payments.length === 0 || payments.reduce((sum, p) => sum + p.amountPaise, 0) !== netPaise) {
    return `Payments must add up to ${rupees(netPaise)}.`;
  }
  return null;
}

export function paymentLabel(mode: PaymentMode): string {
  return PAYMENT_MODE_LABELS[mode];
}

/** "Only <n> in stock." for a short line; the count is a dash when it is hidden from a blind caller. */
export function shortText(line: QuoteLine): string | null {
  if (!line.shortBy) {
    return null;
  }
  return line.availableQty === null || line.availableQty === undefined ? 'Not enough stock.' : `Only ${line.availableQty} in stock.`;
}

export function expiresText(line: QuoteLine): string | null {
  return line.expiresInDays === null ? null : `Expires in ${line.expiresInDays} d`;
}

export function expiredBlockedText(line: QuoteLine): string | null {
  return line.expiredBatchBlocked ? `${line.expiredBatchBlocked} expired · blocked` : null;
}

const BLOCKER_TEXT: Record<QuoteBlocker, string> = {
  NO_LINES: 'Add at least one item.',
  SHORT: 'Some items are short. Reduce the quantity or remove them.',
  ALLERGY: 'An allergy is recorded for a medicine in this bill.',
  H1_PRESCRIBER: 'Prescriber name and registration number are needed for Schedule H1.',
  PRESCRIPTION: 'A prescription is needed for a Schedule H1 or X line.',
  PHARMACIST: 'A pharmacist must complete this bill.',
  DISCOUNT_APPROVAL: 'This discount needs approval.',
  PAYMENTS: 'Payments must add up to the net payable.',
};

export function blockerText(blocker: QuoteBlocker): string {
  return BLOCKER_TEXT[blocker];
}
