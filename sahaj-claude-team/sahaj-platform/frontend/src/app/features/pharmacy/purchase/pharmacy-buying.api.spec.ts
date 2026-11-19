import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Observable } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyApi } from '../pharmacy.api';
import { debitNote, goodsReceipt, purchaseOrder, purchaseOrderPage } from './purchase.fixtures';

const S = '/api/v1/pharmacy/stores/store-central';

describe('PharmacyApi, work package E (purchase orders, receipts, debit notes) and second signers', () => {
  let api: PharmacyApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    api = TestBed.inject(PharmacyApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the second signers of a store and returns the items', () => {
    let got: unknown;
    api.secondSigners('store-central').subscribe((v) => (got = v));
    http.expectOne(`${S}/second-signers`).flush({ items: [{ membershipId: 'm-2', name: 'Test Pharmacist Two', roleLabel: 'Pharmacist' }] });
    expect(got).toEqual([{ membershipId: 'm-2', name: 'Test Pharmacist Two', roleLabel: 'Pharmacist' }]);
  });

  it('lists purchase orders with several statuses, the overdue flag, a page and a size', () => {
    api.purchaseOrders('store-central', { status: ['SENT', 'PARTLY_RECEIVED'], overdue: true, page: 1, size: 20 }).subscribe();
    const req = http.expectOne((r) => r.url === `${S}/purchase-orders`);
    expect(req.request.params.get('status')).toBe('SENT,PARTLY_RECEIVED');
    expect(req.request.params.get('overdue')).toBe('true');
    expect(req.request.params.get('page')).toBe('1');
    req.flush(purchaseOrderPage());
    api.purchaseOrders('store-central').subscribe();
    const plain = http.expectOne(`${S}/purchase-orders`);
    expect(plain.request.params.keys()).toEqual([]);
    plain.flush(purchaseOrderPage());
  });

  it('calls each purchase order operation on its path with its body', () => {
    const cases: [() => Observable<unknown>, string, string, unknown][] = [
      [() => api.createPurchaseOrder('store-central', { clientKey: 'k', vendorId: 'v', deliveryExpected: null, creditDays: 30, lines: [] }), 'POST', `${S}/purchase-orders`, undefined],
      [() => api.purchaseOrder('store-central', 'po-1'), 'GET', `${S}/purchase-orders/po-1`, undefined],
      [() => api.updatePurchaseOrder('store-central', 'po-1', { version: 1, vendorId: 'v', deliveryExpected: null, creditDays: 30, lines: [] }), 'PUT', `${S}/purchase-orders/po-1`, undefined],
      [() => api.discardPurchaseOrder('store-central', 'po-1', { version: 1 }), 'POST', `${S}/purchase-orders/po-1/discard`, { version: 1 }],
      [() => api.submitPurchaseOrder('store-central', 'po-1', { clientKey: 'k', version: 1 }), 'POST', `${S}/purchase-orders/po-1/submit`, { clientKey: 'k', version: 1 }],
      [() => api.markPurchaseOrderSent('store-central', 'po-1', { version: 1, sentOn: '2026-11-19' }), 'POST', `${S}/purchase-orders/po-1/mark-sent`, { version: 1, sentOn: '2026-11-19' }],
      [() => api.cancelPurchaseOrder('store-central', 'po-1', { version: 1, reasonKey: 'r' }), 'POST', `${S}/purchase-orders/po-1/cancel`, { version: 1, reasonKey: 'r' }],
      [() => api.closePurchaseOrderShort('store-central', 'po-1', { version: 1, reasonKey: 'r' }), 'POST', `${S}/purchase-orders/po-1/close-short`, { version: 1, reasonKey: 'r' }],
      [() => api.copyPurchaseOrder('store-central', 'po-1', { clientKey: 'k' }), 'POST', `${S}/purchase-orders/po-1/copy`, { clientKey: 'k' }],
    ];
    for (const [call, method, url, body] of cases) {
      call().subscribe();
      const req = http.expectOne(url);
      expect(req.request.method).toBe(method);
      if (body !== undefined) {
        expect(req.request.body).toEqual(body);
      }
      req.flush(purchaseOrder());
    }
  });

  it('reads the reorder suggestions and creates drafts from them, answering the drafts', () => {
    api.reorderSuggestions('store-central').subscribe();
    const read = http.expectOne((r) => r.url === `${S}/reorder-suggestions`);
    expect(read.request.params.get('size')).toBe('50');
    read.flush({ items: [], total: 0, page: 0, size: 50 });
    let drafts: unknown[] = [];
    api.createPurchaseOrdersFromSuggestions('store-central', { clientKey: 'k', rows: [] }).subscribe((v) => (drafts = v));
    http.expectOne(`${S}/purchase-orders/from-suggestions`).flush({ items: [purchaseOrder()] });
    expect(drafts.length).toBe(1);
  });

  it('calls each goods receipt operation on its path', () => {
    api.goodsReceipts('store-central', 'DRAFT', 0, 20).subscribe();
    const list = http.expectOne((r) => r.url === `${S}/goods-receipts`);
    expect(list.request.params.get('status')).toBe('DRAFT');
    list.flush({ items: [], total: 0, page: 0, size: 20 });
    const cases: [() => Observable<unknown>, string, string][] = [
      [() => api.createGoodsReceipt('store-central', { clientKey: 'k', purchaseOrderId: 'po-1', vendorId: null, invoiceNo: 'I', invoiceDate: '2026-11-18', creditDays: 30, invoiceTotalPaise: 100, lines: [] }), 'POST', `${S}/goods-receipts`],
      [() => api.goodsReceipt('store-central', 'grn-1'), 'GET', `${S}/goods-receipts/grn-1`],
      [() => api.updateGoodsReceipt('store-central', 'grn-1', { version: 1, invoiceNo: 'I', invoiceDate: '2026-11-18', creditDays: 30, invoiceTotalPaise: 100, lines: [] }), 'PUT', `${S}/goods-receipts/grn-1`],
      [() => api.discardGoodsReceipt('store-central', 'grn-1', { version: 1 }), 'POST', `${S}/goods-receipts/grn-1/discard`],
      [() => api.reopenGoodsReceipt('store-central', 'grn-1', { version: 1 }), 'POST', `${S}/goods-receipts/grn-1/reopen`],
      [() => api.postGoodsReceipt('store-central', 'grn-1', { clientKey: 'k', version: 1, secondSigner: null }), 'POST', `${S}/goods-receipts/grn-1/post`],
    ];
    for (const [call, method, url] of cases) {
      call().subscribe();
      const req = http.expectOne(url);
      expect(req.request.method).toBe(method);
      req.flush(goodsReceipt());
    }
  });

  it('calls each debit note operation on its path and the eligible read with the vendor', () => {
    api.vendorReturnEligible('store-central', 'vendor-1').subscribe();
    const eligibleRead = http.expectOne((r) => r.url === `${S}/vendor-returns/eligible`);
    expect(eligibleRead.request.params.get('vendorId')).toBe('vendor-1');
    eligibleRead.flush({});
    api.debitNotes('store-central', { status: 'DRAFT', vendorId: 'vendor-1' }).subscribe();
    const list = http.expectOne((r) => r.url === `${S}/debit-notes`);
    expect(list.request.params.get('status')).toBe('DRAFT');
    expect(list.request.params.get('vendorId')).toBe('vendor-1');
    list.flush({ items: [], total: 0, page: 0, size: 20 });
    const cases: [() => Observable<unknown>, string, string][] = [
      [() => api.createDebitNote('store-central', { clientKey: 'k', vendorId: 'v', lines: [] }), 'POST', `${S}/debit-notes`],
      [() => api.debitNote('store-central', 'dn-1'), 'GET', `${S}/debit-notes/dn-1`],
      [() => api.updateDebitNote('store-central', 'dn-1', { version: 1, lines: [] }), 'PUT', `${S}/debit-notes/dn-1`],
      [() => api.discardDebitNote('store-central', 'dn-1', { version: 1 }), 'POST', `${S}/debit-notes/dn-1/discard`],
      [() => api.submitDebitNote('store-central', 'dn-1', { clientKey: 'k', version: 1 }), 'POST', `${S}/debit-notes/dn-1/submit`],
      [() => api.markDebitNotePicked('store-central', 'dn-1', { version: 1 }), 'POST', `${S}/debit-notes/dn-1/picked`],
      [() => api.handOverDebitNote('store-central', 'dn-1', { clientKey: 'k', version: 1, representativeName: 'R' }), 'POST', `${S}/debit-notes/dn-1/hand-over`],
      [() => api.recordCreditNote('store-central', 'dn-1', { clientKey: 'k', version: 1, creditNoteNo: 'C', creditNoteDate: '2026-11-19', amountPaise: 100 }), 'POST', `${S}/debit-notes/dn-1/credit-note`],
      [() => api.cancelDebitNote('store-central', 'dn-1', { version: 1, reasonKey: 'r' }), 'POST', `${S}/debit-notes/dn-1/cancel`],
    ];
    for (const [call, method, url] of cases) {
      call().subscribe();
      const req = http.expectOne(url);
      expect(req.request.method).toBe(method);
      req.flush(debitNote());
    }
  });
});
