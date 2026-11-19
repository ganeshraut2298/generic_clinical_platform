import { FormControl } from '@angular/forms';
import {
  ACTIVE_VENDOR,
  BLOCKED_VENDOR,
  CONSUMABLES_VENDOR,
  vendor,
} from '../pharmacy-c.fixtures';
import {
  canSwitchOff,
  creditText,
  gstinFormat,
  gstinText,
  licenceExpired,
  licenceText,
  percentFormat,
  phoneFormat,
  suppliesToText,
  vendorStatusChip,
  vendorsSubtitle,
  wholeNumber,
} from './vendor.util';

describe('vendor util', () => {
  it('writes the tab subtitle from the server count', () => {
    expect(vendorsSubtitle({ storeCount: 7, mappedStaffCount: 24, activeVendorCount: 12 })).toBe('12 active suppliers');
    expect(vendorsSubtitle({ storeCount: 1, mappedStaffCount: 0, activeVendorCount: 1 })).toBe('1 active supplier');
    expect(vendorsSubtitle(null)).toBe('');
  });

  it('writes the Drug licence column the way the story words it', () => {
    expect(licenceText(ACTIVE_VENDOR)).toBe('TEST-DL-0002 · till 31 Mar 2028');
    expect(licenceText(BLOCKED_VENDOR)).toBe('TEST-DL-0003 · expired 31 Oct 2026');
    expect(licenceText(CONSUMABLES_VENDOR)).toBe('Not required (consumables)');
    expect(licenceExpired(BLOCKED_VENDOR)).toBe(true);
    expect(licenceExpired(ACTIVE_VENDOR)).toBe(false);
    expect(licenceExpired(CONSUMABLES_VENDOR)).toBe(false);
  });

  it('writes credit, GSTIN and Supplies to', () => {
    expect(creditText(ACTIVE_VENDOR)).toBe('30 days');
    expect(creditText(vendor({ creditDays: 1 }))).toBe('1 day');
    expect(gstinText(CONSUMABLES_VENDOR)).toBe('Not set');
    expect(gstinText(ACTIVE_VENDOR)).toBe('27AAAAA0000A1Z5');
    expect(suppliesToText(ACTIVE_VENDOR)).toBe('Test Central Store');
    expect(suppliesToText(vendor({ suppliesTo: [] }))).toBe('—');
  });

  it('gives each status its own words, not only a colour', () => {
    expect(vendorStatusChip(ACTIVE_VENDOR).text).toBe('Active');
    expect(vendorStatusChip(BLOCKED_VENDOR).text).toBe('Blocked · licence expired');
    expect(vendorStatusChip(vendor({ status: 'INACTIVE' })).text).toBe('Inactive');
  });

  it('offers Switch off unless the vendor is already switched off', () => {
    expect(canSwitchOff(ACTIVE_VENDOR)).toBe(true);
    expect(canSwitchOff(BLOCKED_VENDOR)).toBe(true);
    expect(canSwitchOff(vendor({ status: 'INACTIVE' }))).toBe(false);
  });

  it('checks the GSTIN format only, and allows an empty box', () => {
    expect(gstinFormat(new FormControl(''))).toBeNull();
    expect(gstinFormat(new FormControl('27aaaaa0000a1z5'))).toBeNull();
    expect(gstinFormat(new FormControl('27AAAAA0000A1'))).toEqual({ gstin: true });
  });

  it('checks the phone, the whole numbers and the percent with the contract limits', () => {
    expect(phoneFormat(new FormControl('9000000001'))).toBeNull();
    expect(phoneFormat(new FormControl('900000001'))).toEqual({ phone: true });
    const days = wholeNumber(0, 365, false);
    expect(days(new FormControl('0'))).toBeNull();
    expect(days(new FormControl('365'))).toBeNull();
    expect(days(new FormControl('366'))).toEqual({ range: true });
    expect(days(new FormControl('1.5'))).toEqual({ range: true });
    expect(days(new FormControl(''))).toEqual({ required: true });
    expect(wholeNumber(0, 365, true)(new FormControl(''))).toBeNull();
    expect(percentFormat(new FormControl('100'))).toBeNull();
    expect(percentFormat(new FormControl('12.5'))).toBeNull();
    expect(percentFormat(new FormControl('100.5'))).toEqual({ percent: true });
    expect(percentFormat(new FormControl('12.555'))).toEqual({ percent: true });
  });
});
