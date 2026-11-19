import { openingImport } from '../pharmacy.fixtures';
import {
  approveLockText,
  decisionText,
  fieldLabel,
  importStatusChip,
  rateText,
  rupees,
} from './opening-stock.util';

describe('opening stock display helpers', () => {
  it('writes paise with Indian grouping and two decimals', () => {
    expect(rupees(125000000)).toBe('₹ 12,50,000.00');
    expect(rupees(12500000)).toBe('₹ 1,25,000.00');
    expect(rupees(12500050)).toBe('₹ 1,25,000.50');
    expect(rupees(5)).toBe('₹ 0.05');
    expect(rupees(0)).toBe('₹ 0.00');
  });

  it('writes a rate per issue unit with two decimals, and keeps real extra decimals', () => {
    expect(rateText('45.5000')).toBe('₹ 45.50');
    expect(rateText('45.50')).toBe('₹ 45.50');
    expect(rateText('30')).toBe('₹ 30.00');
    expect(rateText('1250.5')).toBe('₹ 1,250.50');
    expect(rateText('0.125')).toBe('₹ 0.125');
    expect(rateText(null)).toBe('—');
  });

  it('labels every status in words', () => {
    expect(importStatusChip('WAITING_APPROVAL').text).toBe('Waiting for approval');
    expect(importStatusChip('NOT_ACCEPTED').text).toBe('Not accepted');
    expect(importStatusChip('APPROVED').text).toBe('Approved');
    expect(importStatusChip('REJECTED').text).toBe('Rejected');
    expect(importStatusChip('REPLACED').text).toBe('Replaced');
    expect(importStatusChip('DRAFT').text).toBe('Draft');
  });

  it('names the file column of a problem', () => {
    expect(fieldLabel('expiryDate')).toBe('Expiry date');
    expect(fieldLabel('mrp')).toBe('MRP');
  });

  it('says why Approve is not offered, only while the file is waiting', () => {
    expect(approveLockText(openingImport())).toBeNull();
    expect(approveLockText(openingImport({ canApprove: false, approveLockReason: 'SELF_UPLOADED' }))).toBe(
      'Another owner must approve this import.',
    );
    expect(approveLockText(openingImport({ canApprove: false, approveLockReason: 'NOT_OWNER' }))).toBe(
      'Only an owner can approve this import.',
    );
    expect(approveLockText(openingImport({ status: 'APPROVED', canApprove: false }))).toBeNull();
  });

  it('writes the reason of a rejected file', () => {
    expect(decisionText(openingImport({ status: 'REJECTED', rejectReasonKey: 'WRONG_QUANTITIES' }))).toBe(
      'Wrong quantities',
    );
    expect(
      decisionText(openingImport({ status: 'REJECTED', rejectReasonKey: 'OTHER', rejectNote: 'Counts are old' })),
    ).toBe('Other · Counts are old');
    expect(decisionText(openingImport())).toBeNull();
  });
});
