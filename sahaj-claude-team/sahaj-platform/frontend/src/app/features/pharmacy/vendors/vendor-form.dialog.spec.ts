import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { CENTRAL, OPD_STORE } from '../pharmacy.fixtures';
import { ACTIVE_VENDOR, BLOCKED_VENDOR } from '../pharmacy-c.fixtures';
import { VENDOR_FORM_CONFIG, VendorFormData, VendorFormDialog } from './vendor-form.dialog';

const BASE = '/api/v1/pharmacy';

interface Internals {
  form: VendorFormDialog['form'];
  save(): void;
}

describe('VendorFormDialog', () => {
  let fixture: ComponentFixture<VendorFormDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(data: VendorFormData): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(VendorFormDialog);
    fixture.detectChanges();
    http
      .expectOne(`${BASE}/stores`)
      .flush({ items: [CENTRAL, OPD_STORE], centralStoreId: CENTRAL.id });
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const me = () => fixture.componentInstance as unknown as Internals;
  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);

  afterEach(() => http.verify());

  it('names the sheet title that the dialog config points to', async () => {
    const el = await open({});
    expect(VENDOR_FORM_CONFIG.ariaLabelledBy).toBe('vendor-form-title');
    expect(el.querySelector('#vendor-form-title')?.textContent).toContain('Add a vendor');
  });

  it('asks the fields in the story order and defaults Supplies to the Central Store', async () => {
    const el = await open({});
    const labels = Array.from(el.querySelectorAll('mat-label')).map((l) => l.textContent?.trim());
    expect(labels).toEqual([
      'Name',
      'GSTIN',
      'Drug licence required',
      'Drug licence number',
      'Drug licence valid till',
      'Contact name',
      'Contact phone',
      'Credit days',
      'Returns accepted, days before expiry',
      'Credit given on return, %',
      'Credit for damaged on receipt',
      'Credit after expiry',
      'Supplies to',
    ]);
    expect(me().form.controls.suppliesTo.value).toEqual([CENTRAL.id]);
  });

  it('hides the licence fields when the licence is not required', async () => {
    const el = await open({});
    expect(q(el, 'f-vendor-licence-no')).not.toBeNull();
    me().form.controls.licenceRequired.setValue(false);
    await settle();
    expect(q(el, 'f-vendor-licence-no')).toBeNull();
    expect(q(el, 'f-vendor-valid-till')).toBeNull();
  });

  it('refuses an empty name and a bad GSTIN without calling the server', async () => {
    const el = await open({});
    me().form.controls.licenceRequired.setValue(false);
    me().form.controls.gstin.setValue('123');
    me().save();
    await settle();
    expect(el.textContent).toContain('Enter the vendor name');
    expect(el.textContent).toContain('Enter a 15-character GSTIN');
    http.expectNone(`${BASE}/vendors`);
  });

  it('requires the licence number and date when the licence is required', async () => {
    const el = await open({});
    me().form.patchValue({ name: 'Test Vendor' });
    me().save();
    await settle();
    expect(el.textContent).toContain('Enter the drug licence number');
    expect(el.textContent).toContain('Enter the licence valid-till date');
    http.expectNone(`${BASE}/vendors`);
  });

  it('sends the request after the licence is switched to No, even when Save failed while it was Yes (M-2)', async () => {
    const el = await open({});
    me().form.patchValue({ name: 'Test Vendor' });
    me().save();
    await settle();
    expect(el.textContent).toContain('Enter the drug licence number');
    expect(me().form.controls.licenceNo.invalid).toBe(true);

    me().form.controls.licenceRequired.setValue(false);
    await settle();
    expect(me().form.controls.licenceNo.valid).toBe(true);
    expect(me().form.controls.licenceValidTill.valid).toBe(true);

    me().save();
    const req = http.expectOne(`${BASE}/vendors`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.licenceRequired).toBe(false);
    expect(req.request.body.licenceNo).toBeNull();
    req.flush({ ...ACTIVE_VENDOR }, { status: 201, statusText: 'Created' });
  });

  it('refuses credit days outside 0 to 365, a bad phone and a bad percent', async () => {
    const el = await open({});
    me().form.controls.licenceRequired.setValue(false);
    me().form.patchValue({
      name: 'Test Vendor',
      creditDays: '400',
      contactPhone: '123',
      returnCreditPercent: '120',
      returnDaysBeforeExpiry: '500',
    });
    me().save();
    await settle();
    expect(el.textContent).toContain('Enter 0 to 365 days');
    expect(el.textContent).toContain('Enter a 10-digit phone number');
    expect(el.textContent).toContain('Enter a % from 0 to 100');
    http.expectNone(`${BASE}/vendors`);
  });

  it('sends the vendor with one client key, and a double tap sends one request', async () => {
    await open({});
    me().form.patchValue({
      name: '  Test Vendor  ',
      gstin: '27aaaaa0000a1z5',
      licenceNo: 'TEST-DL-1',
      licenceValidTill: '2028-03-31',
      creditDays: '30',
      returnDaysBeforeExpiry: '90',
      returnCreditPercent: '100',
    });
    me().save();
    me().save();
    const request = http.expectOne(`${BASE}/vendors`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toMatchObject({
      name: 'Test Vendor',
      gstin: '27AAAAA0000A1Z5',
      licenceRequired: true,
      licenceNo: 'TEST-DL-1',
      licenceValidTill: '2028-03-31',
      contactName: null,
      contactPhone: null,
      creditDays: 30,
      returnDaysBeforeExpiry: 90,
      returnCreditPercent: '100',
      suppliesToStoreIds: [CENTRAL.id],
    });
    expect(request.request.body.clientKey).toMatch(/^[0-9a-f-]{36}$/);
    request.flush(ACTIVE_VENDOR);
    expect(close).toHaveBeenCalledWith(ACTIVE_VENDOR);
  });

  describe('the two credit levels (contract 0.2.1, DEC-078 item 5)', () => {
    it('keeps the old field under its label and adds the two optional levels with their test ids', async () => {
      const el = await open({});
      expect(q(el, 'f-vendor-return-percent')?.closest('mat-form-field')?.querySelector('mat-label')?.textContent).toBe(
        'Credit given on return, %',
      );
      expect(q(el, 'f-vendor-damaged-credit')?.closest('mat-form-field')?.querySelector('mat-label')?.textContent).toBe(
        'Credit for damaged on receipt',
      );
      expect(q(el, 'f-vendor-expired-credit')?.closest('mat-form-field')?.querySelector('mat-label')?.textContent).toBe(
        'Credit after expiry',
      );
      expect(me().form.controls.damagedCreditPercent.value).toBe('');
      expect(me().form.controls.expiredCreditPercent.value).toBe('');
    });

    it('sends null for an empty level and the typed value, to 2 decimals, for a filled one', async () => {
      await open({});
      me().form.controls.licenceRequired.setValue(false);
      me().form.patchValue({ name: 'Test Vendor', damagedCreditPercent: '85.5', expiredCreditPercent: '' });
      me().save();
      const request = http.expectOne(`${BASE}/vendors`);
      expect(request.request.body.damagedCreditPercent).toBe('85.5');
      expect(request.request.body.expiredCreditPercent).toBeNull();
      request.flush(ACTIVE_VENDOR);
    });

    it('accepts 0 and 100 and refuses 101, a third decimal and text, without calling the server', async () => {
      const el = await open({});
      me().form.controls.licenceRequired.setValue(false);
      for (const ok of ['0', '100', '99.99', '100.00']) {
        me().form.patchValue({ name: 'Test Vendor', damagedCreditPercent: ok, expiredCreditPercent: ok });
        expect(me().form.controls.damagedCreditPercent.valid).toBe(true);
        expect(me().form.controls.expiredCreditPercent.valid).toBe(true);
      }
      for (const bad of ['101', '10.123', 'abc', '-1']) {
        me().form.patchValue({ damagedCreditPercent: bad, expiredCreditPercent: bad });
        expect(me().form.controls.damagedCreditPercent.valid).toBe(false);
        expect(me().form.controls.expiredCreditPercent.valid).toBe(false);
      }
      me().save();
      await settle();
      expect(el.textContent).toContain('Enter a % from 0 to 100');
      http.expectNone(`${BASE}/vendors`);
    });

    it('shows the stored levels on edit, and clears one with an empty box (null)', async () => {
      const stored = { ...ACTIVE_VENDOR, damagedCreditPercent: '50', expiredCreditPercent: '25.25' };
      const el = await open({ vendor: stored });
      expect((q(el, 'f-vendor-damaged-credit') as HTMLInputElement).value).toBe('50');
      expect((q(el, 'f-vendor-expired-credit') as HTMLInputElement).value).toBe('25.25');
      me().form.patchValue({ expiredCreditPercent: '' });
      me().save();
      const request = http.expectOne(`${BASE}/vendors/${ACTIVE_VENDOR.id}`);
      expect(request.request.body).toMatchObject({ damagedCreditPercent: '50', expiredCreditPercent: null });
      request.flush(ACTIVE_VENDOR);
    });

    it('shows a 400 of the server on the level it names', async () => {
      const el = await open({});
      me().form.controls.licenceRequired.setValue(false);
      me().form.patchValue({ name: 'Test Vendor', damagedCreditPercent: '50' });
      me().save();
      http.expectOne(`${BASE}/vendors`).flush(
        {
          type: 'https://sahaj.in/problems/validation',
          title: 'Validation',
          status: 400,
          detail: 'Check the fields.',
          fieldErrors: [{ field: 'damagedCreditPercent', message: 'Enter a % from 0 to 100' }],
        },
        { status: 400, statusText: 'Bad Request', headers: { 'Content-Type': 'application/problem+json' } },
      );
      await settle();
      expect(el.textContent).toContain('Enter a % from 0 to 100');
      expect(close).not.toHaveBeenCalled();
    });
  });

  it('keeps what was typed and shows the server sentence for a name already in use', async () => {
    const el = await open({});
    me().form.controls.licenceRequired.setValue(false);
    me().form.patchValue({ name: 'Test Vendor' });
    me().save();
    http.expectOne(`${BASE}/vendors`).flush(
      { type: 'https://sahaj.example/problems/vendor-name-in-use', title: 'Conflict', status: 409, detail: 'That vendor is already in the list' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(el.textContent).toContain('That vendor is already in the list');
    expect(me().form.controls.name.value).toBe('Test Vendor');
    expect(close).not.toHaveBeenCalled();
  });

  it('shows the standard line when the save fails and keeps the form', async () => {
    const el = await open({});
    me().form.controls.licenceRequired.setValue(false);
    me().form.patchValue({ name: 'Test Vendor' });
    me().save();
    http.expectOne(`${BASE}/vendors`).error(new ProgressEvent('error'));
    await settle();
    expect(q(el, 'vendor-form-error')?.textContent).toContain('Could not save. Check your connection and try again.');
    expect(me().form.controls.name.value).toBe('Test Vendor');
  });

  it('edits a blocked vendor with its version, and drops a store that is no longer active', async () => {
    const gone = { ...BLOCKED_VENDOR, suppliesTo: [{ storeId: 'store-closed', name: 'Closed' }, { storeId: CENTRAL.id, name: 'Test Central Store' }] };
    const el = await open({ vendor: gone });
    expect(el.querySelector('#vendor-form-title')?.textContent).toContain('Edit vendor');
    expect(me().form.controls.suppliesTo.value).toEqual([CENTRAL.id]);
    expect(me().form.controls.name.value).toBe(BLOCKED_VENDOR.name);
    me().form.patchValue({ licenceValidTill: '2029-03-31' });
    me().save();
    const request = http.expectOne(`${BASE}/vendors/${BLOCKED_VENDOR.id}`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.version).toBe(2);
    expect(request.request.body.licenceValidTill).toBe('2029-03-31');
    expect(request.request.body.clientKey).toBeUndefined();
    request.flush({ ...BLOCKED_VENDOR, status: 'ACTIVE', blocked: false });
  });

  it('is a column capped by the screen whose body scrolls, so Save and Cancel stay in reach (QA-78-5)', async () => {
    const el = await open({});
    const host = getComputedStyle(fixture.nativeElement as HTMLElement);
    expect(host.display).toBe('flex');
    expect(host.flexDirection).toBe('column');
    expect(host.maxHeight).toMatch(/^92d?vh$/);
    const body = getComputedStyle(el.querySelector('.sheet-b') as HTMLElement);
    expect(body.overflowY).toBe('auto');
    expect(body.minHeight).toBe('0px');
    expect(getComputedStyle(el.querySelector('.sheet-h') as HTMLElement).flexShrink).toBe('0');
    expect(getComputedStyle(el.querySelector('.sheet-f') as HTMLElement).flexShrink).toBe('0');
  });
});
