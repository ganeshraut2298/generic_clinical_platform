import { FormControl } from '@angular/forms';
import {
  creditPaise,
  dnStatusChip,
  expiryMonthOk,
  gstOnPaise,
  grnStatusChip,
  lineAmountPaise,
  lineCheckChips,
  monthInputValue,
  monthYearText,
  paiseFromRupees,
  percentRange,
  policyLine,
  poStatusChip,
  positiveRate,
  overdueText,
  sourceChipText,
  startingCreditPercent,
  trimPercent,
  trimRate,
  wholeQty,
} from './purchase.util';

describe('purchase.util money', () => {
  it('works a line amount as quantity times rate, rounded half up to the paisa', () => {
    expect(lineAmountPaise(10, '70.00')).toBe(70000);
    expect(lineAmountPaise(3, '0.3333')).toBe(100);
    expect(lineAmountPaise(1, '0.0050')).toBe(1);
    expect(lineAmountPaise(1, '0.0049')).toBe(0);
    expect(lineAmountPaise(2, 'abc')).toBeNull();
    expect(lineAmountPaise(-1, '1')).toBeNull();
  });

  it('works GST per line on the amount, rounded half up', () => {
    expect(gstOnPaise(70000, '12')).toBe(8400);
    expect(gstOnPaise(1050, '5')).toBe(53);
    expect(gstOnPaise(100, 'x')).toBeNull();
  });

  it('works the credit as quantity times rate times credit percent, rounded half up', () => {
    expect(creditPaise(10, '70.00', '100')).toBe(70000);
    expect(creditPaise(10, '70.00', '50')).toBe(35000);
    expect(creditPaise(3, '33.3333', '12.5')).toBe(1250);
    expect(creditPaise(1, '1', 'x')).toBeNull();
  });

  it('reads typed rupees into paise', () => {
    expect(paiseFromRupees('1,25,000.50')).toBe(12500050);
    expect(paiseFromRupees('₹ 70')).toBe(7000);
    expect(paiseFromRupees('70.555')).toBeNull();
    expect(paiseFromRupees('')).toBeNull();
  });
});

describe('purchase.util statuses and chips', () => {
  it('names a purchase order status as the contract does', () => {
    expect(poStatusChip('SENT').text).toBe('Sent to vendor');
    expect(poStatusChip('WAITING_APPROVAL').text).toBe('Waiting for approval');
    expect(poStatusChip('CLOSED_SHORT', 2).text).toBe('Received · 2 short');
    expect(poStatusChip('CANCELLED').css).toBe('p-crit');
    expect(overdueText(3)).toBe('Overdue 3 days');
    expect(overdueText(1)).toBe('Overdue 1 day');
    expect(overdueText(null)).toBeNull();
  });

  it('names receipt and debit note statuses', () => {
    expect(grnStatusChip('POSTED').text).toBe('Posted');
    expect(dnStatusChip('PICKED').text).toBe('Picked and packed');
    expect(dnStatusChip('CREDIT_NOTE_RECEIVED').text).toBe('Credit note received');
  });

  it('words the receipt checks as the story does', () => {
    const chips = lineCheckChips({
      checks: ['RATE_UP', 'SHORT', 'MRP_CHANGED', 'PTR_ABOVE_MRP'],
      ratePercent: '+6.1%',
      rateBasis: 'PO',
      shortQty: 4,
    }).map((c) => c.text);
    expect(chips).toEqual(['Rate ↑ 6.1% vs PO', 'Short 4 vs PO', 'MRP changed', 'PTR above MRP']);
    expect(
      lineCheckChips({ checks: ['RATE_UP'], ratePercent: '+2.0%', rateBasis: 'LAST', shortQty: null })[0].text,
    ).toBe('Rate ↑ 2.0% vs last');
    expect(lineCheckChips({ checks: ['OK'], ratePercent: null, rateBasis: null, shortQty: null })[0].text).toBe('OK');
  });
});

describe('purchase.util expiry', () => {
  it('shows month and year and checks the month is not in the past', () => {
    expect(monthYearText('2028-03')).toBe('Mar 2028');
    expect(monthYearText('2028-03-31')).toBe('Mar 2028');
    expect(monthInputValue('2028-03-31')).toBe('2028-03');
    expect(expiryMonthOk('2026-11', '2026-11-19')).toBe(true);
    expect(expiryMonthOk('2026-10', '2026-11-19')).toBe(false);
    expect(expiryMonthOk('2026-13', '2026-11-19')).toBe(false);
    expect(expiryMonthOk('', '2026-11-19')).toBe(false);
  });
});

