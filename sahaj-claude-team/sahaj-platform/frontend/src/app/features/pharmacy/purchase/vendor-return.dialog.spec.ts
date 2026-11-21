import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { vendor } from '../pharmacy-c.fixtures';
import { VENDOR_RETURN_CONFIG, VendorReturnDialog } from './vendor-return.dialog';
import { debitNote, eligible } from './purchase.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-central`;

const LEVEL_VENDOR = vendor({ damagedCreditPercent: '100', expiredCreditPercent: '50' });

describe('VendorReturnDialog', () => {
  let fixture: ComponentFixture<VendorReturnDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(items = eligible(), extra: { vendorId?: string; batchId?: string } = {}): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { storeId: 'store-central', ...extra } },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(VendorReturnDialog);
    fixture.detectChanges();
    http.expectOne((r) => r.url === `${BASE}/vendors`).flush({ items: [LEVEL_VENDOR], total: 1, page: 0, size: 100 });
    await settle();
    if (!extra.vendorId) {
      (fixture.componentInstance as unknown as { chooseVendor(id: string): void }).chooseVendor('vendor-1');
    }
    const req = http.expectOne((r) => r.url === `${S}/vendor-returns/eligible`);
    expect(req.request.params.get('vendorId')).toBe('vendor-1');
    req.flush(items);
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();
  const type = async (el: HTMLElement, id: string, value: string) => {
    const input = q(el, id) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  };

  afterEach(() => http.verify());

  it('offers a blocked vendor with the warning "Vendor licence expired" and not an inactive one', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { storeId: 'store-central' } },
        { provide: MatDialogRef, useValue: { close: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(VendorReturnDialog);
    fixture.detectChanges();
    const req = http.expectOne((r) => r.url === `${BASE}/vendors`);
    expect(req.request.params.get('status')).toBe('ALL');
    req.flush({
      items: [
        LEVEL_VENDOR,
        { ...LEVEL_VENDOR, id: 'vendor-b', name: 'Blocked Vendor', status: 'BLOCKED', blocked: true },
        { ...LEVEL_VENDOR, id: 'vendor-i', name: 'Inactive Vendor', status: 'INACTIVE', blocked: false },
      ],
      total: 3,
      page: 0,
      size: 100,
    });
    await settle();
    const comp = fixture.componentInstance as unknown as { vendors(): { id: string }[]; chooseVendor(id: string): void };
    expect(comp.vendors().map((v) => v.id)).toEqual(['vendor-1', 'vendor-b']);
    comp.chooseVendor('vendor-b');
    http.expectOne((r) => r.url === `${S}/vendor-returns/eligible`);
    await settle();
    expect(text(fixture.nativeElement, 'vr-blocked-warning')).toBe('Vendor licence expired');
  });

  it('shows the policy line with the two credit levels and the eligible rows with their source chips', async () => {
    const el = await open();
    expect(VENDOR_RETURN_CONFIG.ariaLabelledBy).toBe('vr-title');
    expect(text(el, 'vr-policy')).toBe(
      'return policy: up to 90 days before expiry, 100% credit; damaged on receipt, 100%; after expiry, 50%',
    );
    expect(el.textContent).toContain('Quarantine · expired');
    expect(el.textContent).toContain('GRN short · GRN-26-27/0009');
    expect(el.textContent).toContain('short supply, no stock movement');
    expect(text(el, 'vr-footer')).toBe('Debit note · 0 lines · ₹ 0.00 + GST reversal');
  });

  it('starts Credit % from the expired level for an expired batch and marks a changed value', async () => {
    const el = await open();
    expect((q(el, 'vr-credit-percent-0') as HTMLInputElement).value).toBe('50');
    expect(q(el, 'vr-changed-0')).toBeNull();
    await type(el, 'vr-credit-percent-0', '60');
    expect(text(el, 'vr-changed-0')).toBe('Changed from policy');
  });

  it('works the credit as quantity times rate times credit percent and totals the selected lines', async () => {
    const el = await open();
    (q(el, 'vr-select-0') as HTMLInputElement).click();
    await settle();
    expect(text(el, 'vr-credit-0')).toBe('₹ 705.00');
    expect(text(el, 'vr-footer')).toBe('Debit note · 1 line · ₹ 705.00 + GST reversal');
    await type(el, 'vr-qty-0', '10');
    expect(text(el, 'vr-credit-0')).toBe('₹ 352.50');
  });

  it('refuses more than the units available and a credit above 100', async () => {
    const el = await open();
    (q(el, 'vr-select-0') as HTMLInputElement).click();
    await type(el, 'vr-qty-0', '25');
    q(el, 'vr-create')?.click();
    await settle();
    expect(el.textContent).toContain('Enter 1 to 20');
    http.expectNone(`${S}/debit-notes`);
    await type(el, 'vr-qty-0', '5');
    await type(el, 'vr-credit-percent-0', '120');
    q(el, 'vr-create')?.click();
    await settle();
    expect(el.textContent).toContain('Enter a % from 0 to 100');
    http.expectNone(`${S}/debit-notes`);
  });

  it('creates the draft with a stock line and a short supply line, then submits it', async () => {
    const el = await open();
    (q(el, 'vr-select-0') as HTMLInputElement).click();
    (q(el, 'vr-select-1') as HTMLInputElement).click();
    await settle();
    q(el, 'vr-create')?.click();
    const create = http.expectOne(`${S}/debit-notes`);
    expect(create.request.body.vendorId).toBe('vendor-1');
    expect(create.request.body.lines).toEqual([
      { type: 'STOCK_RETURN', batchId: 'batch-1', sourceState: 'QUARANTINE', qty: 20, creditPercent: '50' },
      { type: 'SHORT_SUPPLY', goodsReceiptLineId: 'grn-line-9', qty: 4, creditPercent: '100' },
    ]);
    create.flush(debitNote({ status: 'DRAFT', number: null }), { status: 201, statusText: 'Created' });
    const submit = http.expectOne(`${S}/debit-notes/dn-1/submit`);
    expect(submit.request.body.version).toBe(2);
    submit.flush(debitNote());
    expect(close).toHaveBeenCalledWith(expect.objectContaining({ number: 'DN-26-27/0001' }));
  });

  it('keeps the selections and shows the server sentence when units are no longer there', async () => {
    const el = await open();
    (q(el, 'vr-select-0') as HTMLInputElement).click();
    await settle();
    q(el, 'vr-create')?.click();
    http.expectOne(`${S}/debit-notes`).flush(debitNote({ status: 'DRAFT', number: null }), { status: 201, statusText: 'Created' });
    http.expectOne(`${S}/debit-notes/dn-1/submit`).flush(
      { type: 'https://sahaj.in/problems/insufficient-stock', title: 'Insufficient', status: 409, detail: 'Some units are no longer available. Nothing was held.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(text(el, 'vr-error')).toContain('Some units are no longer available');
    expect((q(el, 'vr-select-0') as HTMLInputElement).checked).toBe(true);
    expect(close).not.toHaveBeenCalled();
  });

  it('opens with the vendor and the batch of a batch row chosen when it comes from Return (story 79-3)', async () => {
    const el = await open(eligible(), { vendorId: 'vendor-1', batchId: 'batch-1' });
    expect((q(el, 'vr-select-0') as HTMLInputElement).checked).toBe(true);
    expect((q(el, 'vr-select-1') as HTMLInputElement).checked).toBe(false);
    expect(text(el, 'vr-footer')).toBe('Debit note · 1 line · ₹ 705.00 + GST reversal');
  });

  it('shows a dash for a quantity hidden while a count is open and never works with it', async () => {
    const base = eligible();
    const el = await open({ ...base, items: [{ ...base.items[0], availableQty: null }] });
    expect(el.textContent).toContain('—');
    expect(el.textContent).not.toContain('null');
    expect((q(el, 'vr-qty-0') as HTMLInputElement).value).toBe('');
  });
});
