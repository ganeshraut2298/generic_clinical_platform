import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../core/http/problem-detail.interceptor';
import { ApiError } from '../../core/http/problem-detail';
import {
  OWNER_CONTEXT,
  SETTINGS,
  mapping,
  openingImport,
  openingLine,
  productDetail,
  productRow,
  store,
} from './pharmacy.fixtures';
import { PharmacyApi } from './pharmacy.api';

const BASE = '/api/v1/pharmacy';

describe('PharmacyApi', () => {
  let api: PharmacyApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    api = TestBed.inject(PharmacyApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the context and the setup summary', () => {
    api.context().subscribe((c) => expect(c.unit.code).toBe('TCB'));
    http.expectOne(`${BASE}/context`).flush(OWNER_CONTEXT);
    api.setupSummary().subscribe((s) => expect(s.storeCount).toBe(7));
    const req = http.expectOne(`${BASE}/setup-summary`);
    expect(req.request.method).toBe('GET');
    req.flush({ storeCount: 7, mappedStaffCount: 24, activeVendorCount: 12 });
  });

  it('puts the working store as a body, never a path or query value', () => {
    api.setWorkingStore('s1').subscribe();
    const req = http.expectOne(`${BASE}/context/working-store`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ storeId: 's1' });
    req.flush(OWNER_CONTEXT);
  });

  it('reads and saves unit settings with only the five contract fields', () => {
    api.unitSettings().subscribe();
    http.expectOne(`${BASE}/unit-settings`).flush(SETTINGS);
    const input = {
      version: 3,
      gstin: null,
      drugLicenceNo: null,
      drugLicenceValidTill: null,
      integrationMode: 'STANDALONE' as const,
    };
    api.saveUnitSettings(input).subscribe();
    const req = http.expectOne(`${BASE}/unit-settings`);
    expect(req.request.method).toBe('PUT');
    expect(Object.keys(req.request.body as object).sort()).toEqual(
      ['drugLicenceNo', 'drugLicenceValidTill', 'gstin', 'integrationMode', 'version'].sort(),
    );
    req.flush(SETTINGS);
  });

  it('lists stores, with includeInactive only when asked', () => {
    api.stores().subscribe();
    const plain = http.expectOne(`${BASE}/stores`);
    expect(plain.request.params.has('includeInactive')).toBe(false);
    plain.flush({ items: [], centralStoreId: null });
    api.stores(true).subscribe();
    const all = http.expectOne((r) => r.url === `${BASE}/stores`);
    expect(all.request.params.get('includeInactive')).toBe('true');
    all.flush({ items: [], centralStoreId: null });
  });

  it('creates, changes, reads and switches off a store', () => {
    api.createStore({ name: 'Test Central Store', code: 'CEN', type: 'CENTRAL' }).subscribe();
    const create = http.expectOne(`${BASE}/stores`);
    expect(create.request.method).toBe('POST');
    create.flush(store());

    api.store('s1').subscribe();
    http.expectOne(`${BASE}/stores/s1`).flush(store());

    api
      .updateStore('s1', {
        version: 2,
        name: 'A',
        code: 'A',
        type: 'PHARMACY',
        saleAllowed: true,
        consumptionAllowed: false,
        hodMembershipId: null,
        delegate: null,
      })
      .subscribe();
    const put = http.expectOne(`${BASE}/stores/s1`);
    expect(put.request.method).toBe('PUT');
    put.flush(store());

    api.switchOffStore('s1').subscribe();
    const off = http.expectOne(`${BASE}/stores/s1/switch-off`);
    expect(off.request.method).toBe('POST');
    expect(off.request.body).toBeNull();
    off.flush(store());
  });

  it('reads candidates with the store and an optional store role, and unwraps items', () => {
    let count = 0;
    api.staffCandidates('s1', 'APPROVER').subscribe((list) => (count = list.length));
    const req = http.expectOne((r) => r.url === `${BASE}/staff-candidates`);
    expect(req.request.params.get('storeId')).toBe('s1');
    expect(req.request.params.get('storeRole')).toBe('APPROVER');
    req.flush({ items: [{ membershipId: 'm1' }] });
    expect(count).toBe(1);

    api.staffCandidates('s1').subscribe();
    const bare = http.expectOne((r) => r.url === `${BASE}/staff-candidates`);
    expect(bare.request.params.has('storeRole')).toBe(false);
    bare.flush({ items: [] });
  });

  it('leaves storeId out when there is no store yet, and never sends it empty (F-11)', () => {
    api.staffCandidates(null, 'APPROVER').subscribe();
    const req = http.expectOne((r) => r.url === `${BASE}/staff-candidates`);
    expect(req.request.params.has('storeId')).toBe(false);
    expect(req.request.params.get('storeRole')).toBe('APPROVER');
    req.flush({ items: [] });
  });

  it('lists, maps, changes and removes store staff', () => {
    let names: string[] = [];
    api.storeStaff('s1').subscribe((list) => (names = list.map((m) => m.name)));
    http.expectOne(`${BASE}/stores/s1/staff`).flush({ items: [mapping()] });
    expect(names).toEqual(['Test Doctor One']);

    api.mapStaff('s1', { membershipId: 'm1', storeRole: 'STORE_USER' }).subscribe();
    const post = http.expectOne(`${BASE}/stores/s1/staff`);
    expect(post.request.method).toBe('POST');
    post.flush(mapping());

    api.changeStaffMapping('s1', 'c1', { version: 2, storeRole: 'STORE_USER' }).subscribe();
    const put = http.expectOne(`${BASE}/stores/s1/staff/c1`);
    expect(put.request.method).toBe('PUT');
    put.flush(mapping());

    api.removeStaffMapping('s1', 'c1').subscribe();
    const remove = http.expectOne(`${BASE}/stores/s1/staff/c1/remove`);
    expect(remove.request.method).toBe('POST');
    remove.flush(mapping());
  });

  it('turns a problem body into an ApiError with the field errors', () => {
    let caught: unknown;
    api.createStore({ name: '', code: 'A', type: 'PHARMACY' }).subscribe({ error: (e) => (caught = e) });
    http.expectOne(`${BASE}/stores`).flush(
      {
        type: 'https://sahaj.in/problems/validation',
        title: 'Check the form',
        status: 400,
        fieldErrors: [{ field: 'name', message: 'Enter the store name' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    expect(caught).toBeInstanceOf(ApiError);
    expect((caught as ApiError).fieldErrors[0].message).toBe('Enter the store name');
  });

  // ---- work package B (78-5, 78-6, 78-7)

  it('searches products with q, page and size, and leaves out what is not set', () => {
    api.searchProducts({ q: 'ceftri', page: 1, size: 25 }).subscribe();
    const req = http.expectOne((r) => r.url === `${BASE}/products`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('q')).toBe('ceftri');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('25');
    req.flush({ items: [productRow()], total: 1, page: 1, size: 25 });
    api.searchProducts().subscribe();
    const bare = http.expectOne((r) => r.url === `${BASE}/products`);
    expect(bare.request.params.keys()).toEqual([]);
    bare.flush({ items: [], total: 0, page: 0, size: 25 });
  });

  it('reads one product, adopts a catalogue product with no body and saves local settings with PUT', () => {
    api.product('p1').subscribe();
    http.expectOne(`${BASE}/products/p1`).flush(productDetail());

    api.adoptProduct('c1').subscribe();
    const adopt = http.expectOne(`${BASE}/catalogue-products/c1/adopt`);
    expect(adopt.request.method).toBe('POST');
    expect(adopt.request.body).toBeNull();
    adopt.flush(productDetail());

    const input = {
      version: 2,
      storeSettings: [],
      preferredVendorId: null,
      alias: null,
      barcode: null,
      returnable: true,
      active: true,
    };
    api.saveLocalSettings('p1', input).subscribe();
    const put = http.expectOne(`${BASE}/products/p1/local-settings`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual(input);
    put.flush(productDetail());
  });

  it('creates and changes an own product', () => {
    const body = {
      name: 'n',
      salt: 's',
      strength: '1 g',
      form: 'kit',
      purchaseUnit: 'Kit',
      issueUnit: 'kit',
      conversion: 1,
      manufacturer: null,
      madeInHouse: true,
      hsn: '3005',
      gstRate: '12',
      schedule: 'NONE' as const,
    };
    api.createOwnProduct({ clientKey: 'k', ...body }).subscribe();
    const post = http.expectOne(`${BASE}/products`);
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toMatchObject({ clientKey: 'k', conversion: 1 });
    post.flush(productDetail());
    api.updateOwnProduct('p1', { version: 2, ...body }).subscribe();
    const put = http.expectOne(`${BASE}/products/p1`);
    expect(put.request.method).toBe('PUT');
    put.flush(productDetail());
  });

  it('checks duplicates with salt, strength and form (and the product to leave out) and unwraps candidates', () => {
    let count = -1;
    api.findDuplicates('Ceftriaxone', '1 g', 'injection', 'p1').subscribe((c) => (count = c.length));
    const req = http.expectOne((r) => r.url === `${BASE}/products/duplicates`);
    expect(req.request.params.get('salt')).toBe('Ceftriaxone');
    expect(req.request.params.get('strength')).toBe('1 g');
    expect(req.request.params.get('form')).toBe('injection');
    expect(req.request.params.get('excludeProductId')).toBe('p1');
    req.flush({ candidates: [] });
    expect(count).toBe(0);
    api.findDuplicates('s', 't', 'f').subscribe();
    const bare = http.expectOne((r) => r.url === `${BASE}/products/duplicates`);
    expect(bare.request.params.has('excludeProductId')).toBe(false);
    bare.flush({ candidates: [] });
    api.productOptions().subscribe();
    http.expectOne(`${BASE}/product-options`).flush({ units: [], forms: [] });
  });

  it('reads the CSV template as text', () => {
    let text = '';
    api.openingStockTemplate().subscribe((t) => (text = t));
    const req = http.expectOne(`${BASE}/opening-stock/template`);
    expect(req.request.responseType).toBe('text');
    req.flush('store code,product code\n');
    expect(text).toBe('store code,product code\n');
  });

  it('lists the imports of every store with a status filter, and of one store', () => {
    api.allOpeningStockImports('WAITING_APPROVAL', 1, 20).subscribe();
    const all = http.expectOne((r) => r.url === `${BASE}/opening-stock-imports`);
    expect(all.request.params.get('status')).toBe('WAITING_APPROVAL');
    expect(all.request.params.get('page')).toBe('1');
    all.flush({ items: [], total: 0, page: 1, size: 20 });
    api.allOpeningStockImports().subscribe();
    const none = http.expectOne((r) => r.url === `${BASE}/opening-stock-imports`);
    expect(none.request.params.has('status')).toBe(false);
    none.flush({ items: [], total: 0, page: 0, size: 20 });
    api.openingStockImports('s1').subscribe();
    http
      .expectOne((r) => r.url === `${BASE}/stores/s1/opening-stock-imports`)
      .flush({ items: [openingImport()], total: 1, page: 0, size: 20 });
  });

  it('runs an import: start, lines in a body, submit, approve and reject with the paths of the contract', () => {
    const base = `${BASE}/stores/s1/opening-stock-imports`;
    api.startOpeningStockImport('s1', { clientKey: 'k', columns: ['a'], rowCount: 1 }).subscribe();
    const start = http.expectOne(base);
    expect(start.request.method).toBe('POST');
    expect(start.request.body).toEqual({ clientKey: 'k', columns: ['a'], rowCount: 1 });
    start.flush(openingImport());

    api.openingStockImport('s1', 'i1').subscribe();
    http.expectOne(`${base}/i1`).flush(openingImport());

    const row = {
      rowNo: 1,
      storeCode: 'CEN',
      productCode: 'G',
      batch: 'B',
      expiryDate: '31-12-2027',
      mrp: '1',
      purchaseRate: '1',
      quantity: '1',
    };
    api.addOpeningStockLines('s1', 'i1', { rows: [row] }).subscribe();
    const lines = http.expectOne(`${base}/i1/lines`);
    expect(lines.request.method).toBe('POST');
    expect(lines.request.body).toEqual({ rows: [row] });
    lines.flush({ receivedRows: 1, rowCount: 1, issues: [] });

    api.submitOpeningStockImport('s1', 'i1').subscribe();
    const submit = http.expectOne(`${base}/i1/submit`);
    expect(submit.request.method).toBe('POST');
    expect(submit.request.body).toBeNull();
    submit.flush(openingImport());

    api.approveOpeningStockImport('s1', 'i1').subscribe();
    const approve = http.expectOne(`${base}/i1/approve`);
    expect(approve.request.body).toBeNull();
    approve.flush(openingImport({ status: 'APPROVED' }));

    api.rejectOpeningStockImport('s1', 'i1', { reasonKey: 'OTHER', note: 'Counts are old' }).subscribe();
    const reject = http.expectOne(`${base}/i1/reject`);
    expect(reject.request.body).toEqual({ reasonKey: 'OTHER', note: 'Counts are old' });
    reject.flush(openingImport({ status: 'REJECTED' }));
  });

  it('reads the lines of an import, only the ones with problems when asked', () => {
    api.openingStockLines('s1', 'i1', { withIssues: true, page: 2, size: 50 }).subscribe();
    const req = http.expectOne((r) => r.url === `${BASE}/stores/s1/opening-stock-imports/i1/lines`);
    expect(req.request.params.get('withIssues')).toBe('true');
    expect(req.request.params.get('page')).toBe('2');
    req.flush({ items: [openingLine()], total: 1, page: 2, size: 50 });
    api.openingStockLines('s1', 'i1').subscribe();
    const bare = http.expectOne((r) => r.url === `${BASE}/stores/s1/opening-stock-imports/i1/lines`);
    expect(bare.request.params.has('withIssues')).toBe(false);
    bare.flush({ items: [], total: 0, page: 0, size: 50 });
  });
});