describe('purchase.util vendor return', () => {
  const levels = { standard: '100', damaged: '100', expired: '50' };

  it('starts the credit from the damaged level, the expired level, the lower of the two, or the standard credit', () => {
    expect(startingCreditPercent({ source: 'QUARANTINE_DAMAGED', daysToExpiry: 300, defaultCreditPercent: '100' }, levels)).toBe(
      '100',
    );
    expect(startingCreditPercent({ source: 'QUARANTINE_EXPIRED', daysToExpiry: -5, defaultCreditPercent: '100' }, levels)).toBe(
      '50',
    );
    expect(startingCreditPercent({ source: 'QUARANTINE_DAMAGED', daysToExpiry: -5, defaultCreditPercent: '100' }, levels)).toBe(
      '50',
    );
    expect(startingCreditPercent({ source: 'NEAR_EXPIRY', daysToExpiry: 40, defaultCreditPercent: '100' }, levels)).toBe('100');
  });

  it('lets an empty level count as the standard credit before the two are compared', () => {
    const open = { standard: '80', damaged: null, expired: '50' };
    expect(startingCreditPercent({ source: 'QUARANTINE_DAMAGED', daysToExpiry: -1, defaultCreditPercent: '80' }, open)).toBe('50');
    expect(startingCreditPercent({ source: 'QUARANTINE_DAMAGED', daysToExpiry: 100, defaultCreditPercent: '80' }, open)).toBe('80');
    expect(startingCreditPercent({ source: 'QUARANTINE_OTHER', daysToExpiry: 100, defaultCreditPercent: '70' }, { standard: null, damaged: null, expired: null })).toBe('70');
  });

  it('words the source chips and the policy line', () => {
    expect(sourceChipText({ source: 'NEAR_EXPIRY', daysToExpiry: 40, receiptNo: null })).toBe('Near expiry · 40 days');
    expect(sourceChipText({ source: 'GRN_SHORT', daysToExpiry: null, receiptNo: 'GRN-1' })).toBe('GRN short · GRN-1');
    expect(sourceChipText({ source: 'QUARANTINE_DAMAGED', daysToExpiry: 1, receiptNo: null })).toBe('Quarantine · damaged');
    expect(policyLine({ returnDaysBeforeExpiry: 90, creditPercent: '100.00' }, levels)).toBe(
      'return policy: up to 90 days before expiry, 100% credit; damaged on receipt, 100%; after expiry, 50%',
    );
    expect(policyLine({ returnDaysBeforeExpiry: null, creditPercent: null }, { standard: null, damaged: null, expired: null })).toBe(
      'no return policy set',
    );
    expect(trimPercent('12.50')).toBe('12.5');
    expect(trimRate('70.0000')).toBe('70');
    expect(trimRate('70.5000')).toBe('70.5');
    expect(trimRate('120.0000')).toBe('120');
    expect(trimRate('10')).toBe('10');
    expect(trimRate('0.00')).toBe('0');
  });
});

describe('purchase.util validators', () => {
  it('accepts whole quantities in range only', () => {
    const check = wholeQty(1, 999999);
    expect(check(new FormControl('10'))).toBeNull();
    expect(check(new FormControl('0'))).not.toBeNull();
    expect(check(new FormControl('1.5'))).not.toBeNull();
    expect(check(new FormControl(''))).not.toBeNull();
    expect(check(new FormControl('1000000'))).not.toBeNull();
  });

  it('accepts a rate above 0 with up to 4 decimals and a percent from 0 to 100', () => {
    expect(positiveRate(new FormControl('70.5'))).toBeNull();
    expect(positiveRate(new FormControl('0'))).not.toBeNull();
    expect(positiveRate(new FormControl('1.23456'))).not.toBeNull();
    expect(percentRange(new FormControl('100'))).toBeNull();
    expect(percentRange(new FormControl('100.5'))).not.toBeNull();
    expect(percentRange(new FormControl('12.345'))).not.toBeNull();
  });
});
