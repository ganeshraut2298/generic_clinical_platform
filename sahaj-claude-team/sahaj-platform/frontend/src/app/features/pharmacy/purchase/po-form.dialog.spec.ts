import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ACTIVE_VENDOR, BLOCKED_VENDOR, vendor } from '../pharmacy-c.fixtures';
import { PO_FORM_CONFIG, PoFormData, PoFormDialog } from './po-form.dialog';
import { purchaseOrder } from './purchase.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-central`;

interface Internals {
  form: PoFormDialog['form'];
  save(): void;
  addProduct(p: { productId: string; code: string; name: string; strength: string; form: string }): void;
}

const PICKED = { productId: 'prod-1', code: 'G-000101', name: 'Sample Ceftriaxone', strength: '1 g', form: 'Injection' };

describe('PoFormDialog', () => {
  let fixture: ComponentFixture<PoFormDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;
  let vendorsParam: string | null = null;

  async function open(data: PoFormData = { storeId: 'store-central' }): Promise<HTMLElement> {
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
    fixture = TestBed.createComponent(PoFormDialog);
    fixture.detectChanges();
    const vendorsReq = http.expectOne((r) => r.url === `${BASE}/vendors`);
    vendorsParam = vendorsReq.request.params.get('suppliesStoreId');
    vendorsReq.flush({
      items: [ACTIVE_VENDOR, BLOCKED_VENDOR],
      total: 2,
      page: 0,
      size: 100,
    });
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
  const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

  afterEach(() => http.verify());

  it('names the sheet title that the dialog config points to', async () => {
    const el = await open();
    expect(PO_FORM_CONFIG.ariaLabelledBy).toBe('po-form-title');
    expect(el.querySelector('#po-form-title')?.textContent).toContain('New purchase order');
  });

  it('offers only active, unblocked vendors that supply the Central Store', async () => {
    await open();
    expect(vendorsParam).toBe('store-central');
    const vendors = (fixture.componentInstance as unknown as { vendors(): { id: string }[] }).vendors();
    expect(vendors.map((v) => v.id)).toEqual(['vendor-1']);
  });

  it('asks for a vendor, the lines and the delivery date without calling the server', async () => {
    const el = await open();
    me().save();
    await settle();
    expect(el.textContent).toContain('Choose a vendor');
    expect(text(el, 'po-no-lines')).toBe('Add at least one item');
    http.expectNone(`${S}/purchase-orders`);
  });

  it('refuses a delivery date in the past and a rate of 0, and the same product twice', async () => {
    const el = await open();
    me().addProduct(PICKED);
    me().addProduct(PICKED);
    await settle();
    expect(text(el, 'po-duplicate')).toBe('Already on this purchase order');
    me().form.patchValue({ vendorId: 'vendor-1', deliveryExpected: '2020-01-01' });
    me().form.controls.lines.at(0).patchValue({ qty: '5', rate: '0' });
    me().save();
    await settle();
    expect(el.textContent).toContain('Delivery date must be today or later');
    expect(el.textContent).toContain('Enter a rate above 0, up to 4 decimals');
    http.expectNone(`${S}/purchase-orders`);
  });

  it('shows the line amount and the subtotal as typed, and takes the credit days from the vendor', async () => {
    const el = await open();
    me().form.controls.vendorId.setValue('vendor-1');
    (fixture.componentInstance as unknown as { vendorChosen(id: string): void }).vendorChosen('vendor-1');
    me().addProduct(PICKED);
    await settle();
    me().form.controls.lines.at(0).patchValue({ qty: '10', rate: '70.00' });
    await settle();
    expect(text(el, 'po-amount-0')).toBe('₹ 700.00');
    expect(text(el, 'po-total')).toBe('Subtotal ₹ 700.00');
    expect(me().form.controls.creditDays.value).toBe('30');
  });

  it('shows the credit days empty with "From the vendor" when the vendor view hides them, and the server fills it', async () => {
    const el = await open();
    const reduced = vendor({ id: 'vendor-5', name: 'Test Reduced (fake)', creditDays: null, suppliesTo: null, gstin: null });
    (fixture.componentInstance as unknown as { vendors: { set(v: unknown[]): void } }).vendors.set([reduced]);
    me().form.controls.vendorId.setValue('vendor-5');
    (fixture.componentInstance as unknown as { vendorChosen(id: string): void }).vendorChosen('vendor-5');
    await settle();
    expect(me().form.controls.creditDays.value).toBe('');
    expect(text(el, 'po-credit-hint')).toBe('From the vendor');
    me().form.patchValue({ deliveryExpected: '2999-01-31' });
    me().addProduct(PICKED);
    me().form.controls.lines.at(0).patchValue({ qty: '10', rate: '70.00' });
    me().save();
    const req = http.expectOne(`${S}/purchase-orders`);
    expect('creditDays' in req.request.body).toBe(false);
    req.flush(purchaseOrder({ status: 'DRAFT', number: null }), { status: 201, statusText: 'Created' });
    await settle();
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('lets a person with no credit view type another value', async () => {
    const el = await open();
    me().form.patchValue({ vendorId: 'vendor-1', creditDays: '' });
    await settle();
    expect(q(el, 'po-credit-hint')).toBeTruthy();
    me().form.patchValue({ creditDays: '15', deliveryExpected: '2999-01-31' });
    await settle();
    expect(q(el, 'po-credit-hint')).toBeNull();
    me().addProduct(PICKED);
    me().form.controls.lines.at(0).patchValue({ qty: '1', rate: '10' });
    me().save();
    expect(http.expectOne(`${S}/purchase-orders`).request.body.creditDays).toBe(15);
  });

  it('sends the draft with one client key and a double tap sends one request', async () => {
    await open();
    me().form.patchValue({ vendorId: 'vendor-1', deliveryExpected: '2999-01-31', creditDays: '45' });
    me().addProduct(PICKED);
    me().form.controls.lines.at(0).patchValue({ qty: '10', rate: '70.00' });
    me().save();
    me().save();
    const req = http.expectOne(`${S}/purchase-orders`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toMatchObject({
      vendorId: 'vendor-1',
      deliveryExpected: '2999-01-31',
      creditDays: 45,
      lines: [{ productId: 'prod-1', qty: 10, rate: '70.00' }],
    });
    expect(req.request.body.clientKey).toMatch(/[0-9a-f-]{36}/);
    req.flush(purchaseOrder({ status: 'DRAFT', number: null }), { status: 201, statusText: 'Created' });
    await settle();
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('keeps what was typed and says why when the server refuses with vendor-blocked', async () => {
    const el = await open();
    me().form.patchValue({ vendorId: 'vendor-1', deliveryExpected: '2999-01-31' });
    me().addProduct(PICKED);
    me().form.controls.lines.at(0).patchValue({ qty: '10', rate: '70.00' });
    me().save();
    http.expectOne(`${S}/purchase-orders`).flush(
      { type: 'https://sahaj.in/problems/vendor-blocked', title: 'Vendor blocked', status: 409, detail: "This vendor's drug licence has expired. It cannot get a new purchase order." },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(text(el, 'po-form-error')).toContain('drug licence has expired');
    expect(me().form.controls.lines.length).toBe(1);
    expect(close).not.toHaveBeenCalled();
  });

  it('saves a change of a draft with its version', async () => {
    await open({ storeId: 'store-central', po: purchaseOrder({ status: 'DRAFT', number: null, deliveryExpected: '2999-01-31' }) });
    me().save();
    const req = http.expectOne(`${S}/purchase-orders/po-1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.version).toBe(3);
    expect(req.request.body.lines[0]).toEqual({ productId: 'prod-1', qty: 10, rate: '70' });
    req.flush(purchaseOrder({ status: 'DRAFT' }));
  });
});
