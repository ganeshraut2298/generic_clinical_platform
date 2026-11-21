import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { ACTIVE_VENDOR } from '../pharmacy-c.fixtures';
import { PharmacyContext } from '../pharmacy.model';
import { GRN_DIALOG_CONFIG, GrnDialog, GrnDialogData } from './grn.dialog';
import { BUYER_CONTEXT, BUYER_NO_X_CONTEXT, goodsReceipt, grnLine, purchaseOrder } from './purchase.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-central`;

interface Internals {
  form: GrnDialog['form'];
  saveDraft(): void;
  post(): void;
  addProduct(p: { productId: string; code: string; name: string; strength: string; form: string }): void;
}

const PICKED = { productId: 'prod-1', code: 'G-000101', name: 'Sample Ceftriaxone', strength: '1 g', form: 'Injection' };

describe('GrnDialog', () => {
  let fixture: ComponentFixture<GrnDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(data: GrnDialogData, context: PharmacyContext = BUYER_CONTEXT): Promise<HTMLElement> {
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
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(GrnDialog);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  /** Opens an existing receipt, answers the receipt and the purchase order read that finds the PO line of each line. */
  async function openGrn(grn = goodsReceipt(), context: PharmacyContext = BUYER_CONTEXT, signers = false): Promise<HTMLElement> {
    const el = await open({ storeId: 'store-central', grnId: 'grn-1' }, context);
    http.expectOne(`${S}/goods-receipts/grn-1`).flush(grn);
    if (grn.purchaseOrder) {
      http.expectOne(`${S}/purchase-orders/po-1`).flush(purchaseOrder());
    }
    if (signers) {
      http.expectOne(`${S}/second-signers`).flush({ items: [{ membershipId: 'm-2', name: 'Test Pharmacist Two', roleLabel: 'Pharmacist' }] });
    }
    await settle();
    return el;
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

  it('shows the five steps, the header fields and no OCR source', async () => {
    const el = await open({ storeId: 'store-central' });
    http.expectOne((r) => r.url === `${BASE}/vendors`).flush({ items: [ACTIVE_VENDOR], total: 1, page: 0, size: 100 });
    await settle();
    expect(GRN_DIALOG_CONFIG.ariaLabelledBy).toBe('grn-title');
    const steps = Array.from(el.querySelectorAll('[data-testid="grn-steps"] li')).map((l) => l.textContent?.trim());
    expect(steps).toEqual(['1 · PO', '2 · Enter invoice', '3 · Verify', '4 · Post to stock', '5 · Payable']);
    const labels = Array.from(el.querySelectorAll('mat-label')).map((l) => l.textContent?.trim());
    expect(labels).toEqual(['Vendor', 'Vendor invoice number', 'Invoice date', 'Credit days', 'Invoice total', 'Add a product']);
    expect(el.textContent?.toLowerCase()).not.toContain('ocr');
    expect(el.textContent?.toLowerCase()).not.toContain('photo');
  });

  it('starts against a purchase order with no lines typed and fills them from the server', async () => {
    const el = await open({ storeId: 'store-central', po: { id: 'po-1', number: 'PO-26-27/0001', vendorName: 'Test Distributors Pvt. Ltd. (fake)' } });
    expect(text(el, 'grn-matched')).toContain('Matched to PO-26-27/0001');
    me().form.patchValue({ invoiceNo: 'TEST-INV-1', invoiceTotal: '832.16' });
    me().saveDraft();
    const req = http.expectOne(`${S}/goods-receipts`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toMatchObject({
      purchaseOrderId: 'po-1',
      vendorId: null,
      invoiceNo: 'TEST-INV-1',
      creditDays: 30,
      invoiceTotalPaise: 83216,
      lines: [],
    });
    req.flush(goodsReceipt(), { status: 201, statusText: 'Created' });
    http.expectOne(`${S}/purchase-orders/po-1`).flush(purchaseOrder());
    await settle();
    expect(q(el, 'grn-lines')).toBeTruthy();
    expect(text(el, 'grn-landed-0')).toBe('₹ 7.43');
    expect(text(el, 'grn-margin-0')).toBe('38.1%');
    expect(text(el, 'grn-gst-0')).toBe('12');
    expect(text(el, 'grn-check-0')).toBe('OK');
  });

  it('asks for the invoice fields and refuses a past expiry and a bad PTR on a line', async () => {
    const el = await open({ storeId: 'store-central' });
    http.expectOne((r) => r.url === `${BASE}/vendors`).flush({ items: [ACTIVE_VENDOR], total: 1, page: 0, size: 100 });
    me().addProduct(PICKED);
    await settle();
    me().saveDraft();
    await settle();
    expect(el.textContent).toContain('Choose a vendor');
    expect(el.textContent).toContain('Enter the vendor invoice number');
    expect(el.textContent).toContain('Enter the invoice total as printed');
    me().form.patchValue({ vendorId: 'vendor-1', invoiceNo: 'TEST-INV-2', invoiceTotal: '100' });
    me().form.controls.lines.at(0).patchValue({ batchNo: 'B1', expiryMonth: '2020-01', qty: '5', billedQty: '5', ptr: '0', mrp: '10' });
    me().saveDraft();
    await settle();
    expect(text(el, 'grn-line-0')).toContain('Expiry must be in the future');
    http.expectNone(`${S}/goods-receipts`);
    me().form.controls.lines.at(0).patchValue({ expiryMonth: '2999-03' });
    me().saveDraft();
    await settle();
    expect(text(el, 'grn-line-0')).toContain('PTR and MRP are above 0');
    http.expectNone(`${S}/goods-receipts`);
  });

  it('flags a rate above the PO rate and short supply on the line and offers the short action', async () => {
    const el = await openGrn(
      goodsReceipt({
        lines: [grnLine({ checks: ['RATE_UP', 'SHORT', 'MRP_CHANGED'], ratePercent: '+6.1%', rateBasis: 'PO', shortQty: 4 })],
      }),
    );
    expect(text(el, 'grn-check-0')).toContain('Rate ↑ 6.1% vs PO');
    expect(text(el, 'grn-check-0')).toContain('Short 4 vs PO');
    expect(text(el, 'grn-check-0')).toContain('MRP changed');
    expect(q(el, 'grn-short-0')).toBeTruthy();
    expect(q(el, 'grn-claim-0')).toBeTruthy();
    expect(text(el, 'grn-post')).toBe('Send for approval (rate ↑)');
  });

  it('shows the 3-way match only when a line differs, and the total difference', async () => {
    const plain = await openGrn();
    expect(q(plain, 'grn-three-way')).toBeNull();
    expect(text(plain, 'grn-difference')).toBe('Difference ₹ 0.00');
    TestBed.resetTestingModule();
    const el = await openGrn(
      goodsReceipt({
        totalDifferencePaise: 150,
        threeWayMatch: [{ lineId: 'l1', product: 'Sample Ceftriaxone', poQty: 10, billedQty: 10, receivedQty: 8, poRate: '70.0000', billedRate: '74.3000' }],
      }),
    );
    expect(text(el, 'grn-three-way')).toContain('3-way match');
    expect(text(el, 'grn-three-way')).toContain('₹ 74.30');
    expect(text(el, 'grn-difference')).toBe('Difference ₹ 1.50');
  });

  it('posts a draft: saves the lines, then posts once with one key, and shows the payable and the prepared debit note', async () => {
    const el = await openGrn();
    me().post();
    me().post();
    http.expectOne(`${S}/goods-receipts/grn-1`).flush(goodsReceipt());
    const post = http.expectOne(`${S}/goods-receipts/grn-1/post`);
    expect(post.request.body.secondSigner).toBeNull();
    expect(post.request.body.clientKey).toMatch(/[0-9a-f-]{36}/);
    post.flush(
      goodsReceipt({
        status: 'POSTED',
        number: 'GRN-26-27/0001',
        outcome: 'POSTED',
        step: 'PAYABLE',
        payable: {
          payableId: 'pay-1',
          vendor: goodsReceipt().vendor,
          invoiceNo: 'TEST-INV-1',
          invoiceDate: '2026-11-18',
          amountPaise: 83216,
          creditedPaise: 0,
          openPaise: 83216,
          dueDate: '2026-12-18',
          status: 'OPEN',
        },
        debitNoteDraft: { dnId: 'dn-9', lineCount: 1, amountPaise: 12000 },
      }),
    );
    await settle();
    expect(text(el, 'grn-status')).toBe('Posted');
    expect(text(el, 'grn-payable')).toBe('Payable · due 18 Dec 2026 · ₹ 832.16');
    expect(text(el, 'grn-debit-note')).toBe('Debit note prepared: 1 line, ₹ 120.00');
    expect(q(el, 'grn-post')).toBeNull();
    expect(q(el, 'grn-save-draft')).toBeNull();
  });

  it('shows the waiting state after a rate rise goes for approval and nothing is posted', async () => {
    const el = await openGrn();
    me().post();
    http.expectOne(`${S}/goods-receipts/grn-1`).flush(goodsReceipt());
    http.expectOne(`${S}/goods-receipts/grn-1/post`).flush(
      goodsReceipt({
        status: 'WAITING_APPROVAL',
        outcome: 'SENT_FOR_APPROVAL',
        approval: {
          requestId: 'req-2',
          requestVersion: 1,
          levelNo: 1,
          status: 'PENDING',
          currentLevel: 1,
          ruleText: 'Rate up more than 5%',
          waitingFor: 'HOD',
          escalated: false,
          needsAnotherApprover: false,
          canDecide: false,
        },
      }),
    );
    await settle();
    expect(text(el, 'grn-status')).toBe('Waiting for approval');
    expect(text(el, 'grn-approval')).toContain('Nothing is posted until it is approved');
    expect(q(el, 'grn-post')).toBeNull();
    q(el, 'grn-open-approvals')?.click();
    expect(close).toHaveBeenCalledWith({ changed: true, next: 'approvals' });
  });

  it('says that invoice was already received on a duplicate invoice and keeps the lines', async () => {
    const el = await openGrn();
    me().saveDraft();
    http.expectOne(`${S}/goods-receipts/grn-1`).flush(
      { type: 'https://sahaj.in/problems/duplicate-invoice', title: 'Duplicate', status: 409, detail: 'That invoice was already received.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(el.textContent).toContain('That invoice was already received.');
    expect(q(el, 'grn-lines')).toBeTruthy();
  });

  it('shows the licence warning on a receipt against an order of a vendor that became blocked', async () => {
    const el = await openGrn(goodsReceipt({ warnings: ['VENDOR_LICENCE_EXPIRED'] }));
    expect(text(el, 'grn-licence-warning')).toBe('Drug licence expired');
  });

  it('needs a pharmacist and a second signer for a Schedule X line, and sends the PIN only with the post', async () => {
    const el = await openGrn(goodsReceipt({ lines: [grnLine({ scheduleX: true })] }), BUYER_CONTEXT, true);
    expect(text(el, 'grn-schedule-x')).toBe('Pharmacist and second signature needed');
    expect(q(el, 'f-grn-pin')?.getAttribute('type')).toBe('password');
    me().post();
    await settle();
    expect(el.textContent).toContain('Choose the second signer');
    http.expectNone(`${S}/goods-receipts/grn-1/post`);
    me().form.patchValue({ signerId: 'm-2', pin: '4821' });
    me().post();
    http.expectOne(`${S}/goods-receipts/grn-1`).flush(goodsReceipt({ lines: [grnLine({ scheduleX: true })] }));
    const req = http.expectOne(`${S}/goods-receipts/grn-1/post`);
    expect(req.request.body.secondSigner).toEqual({ membershipId: 'm-2', pin: '4821' });
    req.flush(goodsReceipt({ status: 'POSTED', number: 'GRN-26-27/0002', lines: [grnLine({ scheduleX: true })] }));
    await settle();
    expect(me().form.controls.pin.value).toBe('');
  });

  it('says so when no other pharmacist or owner can sign', async () => {
    const el = await open({ storeId: 'store-central', grnId: 'grn-1' });
    http.expectOne(`${S}/goods-receipts/grn-1`).flush(goodsReceipt({ lines: [grnLine({ scheduleX: true })] }));
    http.expectOne(`${S}/purchase-orders/po-1`).flush(purchaseOrder());
    http.expectOne(`${S}/second-signers`).flush({ items: [] });
    await settle();
    expect(text(el, 'grn-no-second-signer')).toBe('No other pharmacist or owner is available to sign.');
  });

  it('hides posting from a person who may not handle Schedule X and says why', async () => {
    const el = await openGrn(goodsReceipt({ lines: [grnLine({ scheduleX: true })] }), BUYER_NO_X_CONTEXT, true);
    expect(text(el, 'grn-pharmacist-needed')).toContain('need a pharmacist and a second signature');
    expect(q(el, 'grn-post')).toBeNull();
  });

  it('reopens an approved receipt and discards a draft', async () => {
    const el = await openGrn(goodsReceipt({ status: 'APPROVED' }));
    q(el, 'grn-reopen')?.click();
    const reopen = http.expectOne(`${S}/goods-receipts/grn-1/reopen`);
    expect(reopen.request.body).toEqual({ version: 1 });
    reopen.flush(goodsReceipt({ status: 'DRAFT' }));
    await settle();
    expect(text(el, 'grn-status')).toBe('Draft');
    q(el, 'grn-discard')?.click();
    http.expectOne(`${S}/goods-receipts/grn-1/discard`).flush(goodsReceipt({ status: 'DISCARDED' }));
    expect(close).toHaveBeenCalledWith({ changed: true, next: null });
  });
});
