import { FormControl } from '@angular/forms';
import { ApiError, ProblemDetail } from '../../core/http/problem-detail';
import { LOAD_FAILED, SAVE_FAILED } from './pharmacy.model';
import {
  applyFieldErrors,
  controlMessage,
  dayMonth,
  delegateEndedText,
  delegateErrors,
  loadErrorMessage,
  otherStoresText,
  plural,
  problemSlug,
  receiveText,
  roleChip,
  saveErrorMessage,
  staffSubline,
  storeLine,
  storeTypeChip,
  storesSubtitle,
  yesNo,
} from './pharmacy-display.util';
import { OPD_STORE, PHARMACIST_MAPPING, STORE_STAFF_MAPPING, WARD_STORE, CENTRAL, mapping, store } from './pharmacy.fixtures';

function problem(status: number, over: Partial<ProblemDetail> = {}): ApiError {
  return new ApiError({ type: 'https://sahaj.in/problems/x', title: 'T', status, ...over });
}

describe('pharmacy display helpers', () => {
  it('writes a day and month like 30 Nov', () => {
    expect(dayMonth('2026-11-30')).toBe('30 Nov');
    expect(dayMonth('2026-03-05')).toBe('5 Mar');
  });

  it('pluralises counts', () => {
    expect(plural(1, 'store', 'stores')).toBe('1 store');
    expect(plural(7, 'store', 'stores')).toBe('7 stores');
  });

  it('draws the section subtitle of mockup 16', () => {
    expect(storesSubtitle({ storeCount: 7, mappedStaffCount: 24, activeVendorCount: 12 })).toBe(
      '7 stores · 24 staff mapped',
    );
    expect(storesSubtitle(null)).toBe('');
  });

  it('gives each store type its chip words', () => {
    expect(storeTypeChip('CENTRAL').text).toBe('Central');
    expect(storeTypeChip('PHARMACY').text).toBe('Sale');
    expect(storeTypeChip('SUB_STORE').text).toBe('Sub-store');
    expect(storeTypeChip('WARD').text).toBe('Ward · consumption');
  });

  it('writes the tree line of a store', () => {
    expect(storeLine(CENTRAL)).toBe('Buys from vendors · issues to all stores');
    expect(storeLine(OPD_STORE)).toBe('Issues from Test Central Store · 5 staff');
    expect(storeLine(store({ ...WARD_STORE, staffCount: 0 }))).toBe('Issues from Test Central Store');
  });

  it('says "Delegate ended 30 Nov" only after the delegation is over', () => {
    expect(delegateEndedText(OPD_STORE)).toBeNull();
    const ended = store({
      ...OPD_STORE,
      delegate: { ...OPD_STORE.delegate!, state: 'ENDED' },
    });
    expect(delegateEndedText(ended)).toBe('Delegate ended 30 Nov');
  });

  it('draws the store role chip of the staff table', () => {
    expect(roleChip(mapping()).text).toBe('Approver');
    expect(roleChip(STORE_STAFF_MAPPING).text).toBe('Store user');
    const delegate = mapping({
      storeRole: 'DELEGATE',
      effectiveStoreRole: 'DELEGATE',
      delegateFrom: '2026-11-17',
      delegateTo: '2026-11-30',
      delegateState: 'CURRENT',
    });
    expect(roleChip(delegate).text).toBe('Delegate · till 30 Nov');
    const ended = { ...delegate, delegateState: 'ENDED' as const, effectiveStoreRole: 'STORE_USER' as const };
    expect(roleChip(ended).text).toBe('Delegate · ended 30 Nov');
    expect(roleChip(mapping({ storeRole: 'CENTRAL_STORE_USER' })).text).toBe('Central Store user');
  });

  it('writes the line under the name: the HOD line or the team role', () => {
    expect(staffSubline(mapping(), 'Test OPD Store')).toBe('HOD, Test OPD Store');
    expect(staffSubline(STORE_STAFF_MAPPING, 'Test OPD Store')).toBe('Store staff');
  });

  it('shows Yes and No, and Schedule X only for the pharmacist', () => {
    expect(yesNo(true)).toBe('Yes');
    expect(yesNo(false)).toBe('No');
    expect(receiveText(PHARMACIST_MAPPING)).toBe('Yes · incl. Schedule X');
    expect(receiveText(STORE_STAFF_MAPPING)).toBe('Yes');
    expect(receiveText(mapping({ access: { ...mapping().access, receive: false } }))).toBe('No');
  });

  it('lists the other stores or a dash', () => {
    expect(otherStoresText(PHARMACIST_MAPPING)).toBe('Test Main Pharmacy');
    expect(otherStoresText(mapping())).toBe('—');
  });
});

