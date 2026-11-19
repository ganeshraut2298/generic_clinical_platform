import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../core/http/problem-detail.interceptor';
import { ACTIVE_VENDOR, ELEVEN_LISTS, TEN_SERIES, THREE_TEMPLATES, approvalRules } from './pharmacy-c.fixtures';
import { PharmacyApi } from './pharmacy.api';

const BASE = '/api/v1/pharmacy';

describe('PharmacyApi, work package C (vendors, approval rules, numbering, reasons, print templates)', () => {
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

  it('lists vendors with a status filter, a page and a size, and leaves out what is not set', () => {
    api.vendors().subscribe();
    const plain = http.expectOne(`${BASE}/vendors`);
    expect(plain.request.params.keys()).toEqual([]);
    plain.flush({ items: [], total: 0, page: 0, size: 25 });

    api.vendors({ q: 'test', status: 'ALL', page: 2, size: 25 }).subscribe();
    const filtered = http.expectOne((r) => r.url === `${BASE}/vendors`);
    expect(filtered.request.params.get('q')).toBe('test');
    expect(filtered.request.params.get('status')).toBe('ALL');
    expect(filtered.request.params.get('page')).toBe('2');
    filtered.flush({ items: [ACTIVE_VENDOR], total: 1, page: 2, size: 25 });
  });

  it('creates, changes and switches off a vendor', () => {
    const body = {
      clientKey: 'k',
      name: 'Test',
      gstin: null,
      licenceRequired: false,
      licenceNo: null,
      licenceValidTill: null,
      contactName: null,
      contactPhone: null,
      creditDays: 0,
      returnDaysBeforeExpiry: null,
      returnCreditPercent: null,
      suppliesToStoreIds: [],
    };
    api.createVendor(body).subscribe();
    const create = http.expectOne(`${BASE}/vendors`);
    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual(body);
    create.flush(ACTIVE_VENDOR);

    const rest: Partial<typeof body> = { ...body };
    delete rest.clientKey;
    api.updateVendor('v1', { version: 1, ...(rest as Omit<typeof body, 'clientKey'>) }).subscribe();
    const update = http.expectOne(`${BASE}/vendors/v1`);
    expect(update.request.method).toBe('PUT');
    expect(update.request.body.version).toBe(1);
    update.flush(ACTIVE_VENDOR);

    api.switchOffVendor('v1').subscribe();
    const off = http.expectOne(`${BASE}/vendors/v1/switch-off`);
    expect(off.request.method).toBe('POST');
    expect(off.request.body).toBeNull();
    off.flush(ACTIVE_VENDOR);

    api.switchOnVendor('v1').subscribe();
    const on = http.expectOne(`${BASE}/vendors/v1/switch-on`);
    expect(on.request.method).toBe('POST');
    expect(on.request.body).toBeNull();
    on.flush(ACTIVE_VENDOR);
  });

  it('reads and saves the approval rules and applies a preset', () => {
    api.approvalRules().subscribe((r) => expect(r.rules).toHaveLength(9));
    http.expectOne(`${BASE}/approval-rules`).flush(approvalRules());

    api.saveApprovalRules({ baseVersionNo: 2, highValueThresholdPaise: null, rules: approvalRules().rules }).subscribe();
    const save = http.expectOne(`${BASE}/approval-rules`);
    expect(save.request.method).toBe('PUT');
    save.flush(approvalRules());

    api.applyApprovalPreset({ baseVersionNo: 2, preset: 'LEAN', limits: [] }).subscribe();
    const preset = http.expectOne(`${BASE}/approval-rules/preset`);
    expect(preset.request.method).toBe('POST');
    preset.flush(approvalRules());
  });

  it('reads the series the server returns and changes one by document type', () => {
    api.documentSeries().subscribe((items) => expect(items).toHaveLength(10));
    http.expectOne(`${BASE}/document-series`).flush({ items: TEN_SERIES });

    api
      .saveDocumentSeries('BILL', {
        version: 0,
        prefix: 'SB/',
        financialYearPart: true,
        sequenceLength: 6,
        restartsEachFinancialYear: true,
      })
      .subscribe();
    const put = http.expectOne(`${BASE}/document-series/BILL`);
    expect(put.request.method).toBe('PUT');
    put.flush(TEN_SERIES[4]);
  });

  it('reads the reason lists and adds, switches off and switches on a reason', () => {
    api.reasonLists().subscribe((r) => expect(r.lists).toHaveLength(11));
    http.expectOne(`${BASE}/reason-lists`).flush(ELEVEN_LISTS);

    api.addReason('damage', { label: 'Packaging torn' }).subscribe();
    const add = http.expectOne(`${BASE}/reason-lists/damage/reasons`);
    expect(add.request.method).toBe('POST');
    expect(add.request.body).toEqual({ label: 'Packaging torn' });
    add.flush(ELEVEN_LISTS);

    api.switchOffReason('damage', 'damage.2').subscribe();
    const off = http.expectOne(`${BASE}/reason-lists/damage/reasons/damage.2/switch-off`);
    expect(off.request.body).toBeNull();
    off.flush(ELEVEN_LISTS);

    api.switchOnReason('damage', 'damage.2').subscribe();
    http.expectOne(`${BASE}/reason-lists/damage/reasons/damage.2/switch-on`).flush(ELEVEN_LISTS);
  });

  it('reads the print templates and saves one by document type', () => {
    api.printTemplates().subscribe((items) => expect(items).toHaveLength(3));
    http.expectOne(`${BASE}/print-templates`).flush({ items: THREE_TEMPLATES });

    api.savePrintTemplate('INVOICE', { version: 0, headerLines: ['A'], footerNote: null, paperSize: 'A5' }).subscribe();
    const put = http.expectOne(`${BASE}/print-templates/INVOICE`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ version: 0, headerLines: ['A'], footerNote: null, paperSize: 'A5' });
    put.flush(THREE_TEMPLATES[0]);
  });
});
