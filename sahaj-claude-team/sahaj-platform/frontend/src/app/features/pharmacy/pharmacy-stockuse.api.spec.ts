import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from './pharmacy-context.store';
import { PharmacyApi } from './pharmacy.api';
import { countSheet, reportPage, stockAudit, storeContext, transfer, varianceReview } from './stockuse/stockuse.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/s1`;

describe('PharmacyApi, SAH-79 work packages G and H', () => {
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

  it('lists the open transfers of a store and requests one', () => {
    api.transfers('s1').subscribe();
    const list = http.expectOne((r) => r.url === `${S}/transfers`);
    expect(list.request.params.get('open')).toBe('true');
    list.flush({ items: [], total: 0, page: 0, size: 20 });
    api.createTransfer('s1', { clientKey: 'k', fromStoreId: 'a', toStoreId: 'b', reasonKey: 'transfer.x', lines: [{ productId: 'p', quantity: 1 }] }).subscribe();
    const post = http.expectOne(`${S}/transfers`);
    expect(post.request.method).toBe('POST');
    post.flush(transfer());
  });

  it('dispatches and cancels a transfer', () => {
    api.dispatchTransfer('s1', 't1', { clientKey: 'k', version: 1 }).subscribe();
    http.expectOne(`${S}/transfers/t1/dispatch`).flush(transfer());
    api.cancelTransfer('s1', 't1', { version: 1, reasonKey: 'approval-reject.other' }).subscribe();
    http.expectOne(`${S}/transfers/t1/cancel`).flush(transfer());
  });

  it('reads the admitted patients with a search text and the ordered-by options', () => {
    api.admittedPatients('s1', 'IP-7').subscribe();
    const req = http.expectOne((r) => r.url === `${S}/consumption/admitted-patients`);
    expect(req.request.params.get('q')).toBe('IP-7');
    req.flush({ items: [] });
    api.orderedByOptions().subscribe();
    http.expectOne(`${BASE}/consumption/ordered-by-options`).flush({ items: [] });
  });

  it('counts blind: the count sheet is read for the counter and the counts are saved with the version', () => {
    api.countSheet('s1', 'a1').subscribe((sheet) => expect(Object.keys(sheet.items[0])).not.toContain('system'));
    http.expectOne((r) => r.url === `${S}/stock-audits/a1/count-sheet`).flush(countSheet());
    api.saveCounts('s1', 'a1', { version: 3, lines: [{ auditLineId: 'l1', counted: 4 }] }).subscribe();
    const put = http.expectOne(`${S}/stock-audits/a1/counts`);
    expect(put.request.method).toBe('PUT');
    put.flush(countSheet());
  });

  it('starts, submits, reviews, recounts and posts an audit', () => {
    api.startStockAudit('s1', { clientKey: 'k', scope: 'ALL_AVAILABLE' }).subscribe();
    http.expectOne(`${S}/stock-audits`).flush(stockAudit());
    api.submitCount('s1', 'a1', { clientKey: 'k', version: 1 }).subscribe();
    http.expectOne(`${S}/stock-audits/a1/submit`).flush(stockAudit());
    api.varianceReview('s1', 'a1').subscribe();
    http.expectOne(`${S}/stock-audits/a1/variance`).flush(varianceReview());
    api.requestRecount('s1', 'a1', { version: 1, auditLineIds: ['l1'] }).subscribe();
    http.expectOne(`${S}/stock-audits/a1/recount`).flush(stockAudit());
    api.approveAndPostAudit('s1', 'a1', { clientKey: 'k', version: 1 }).subscribe();
    http.expectOne(`${S}/stock-audits/a1/approve-and-post`).flush(stockAudit());
  });

  it('runs a report with only the filters that are set, and exports without paging as a blob', () => {
    api.runReport('store-stock', { storeId: 's1', page: 0, size: 50 }).subscribe();
    const run = http.expectOne((r) => r.url === `${BASE}/reports/store-stock`);
    expect(run.request.params.get('storeId')).toBe('s1');
    expect(run.request.params.has('from')).toBe(false);
    run.flush(reportPage());
    api.exportReport('indent-register', { from: '2026-11-01', to: '2026-11-19', page: 3 }).subscribe();
    const csv = http.expectOne((r) => r.url === `${BASE}/reports/indent-register/export`);
    expect(csv.request.params.has('page')).toBe(false);
    expect(csv.request.params.get('from')).toBe('2026-11-01');
    expect(csv.request.responseType).toBe('blob');
    csv.flush(new Blob(['x']));
    api.exportStockLedger({ productId: 'p1', from: '2026-11-01', to: '2026-11-19' }).subscribe();
    http.expectOne((r) => r.url === `${BASE}/reports/stock-ledger/export`).flush(new Blob(['x']));
  });
});

describe('PharmacyApi, SAH-80 work package I', () => {
  it('searches products by text or barcode, quotes the cart and saves the bill', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    const api = TestBed.inject(PharmacyApi);
    const http = TestBed.inject(HttpTestingController);
    api.saleProducts('s1', { barcode: '890' }).subscribe();
    const search = http.expectOne((r) => r.url === `${S}/sale/products`);
    expect(search.request.params.get('barcode')).toBe('890');
    expect(search.request.params.has('q')).toBe(false);
    search.flush({ items: [] });
    api.quoteSale('s1', { buyer: { kind: 'NONE' }, lines: [{ productId: 'p', qty: 1 }], discountPercent: null }).subscribe();
    http.expectOne(`${S}/sale/quote`).flush({});
    api.createBill('s1', { clientKey: 'k', quoteVersion: 'q', expectedNetPaise: 100, payments: [{ mode: 'CASH', amountPaise: 100 }], buyer: { kind: 'NONE' }, lines: [{ productId: 'p', qty: 1 }], discountPercent: null }).subscribe();
    expect(http.expectOne(`${S}/bills`).request.method).toBe('POST');
    http.verify();
  });
});

describe('PharmacyContextStore, work packages G and H', () => {
  let http: HttpTestingController;

  function start(context = storeContext()): PharmacyContextStore {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    const store = TestBed.inject(PharmacyContextStore);
    store.start();
    http.expectOne(`${BASE}/context`).flush(context);
    return store;
  }

  it('reads the store capabilities as the server lists them, and the unit capabilities for patient-wise and reports', () => {
    const store = start(storeContext({ reviewCount: true, dispatchTransfer: true }, ['INVENTORY', 'CONSUMPTION_PATIENT', 'STOCK_REPORTS']));
    expect(store.canConsume()).toBe(true);
    expect(store.canReturnDamage()).toBe(true);
    expect(store.canCount()).toBe(true);
    expect(store.canReviewCount()).toBe(true);
    expect(store.canDispatchTransfer()).toBe(true);
    expect(store.canConsumePatient()).toBe(true);
    expect(store.canReports()).toBe(true);
  });

  it('treats a capability the server does not list as not allowed', () => {
    const store = start(storeContext({ consume: false, count: false, returnDamage: false }));
    expect(store.canConsume()).toBe(false);
    expect(store.canCount()).toBe(false);
    expect(store.canReturnDamage()).toBe(false);
    expect(store.canConsumePatient()).toBe(false);
    expect(store.canReports()).toBe(false);
  });
});