describe('pharmacy error messages', () => {
  it('uses the standard line for no connection and for server faults', () => {
    expect(saveErrorMessage(problem(0))).toBe(SAVE_FAILED);
    expect(saveErrorMessage(problem(500, { detail: 'internal' }))).toBe(SAVE_FAILED);
    expect(saveErrorMessage(new Error('boom'))).toBe(SAVE_FAILED);
  });

  it('shows the server sentence for a refusal and for a busy database', () => {
    expect(saveErrorMessage(problem(409, { detail: 'That store name is already used' }))).toBe(
      'That store name is already used',
    );
    expect(saveErrorMessage(problem(503, { detail: 'Nothing was saved by this attempt.' }))).toBe(
      'Nothing was saved by this attempt.',
    );
    expect(
      saveErrorMessage(problem(400, { fieldErrors: [{ field: 'name', message: 'Enter the store name' }] })),
    ).toBe('Enter the store name');
  });

  it('says "Could not load. Try again." unless the server gave a sentence for a refusal', () => {
    expect(loadErrorMessage(problem(0))).toBe(LOAD_FAILED);
    expect(loadErrorMessage(problem(500, { detail: 'x' }))).toBe(LOAD_FAILED);
    expect(loadErrorMessage(problem(403, { detail: 'Pharmacy & Inventory is not switched on for your clinic.' }))).toBe(
      'Pharmacy & Inventory is not switched on for your clinic.',
    );
  });

  it('reads the problem slug', () => {
    expect(problemSlug(problem(409, { type: 'https://sahaj.in/problems/settings-changed' }))).toBe(
      'settings-changed',
    );
    expect(problemSlug(new Error('x'))).toBeNull();
  });
});

describe('form field errors', () => {
  it('puts server messages on controls and returns the rest', () => {
    const name = new FormControl('');
    const to = new FormControl('');
    const rest = applyFieldErrors(
      { name, delegateTo: to },
      [
        { field: 'name', message: 'Enter the store name' },
        { field: 'delegate.to', message: 'Enter the end date' },
        { field: 'unknown', message: 'Something else' },
      ],
      { 'delegate.to': 'delegateTo' },
    );
    expect(name.errors).toEqual({ server: 'Enter the store name' });
    expect(to.errors).toEqual({ server: 'Enter the end date' });
    expect(rest).toEqual(['Something else']);
  });

  it('shows a control message only after it was touched', () => {
    const control = new FormControl('');
    control.setErrors({ required: true });
    expect(controlMessage(control, { required: 'Enter it' })).toBeNull();
    control.markAsTouched();
    expect(controlMessage(control, { required: 'Enter it' })).toBe('Enter it');
    control.setErrors({ server: 'From the server' });
    expect(controlMessage(control, { required: 'Enter it' })).toBe('From the server');
  });
});

describe('delegateErrors', () => {
  const today = '2026-11-17';
  const ok = { hodId: 'h', delegateId: 'd', from: '2026-11-17', to: '2026-11-30' };

  it('has no error without a delegate, or with a valid one', () => {
    expect(delegateErrors({ ...ok, delegateId: '', to: '' }, today)).toEqual({});
    expect(delegateErrors(ok, today)).toEqual({});
  });

  it('refuses the same person as approver and delegate', () => {
    expect(delegateErrors({ ...ok, delegateId: 'h' }, today).delegateMembershipId).toBe(
      'The delegate must be a different person from the approver',
    );
  });

  it('needs the dates, in order, not in the past, at most 90 days', () => {
    expect(delegateErrors({ ...ok, to: '' }, today).delegateTo).toBe('Enter the end date');
    expect(delegateErrors({ ...ok, from: '' }, today).delegateFrom).toBe('Enter the start date');
    expect(delegateErrors({ ...ok, to: '2026-11-10' }, today).delegateTo).toBe(
      'The end date cannot be in the past',
    );
    expect(delegateErrors({ ...ok, from: '2026-11-25', to: '2026-11-20' }, today).delegateTo).toBe(
      'The end date must be on or after the start date',
    );
    expect(delegateErrors({ ...ok, to: '2027-03-01' }, today).delegateTo).toBe(
      'A delegation can be at most 90 days',
    );
    // 90 calendar days counting both ends: 17 Nov 2026 to 14 Feb 2027 is accepted, 15 Feb 2027 is day 91.
    expect(delegateErrors({ ...ok, to: '2027-02-14' }, today)).toEqual({});
    expect(delegateErrors({ ...ok, to: '2027-02-15' }, today).delegateTo).toBe(
      'A delegation can be at most 90 days',
    );
  });
});
