import { adjustmentValuePaise, validatePhysical } from './stock-adjustment.util';
import {
  actingAsText,
  adjustmentStatusChip,
  batchStatusChips,
  decideLockText,
  differenceText,
  expiryMonthYear,
  inboxChipText,
  ledgerTransactionText,
  marginText,
  qtyWithUnit,
  stateQty,
  storeStockStatusText,
  waitingText,
} from './inventory.util';
import { batchRow } from './inventory.fixtures';

describe('inventory util', () => {
  it('shows a dash for a quantity the server hid during a blind count (rule 24) and the number otherwise', () => {
    expect(stateQty(null)).toBe('—');
    expect(stateQty(0)).toBe('0');
    expect(stateQty(12)).toBe('12');
    expect(qtyWithUnit(null, 'vial')).toBe('—');
    expect(qtyWithUnit(4, 'vial')).toBe('4 vial');
  });

  it('shows batch expiry as month and year', () => {
    expect(expiryMonthYear('2028-03-31')).toBe('Mar 2028');
    expect(expiryMonthYear('2026-12-31')).toBe('Dec 2026');
  });

  it('shows how long an item waited as minutes, hours or days', () => {
    expect(waitingText(35)).toBe('35 min');
    expect(waitingText(120)).toBe('2 h');
    expect(waitingText(59)).toBe('59 min');
    expect(waitingText(60 * 50)).toBe('2 d');
  });

  it('writes the inbox type chip, with the urgent mark', () => {
    expect(inboxChipText('PURCHASE_ORDER', false)).toBe('Purchase order');
    expect(inboxChipText('INDENT', true)).toBe('Indent · urgent');
    expect(inboxChipText('AUDIT_VARIANCE', false)).toBe('Audit variance');
  });

  it('names the capacity of a decision', () => {
    expect(actingAsText('DELEGATE')).toBe('Delegate');
    expect(actingAsText('ESCALATED')).toBe('Escalated');
    expect(actingAsText(null)).toBe('');
  });

  it('says why a person cannot decide, in plain words', () => {
    expect(decideLockText('SELF_APPROVAL')).toBe('You cannot approve your own request.');
    expect(decideLockText('ALREADY_APPROVED_LEVEL_1')).toBe('Another person must approve the second level.');
    expect(decideLockText(null)).toBeNull();
  });

  it('draws the status chips of a batch from the server and its days to expiry', () => {
    expect(batchStatusChips(batchRow()).map((c) => c.text)).toEqual(['Saleable']);
    expect(batchStatusChips(batchRow({ statusChip: ['EXPIRES_IN', 'NEW_MRP'], daysToExpiry: 42 })).map((c) => c.text)).toEqual([
      'Expires in 42 d',
      'New MRP',
    ]);
    expect(batchStatusChips(batchRow({ statusChip: ['EXPIRED_QUARANTINED'], state: 'QUARANTINE' }))[0].text).toBe(
      'Expired · quarantined',
    );
  });

  it('shows a dash for a margin of a batch with no purchase rate', () => {
    expect(marginText(null)).toBe('—');
    expect(marginText('41.3')).toBe('41.3%');
  });

  it('words the store status of mockup 11', () => {
    expect(storeStockStatusText('OK')).toBe('OK');
    expect(storeStockStatusText('BELOW_REORDER')).toBe('Below reorder · raise indent');
    expect(storeStockStatusText('INDENT_IN_TRANSIT')).toBe('Indent in transit');
    expect(storeStockStatusText(null)).toBe('—');
  });

  it('writes a difference with a true minus sign', () => {
    expect(differenceText(4)).toBe('+4');
    expect(differenceText(-2)).toBe('−2');
    expect(differenceText(0)).toBe('0');
  });

  it('chips an adjustment by status', () => {
    expect(adjustmentStatusChip('WAITING_APPROVAL').text).toBe('Waiting for approval');
    expect(adjustmentStatusChip('POSTED').css).toBe('p-good');
    expect(adjustmentStatusChip('REJECTED').text).toBe('Rejected');
  });

  it('labels a ledger transaction, and shows a value of a later phase as words', () => {
    expect(ledgerTransactionText('OPENING_STOCK')).toBe('Opening stock');
    expect(ledgerTransactionText('EXPIRY_QUARANTINE')).toBe('Expired · quarantined');
    expect(ledgerTransactionText('SOMETHING_NEW')).toBe('Something new');
  });
});

describe('stock adjustment util', () => {
  it('needs a whole number of 0 or more and refuses no change', () => {
    expect(validatePhysical(null, 40)).toBe('Enter a whole number of 0 or more');
    expect(validatePhysical(-1, 40)).toBe('Enter a whole number of 0 or more');
    expect(validatePhysical(2.5, 40)).toBe('Enter a whole number of 0 or more');
    expect(validatePhysical(1_000_000, 40)).toBe('Enter a whole number of 0 or more');
    expect(validatePhysical(40, 40)).toBe('No change to post');
    expect(validatePhysical(0, 40)).toBeNull();
    expect(validatePhysical(41, 40)).toBeNull();
  });

  it('works the value as units times the purchase rate, rounded half up to the paisa', () => {
    expect(adjustmentValuePaise(-2, '70.5000')).toBe(14100);
    expect(adjustmentValuePaise(3, '10.125')).toBe(3038);
    expect(adjustmentValuePaise(1, '0.5')).toBe(50);
    expect(adjustmentValuePaise(5, null)).toBeNull();
  });
});
