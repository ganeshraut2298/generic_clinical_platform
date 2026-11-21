import {
  auditChip,
  blindQty,
  isNumericColumn,
  reportCellText,
  signedQty,
  signedRupees,
  transferChip,
  transferSteps,
} from './stockuse.util';

describe('stockuse.util', () => {
  it('shows a hidden quantity as a dash', () => {
    expect(blindQty(null)).toBe('—');
    expect(blindQty(0)).toBe('0');
    expect(signedRupees(null)).toBe('—');
  });

  it('writes a loss and a gain with their signs', () => {
    expect(signedRupees(-41200)).toBe('− ₹ 412.00');
    expect(signedRupees(9000)).toBe('+ ₹ 90.00');
    expect(signedRupees(0)).toBe('₹ 0.00');
    expect(signedQty(-6)).toBe('−6');
    expect(signedQty(0)).toBe('0');
  });

  it('marks a transfer in transit too long as not received', () => {
    expect(transferChip('IN_TRANSIT', 26).text).toBe('Not received · 26 h');
    expect(transferChip('IN_TRANSIT', null).text).toBe('In transit');
    expect(transferChip('WAITING_APPROVAL', null).text).toBe('Waiting for approval');
  });

  it('marks the steps done up to the current one', () => {
    const steps = transferSteps('DISPATCHED', 'Aundh', 'Baner');
    expect(steps.map((s) => s.done)).toEqual([true, true, true, false]);
    expect(steps[1].hint).toBe('Aundh in-charge');
  });

  it('names the audit status', () => {
    expect(auditChip('COUNTING').text).toBe('Counting in progress');
    expect(auditChip('CLOSED').css).toBe('p-good');
  });

  it('formats report cells by their kind and shows null as a dash', () => {
    expect(reportCellText({ key: 'v', label: 'Value', kind: 'PAISE' }, 12500050)).toBe('₹ 1,25,000.50');
    expect(reportCellText({ key: 'q', label: 'Qty', kind: 'INTEGER' }, null)).toBe('—');
    expect(reportCellText({ key: 'g', label: 'GST', kind: 'PERCENT' }, '12')).toBe('12%');
    expect(reportCellText({ key: 'd', label: 'Date', kind: 'DATE' }, '2026-11-17')).toBe('17 Nov 2026');
    expect(isNumericColumn({ key: 'q', label: 'Qty', kind: 'INTEGER' })).toBe(true);
    expect(isNumericColumn({ key: 'n', label: 'Name', kind: 'TEXT' })).toBe(false);
  });
});
