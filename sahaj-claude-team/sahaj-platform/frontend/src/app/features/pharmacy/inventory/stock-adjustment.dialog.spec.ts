import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { adjustment, batchRow, SCHEDULE_X_REF } from './inventory.fixtures';
import { STOCK_ADJUSTMENT_CONFIG, StockAdjustmentData, StockAdjustmentDialog } from './stock-adjustment.dialog';
import { SecondSignerCandidate, StockBatchRow } from '../pharmacy-inventory.model';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-central`;
const REASONS = {
  lists: [
    {
      listKey: 'stock-adjustment',
      reasons: [
        { reasonKey: 'stock-adjustment.count-mismatch', label: 'Count mismatch', needsNote: false, fixed: false },
        { reasonKey: 'stock-adjustment.other', label: 'Other', needsNote: true, fixed: false },
      ],
    },
  ],
};

interface Internals {
  form: StockAdjustmentDialog['form'];
  submit(): void;
}

describe('StockAdjustmentDialog', () => {
  let fixture: ComponentFixture<StockAdjustmentDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(
    preset: StockBatchRow | null = batchRow(),
    role = 'PHARMACIST',
    signers: SecondSignerCandidate[] = [],
  ): Promise<HTMLElement> {
    close = vi.fn();
    const data: StockAdjustmentData = {
      storeId: 'store-central',
      storeName: 'Test Central Store',
      preset,
      callerMembershipId: 'm-me',
      callerRole: role,
    };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(StockAdjustmentDialog);
    fixture.detectChanges();
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush(REASONS);
    if (preset) {
      http.expectOne(`${S}/second-signers`).flush({ items: signers });
    }
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

  async function type(physical: number | null): Promise<void> {
    me().form.controls.physical.setValue(physical);
    await settle();
  }

  afterEach(() => http.verify());

  it('names the sheet, shows the batch, the book quantity and no photo field', async () => {
    const el = await open();
    expect(STOCK_ADJUSTMENT_CONFIG.ariaLabelledBy).toBe('adjust-title');
    expect(el.querySelector('#adjust-title')?.textContent).toBe('Stock adjustment');
    expect(text(el, 'adjust-batch-line')).toContain('Sample Ceftriaxone 1 g injection · TESTB001');
    expect(text(el, 'book-qty')).toBe('40 vial');
    expect(q(el, 'submit-adjustment')?.textContent?.trim()).toBe('Submit adjustment');
    expect(el.querySelector('input[type="file"]')).toBeNull();
    expect(el.textContent?.toLowerCase()).not.toContain('photo');
  });

  it('shows the difference as −n with its value at the purchase rate, without GST', async () => {
    const el = await open();
    await type(38);
    expect(text(el, 'difference')).toBe('−2 vial · ₹ 141.00');
    await type(43);
    expect(text(el, 'difference')).toBe('+3 vial · ₹ 211.50');
  });

  it('refuses no change and a bad number with the contract sentences, and sends nothing', async () => {
    const el = await open();
    await type(40);
    q(el, 'submit-adjustment')?.click();
    await settle();
    expect(el.textContent).toContain('No change to post');
    await type(-3);
    q(el, 'submit-adjustment')?.click();
    await settle();
    expect(el.textContent).toContain('Enter a whole number of 0 or more');
    http.expectNone(`${S}/stock-adjustments`);
  });

  it('needs a reason ("Choose a reason") and a note of 4 characters for Other', async () => {
    const el = await open();
    await type(38);
    me().submit();
    await settle();
    expect(text(el, 'reason-error')).toBe('Choose a reason');
    q(el, 'reason-stock-adjustment.other')?.click();
    await settle();
    me().submit();
    await settle();
    expect(el.textContent).toContain('Write at least 4 characters');
    http.expectNone(`${S}/stock-adjustments`);
  });

  it('submits with the book quantity seen, the reason and one client key, and closes with the adjustment', async () => {
    const el = await open();
    await type(38);
    q(el, 'reason-stock-adjustment.count-mismatch')?.click();
    await settle();
    me().submit();
    me().submit();
    const req = http.expectOne(`${S}/stock-adjustments`);
    expect(req.request.body).toEqual(
      expect.objectContaining({
        batchId: 'batch-1',
        bookQtySeen: 40,
        physicalQty: 38,
        reasonKey: 'stock-adjustment.count-mismatch',
        note: null,
        secondSigner: null,
      }),
    );
    expect(req.request.body.clientKey).toMatch(/^[0-9a-f-]{36}$/);
    req.flush(adjustment(), { status: 201, statusText: 'Created' });
    await settle();
    expect(close).toHaveBeenCalledOnce();
    expect(close.mock.calls[0][0].number).toBe('ADJ-0001');
  });

  it('keeps what was typed and says so when the server is unreachable', async () => {
    const el = await open();
    await type(38);
    q(el, 'reason-stock-adjustment.count-mismatch')?.click();
    await settle();
    me().submit();
    http.expectOne(`${S}/stock-adjustments`).flush(null, { status: 0, statusText: 'Unknown Error' });
    await settle();
    expect(text(el, 'adjust-error')).toBe('Could not save. Check your connection and try again.');
    expect(me().form.controls.physical.value).toBe(38);
    expect(close).not.toHaveBeenCalled();
  });

  it('shows the sentence of an adjustment already waiting for the batch', async () => {
    const el = await open();
    await type(38);
    q(el, 'reason-stock-adjustment.count-mismatch')?.click();
    await settle();
    me().submit();
    http.expectOne(`${S}/stock-adjustments`).flush(
      { type: 'https://sahaj.in/problems/adjustment-waiting', title: 'Waiting', status: 409, detail: 'An adjustment for this batch is already waiting.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(text(el, 'adjust-error')).toBe('An adjustment for this batch is already waiting.');
  });

  it('reads the batch again when the book quantity changed and shows the new figure', async () => {
    const el = await open();
    await type(38);
    q(el, 'reason-stock-adjustment.count-mismatch')?.click();
    await settle();
    me().submit();
    http.expectOne(`${S}/stock-adjustments`).flush(
      { type: 'https://sahaj.in/problems/changed', title: 'Changed', status: 409, detail: 'The stock changed. Check the quantity again.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    http.expectOne(`${S}/stock/products/prod-1`).flush({
      product: batchRow().product,
      salt: 'Ceftriaxone',
      hsn: '3004',
      gstRate: '12',
      rack: null,
      reorderLevel: null,
      batches: [batchRow({ qty: 35 })],
    });
    await settle();
    expect(text(el, 'book-qty')).toBe('35 vial');
  });

  it('blocks an owner from raising a Schedule X adjustment and says why', async () => {
    const el = await open(batchRow({ product: SCHEDULE_X_REF }), 'OWNER');
    expect(text(el, 'schedule-x-owner')).toContain('An owner cannot raise it');
    expect((q(el, 'submit-adjustment') as HTMLButtonElement).disabled).toBe(true);
  });

  it('asks a pharmacist for a second signer and their PIN on Schedule X, and sends them without echoing the PIN', async () => {
    const el = await open(batchRow({ product: SCHEDULE_X_REF }), 'PHARMACIST', [
      { membershipId: 'm-2', name: 'Test Pharmacist Two', roleLabel: 'Pharmacist' },
    ]);
    expect(q(el, 'schedule-x-note')).toBeTruthy();
    expect(q(el, 'f-pin')?.getAttribute('type')).toBe('password');
    await type(38);
    q(el, 'reason-stock-adjustment.count-mismatch')?.click();
    await settle();
    me().submit();
    await settle();
    expect(el.textContent).toContain('Choose the second signer');
    http.expectNone(`${S}/stock-adjustments`);
    me().form.patchValue({ signerId: 'm-2', pin: '4821' });
    me().submit();
    const req = http.expectOne(`${S}/stock-adjustments`);
    expect(req.request.body.secondSigner).toEqual({ membershipId: 'm-2', pin: '4821' });
    req.flush(adjustment(), { status: 201, statusText: 'Created' });
    await settle();
    expect(me().form.controls.pin.value).toBe('');
  });

  it('lists the second signers from the second-signers read and never the caller', async () => {
    const el = await open(batchRow({ product: SCHEDULE_X_REF }), 'PHARMACIST', [
      { membershipId: 'm-2', name: 'Test Pharmacist Two', roleLabel: 'Pharmacist' },
      { membershipId: 'm-3', name: 'Test Owner', roleLabel: 'Owner' },
    ]);
    me().form.controls.signerId.markAsTouched();
    expect(q(el, 'no-second-signer')).toBeNull();
    expect(
      (fixture.componentInstance as unknown as { signers(): SecondSignerCandidate[] }).signers().map((p) => p.membershipId),
    ).toEqual(['m-2', 'm-3']);
  });

  it('says so when nobody else can sign', async () => {
    const el = await open(batchRow({ product: SCHEDULE_X_REF }), 'PHARMACIST', []);
    expect(text(el, 'no-second-signer')).toBe('No other pharmacist or owner is available to sign.');
  });
});
