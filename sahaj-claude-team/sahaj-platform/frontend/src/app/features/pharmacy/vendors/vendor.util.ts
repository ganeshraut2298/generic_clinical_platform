import { AbstractControl, ValidationErrors } from '@angular/forms';
import { formatDisplayDate } from '../../../shared/format/ist-date';
import { Chip, plural } from '../pharmacy-display.util';
import { SetupSummary, Vendor } from '../pharmacy.model';

/** Mockup 16 tab "Vendors": the line under the tab, "12 active suppliers". */
export function vendorsSubtitle(summary: SetupSummary | null): string {
  if (!summary) {
    return '';
  }
  return plural(summary.activeVendorCount, 'active supplier', 'active suppliers');
}

/** Drug licence column: "<number> · till 31 Mar 2028", "<number> · expired 31 Oct 2026", or "Not required (consumables)". */
export function licenceText(vendor: Vendor): string {
  if (vendor.licenceState === 'NOT_REQUIRED' || !vendor.licenceRequired) {
    return 'Not required (consumables)';
  }
  const number = vendor.licenceNo ?? 'Not set';
  if (!vendor.licenceValidTill) {
    return number;
  }
  const date = formatDisplayDate(vendor.licenceValidTill);
  return vendor.licenceState === 'EXPIRED'
    ? `${number} · expired ${date}`
    : `${number} · till ${date}`;
}

/** An expired licence is drawn in the warning colour (story 78-8). */
export function licenceExpired(vendor: Vendor): boolean {
  return vendor.licenceRequired === true && vendor.licenceState === 'EXPIRED';
}

export function creditText(vendor: Vendor): string {
  return vendor.creditDays === null ? '—' : plural(vendor.creditDays, 'day', 'days');
}

export function gstinText(vendor: Vendor): string {
  return vendor.gstin ?? 'Not set';
}

export function suppliesToText(vendor: Vendor): string {
  return vendor.suppliesTo !== null && vendor.suppliesTo.length > 0 ? vendor.suppliesTo.map((s) => s.name).join(', ') : '—';
}

/** Active, "Blocked · licence expired" (the server computes it from the date) or Inactive. */
export function vendorStatusChip(vendor: Vendor): Chip {
  switch (vendor.status) {
    case 'ACTIVE':
      return { text: 'Active', css: 'p-good' };
    case 'BLOCKED':
      return { text: 'Blocked · licence expired', css: 'p-crit' };
    case 'INACTIVE':
      return { text: 'Inactive', css: 'p-wait' };
  }
}

/** A vendor can be switched off unless it already is (story 78-8). */
export function canSwitchOff(vendor: Vendor): boolean {
  return vendor.status !== 'INACTIVE';
}

/** A switched-off vendor can be switched on again (contract 0.2.1, owner only). */
export function canSwitchOn(vendor: Vendor): boolean {
  return vendor.status === 'INACTIVE';
}

// ---- form validators, with the limits of VendorInput (docs/api/pharmacy.yaml)

/** Format only, as the contract's `Gstin` pattern; lower case is upper-cased before the check. */
export const VENDOR_GSTIN_PATTERN = /^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/;
export const PHONE_PATTERN = /^[0-9]{10}$/;
export const PERCENT_PATTERN = /^(100(\.0{1,2})?|[0-9]{1,2}(\.[0-9]{1,2})?)$/;

function text(control: AbstractControl): string {
  return String(control.value ?? '').trim();
}

export function nameValidator(control: AbstractControl): ValidationErrors | null {
  const value = text(control);
  if (value === '') {
    return { required: true };
  }
  return value.length > 120 ? { maxlength: true } : null;
}

export function gstinFormat(control: AbstractControl): ValidationErrors | null {
  const value = text(control).toUpperCase();
  return value === '' || VENDOR_GSTIN_PATTERN.test(value) ? null : { gstin: true };
}

export function phoneFormat(control: AbstractControl): ValidationErrors | null {
  const value = text(control);
  return value === '' || PHONE_PATTERN.test(value) ? null : { phone: true };
}

/** A whole number from `min` to `max`; `optional` lets the box stay empty. */
export function wholeNumber(min: number, max: number, optional: boolean) {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = text(control);
    if (value === '') {
      return optional ? null : { required: true };
    }
    if (!/^[0-9]{1,4}$/.test(value)) {
      return { range: true };
    }
    const n = Number(value);
    return n < min || n > max ? { range: true } : null;
  };
}

export function percentFormat(control: AbstractControl): ValidationErrors | null {
  const value = text(control);
  return value === '' || PERCENT_PATTERN.test(value) ? null : { percent: true };
}
