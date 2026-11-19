import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../core/http/problem-detail';
import { problemDetailInterceptor } from '../../core/http/problem-detail.interceptor';
import { adjustment, approvalRequest, byStore, inboxPage, inboxRow, overview, postingPreview, productStock } from './inventory/inventory.fixtures';
import { PharmacyApi } from './pharmacy.api';
import { openingImport } from './pharmacy.fixtures';

const BASE = '/api/v1/pharmacy';

describe('PharmacyApi, SAH-79 work package D', () => {
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

  it('reads the posting preview of an import, and posts it with one client key in the body', () => {
    api.previewOpeningStockPosting('s1', 'i1').subscribe((p) => expect(p.canPost).toBe(true));
    const preview = http.expectOne(`${BASE}/stores/s1/opening-stock-imports/i1/posting-preview`);
    expect(preview.request.method).toBe('GET');
    preview.flush(postingPreview());

    api.postOpeningStock('s1', 'i1', 'key-1').subscribe((i) => expect(i.posted).toBe(true));
    const post = http.expectOne(`${BASE}/stores/s1/opening-stock-imports/i1/post`);
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ clientKey: 'key-1' });
    post.flush(openingImport({ posted: true, postedAt: '2026-11-19T06:00:00Z' }));
  });

  it('asks the inbox for a type chip and a page, and leaves the type out for All', () => {
    api.approvalInbox({ type: 'ALL', page: 0, size: 20 }).subscribe();
    const all = http.expectOne((r) => r.url === `${BASE}/approvals/inbox`);
    expect(all.request.params.has('type')).toBe(false);
    expect(all.request.params.get('size')).toBe('20');
    all.flush(inboxPage([inboxRow()]));

    api.approvalInbox({ type: 'STOCK', page: 1 }).subscribe();
    const stock = http.expectOne((r) => r.url === `${BASE}/approvals/inbox`);
    expect(stock.request.params.get('type')).toBe('STOCK');
    expect(stock.request.params.get('page')).toBe('1');
    stock.flush(inboxPage([]));
  });

  it('reads one request and sends a decision', () => {
    api.approvalRequest('r1').subscribe();
    http.expectOne(`${BASE}/approvals/r1`).flush(approvalRequest());
    api
      .decideApproval('r1', { clientKey: 'k', level: 1, requestVersion: 4, decision: 'REJECTED', reasonKey: 'approval-reject.quantity-too-high' })
      .subscribe();
    const req = http.expectOne(`${BASE}/approvals/r1/decision`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.decision).toBe('REJECTED');
    req.flush(approvalRequest({ status: 'REJECTED' }));
  });

  it('reads stock by store: overview, batches with the view, one product, low stock', () => {
    api.stockOverview('s1').subscribe();
    http.expectOne(`${BASE}/stores/s1/stock/overview`).flush(overview());

    api.stockBatches('s1', { view: 'NEAR_EXPIRY', withinDays: 60, q: 'cef', page: 0, size: 25 }).subscribe();
    const near = http.expectOne((r) => r.url === `${BASE}/stores/s1/stock/batches`);
    expect(near.request.params.get('view')).toBe('NEAR_EXPIRY');
    expect(near.request.params.get('withinDays')).toBe('60');
    expect(near.request.params.get('q')).toBe('cef');
    near.flush({ items: [], total: 0, page: 0, size: 25 });

    api.stockBatches('s1', { view: 'EXPIRED' }).subscribe();
    const expired = http.expectOne((r) => r.url === `${BASE}/stores/s1/stock/batches`);
    expect(expired.request.params.has('withinDays')).toBe(false);
    expired.flush({ items: [], total: 0, page: 0, size: 25 });

    api.productStock('s1', 'p1').subscribe();
    http.expectOne(`${BASE}/stores/s1/stock/products/p1`).flush(productStock());

    api.lowStock('s1', { page: 1, size: 25 }).subscribe();
    const low = http.expectOne((r) => r.url === `${BASE}/stores/s1/stock/low-stock`);
    expect(low.request.params.get('page')).toBe('1');
    low.flush({ items: [], total: 0, page: 1, size: 25 });
  });

  it('reads a product across stores and in my store', () => {
    api.productStockAllStores('p1').subscribe();
    http.expectOne(`${BASE}/stock/products/p1/stores`).flush(byStore());
    api.productStockMyStore('s1', 'p1').subscribe();
    http.expectOne(`${BASE}/stores/s1/stock/products/p1/states`).flush(byStore());
  });

  it('lists, reads and raises stock adjustments under the store path', () => {
    api.stockAdjustments('s1', { status: 'POSTED', page: 0, size: 20 }).subscribe();
    const list = http.expectOne((r) => r.url === `${BASE}/stores/s1/stock-adjustments`);
    expect(list.request.params.get('status')).toBe('POSTED');
    list.flush({ items: [adjustment()], total: 1, page: 0, size: 20 });

    api.stockAdjustment('s1', 'a1').subscribe();
    http.expectOne(`${BASE}/stores/s1/stock-adjustments/a1`).flush(adjustment());

    api
      .createStockAdjustment('s1', {
        clientKey: 'k',
        batchId: 'b1',
        bookQtySeen: 40,
        physicalQty: 38,
        reasonKey: 'stock-adjustment.count-mismatch',
      })
      .subscribe();
    const create = http.expectOne(`${BASE}/stores/s1/stock-adjustments`);
    expect(create.request.method).toBe('POST');
    expect(create.request.body.physicalQty).toBe(38);
    create.flush(adjustment());
  });

  it('keeps a conflict problem with its type, so that the screen can read the sentence', () => {
    let caught: ApiError | undefined;
    api.postOpeningStock('s1', 'i1', 'k').subscribe({ error: (e: ApiError) => (caught = e) });
    http.expectOne(`${BASE}/stores/s1/opening-stock-imports/i1/post`).flush(
      {
        type: 'https://sahaj.in/problems/already-posted',
        title: 'Already posted',
        status: 409,
        detail: 'This import was already posted.',
        postedAt: '2026-11-19T06:00:00Z',
      },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    expect(caught?.status).toBe(409);
    expect(caught?.problem.detail).toBe('This import was already posted.');
  });

  it('reads the active reasons of the named lists as one comma list', () => {
    api.activeReasons(['approval-reject', 'stock-adjustment']).subscribe();
    const req = http.expectOne((r) => r.url === `${BASE}/reasons`);
    expect(req.request.params.get('lists')).toBe('approval-reject,stock-adjustment');
    req.flush({ lists: [] });
  });

  it('reads the stock ledger of one product by dates and store', () => {
    api.stockLedger({ productId: 'p1', storeId: 's1', from: '2026-10-20', to: '2026-11-19', page: 0, size: 50 }).subscribe();
    const req = http.expectOne((r) => r.url === `${BASE}/reports/stock-ledger`);
    expect(req.request.params.get('productId')).toBe('p1');
    expect(req.request.params.get('storeId')).toBe('s1');
    expect(req.request.params.get('from')).toBe('2026-10-20');
    req.flush({ items: [], total: 0, page: 0, size: 50, product: {}, stores: [], from: '2026-10-20', to: '2026-11-19' });

    api.stockLedger({ productId: 'p1', from: '2026-10-20', to: '2026-11-19' }).subscribe();
    const all = http.expectOne((r) => r.url === `${BASE}/reports/stock-ledger`);
    expect(all.request.params.has('storeId')).toBe(false);
    all.flush({ items: [], total: 0, page: 0, size: 50, product: {}, stores: [], from: '', to: '' });
  });
});
