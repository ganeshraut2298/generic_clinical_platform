import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SpecialtyApi } from './specialty.api';
import { REFERENCE_LISTS } from './specialty.context';
import { REFERENCE_LIST_NAMES } from './specialty.model';
import { SPECIALTY_EDITORS, editorLoader } from './specialty-registry';

const EMR = '/api/v1/opd/emr';
const FP = 'a'.repeat(64);

/** The phase 4 calls use exactly the paths, methods, headers and bodies of docs/api/opd.yaml 0.5.1 (ADR-023). */
describe('SpecialtyApi (opd.yaml 0.5.1)', () => {
  let api: SpecialtyApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(SpecialtyApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function expectCall(method: string, url: string, body?: unknown) {
    const req = http.expectOne(url);
    expect(req.request.method).toBe(method);
    if (body !== undefined) {
      expect(req.request.body).toEqual(body);
    }
    req.flush({});
    return req;
  }

  it('reads the reference of a profile and the history of a record', () => {
    api.getReference('onco').subscribe();
    expectCall('GET', `${EMR}/specialty-reference/onco`);
    api.getHistory('r1').subscribe();
    expectCall('GET', `${EMR}/records/r1/specialty-history`);
  });

  it('books the surgery with the record revision and the Idempotency-Key', () => {
    api.bookSurgery('r1', 7, 'key-12345678').subscribe();
    const req = expectCall('POST', `${EMR}/records/r1/surgery-booking`, { revision: 7 });
    expect(req.request.headers.get('Idempotency-Key')).toBe('key-12345678');
  });

  it('reads, saves, names the checker for, confirms by PIN, sends and holds the chemotherapy order', () => {
    api.getChemoOrder('r1').subscribe();
    expectCall('GET', `${EMR}/records/r1/chemo-order`);
    api.saveChemoOrder('r1', { revision: 0, protocolKey: 'AC', levelPct: 100 }).subscribe();
    expectCall('PUT', `${EMR}/records/r1/chemo-order`, { revision: 0, protocolKey: 'AC', levelPct: 100 });
    api.listChemoCheckerOptions('r1').subscribe();
    expectCall('GET', `${EMR}/records/r1/chemo-order/checker-options`);
    api.nameChemoChecker('r1', { revision: 1, checkerMembershipId: 'm9' }).subscribe();
    expectCall('PUT', `${EMR}/records/r1/chemo-order/checker`, { revision: 1, checkerMembershipId: 'm9' });
    api.nameChemoChecker('r1', { revision: 1, checkerMembershipId: null }).subscribe();
    expectCall('PUT', `${EMR}/records/r1/chemo-order/checker`, { revision: 1, checkerMembershipId: null });
    api.confirmChemoSecondCheckByPin('r1', { orderRevision: 2, fingerprint: FP, checkerMembershipId: 'm9', pin: '1234' }).subscribe();
    expectCall('POST', `${EMR}/records/r1/chemo-order/second-check`, { orderRevision: 2, fingerprint: FP, checkerMembershipId: 'm9', pin: '1234' });
    api.sendChemoOrder('r1', { revision: 2, fingerprint: FP }).subscribe();
    expectCall('POST', `${EMR}/records/r1/chemo-order/send`, { revision: 2, fingerprint: FP });
    api.recordChemoHold('r1', { revision: 2 }).subscribe();
    expectCall('POST', `${EMR}/records/r1/chemo-order/hold`, { revision: 2 });
  });

  it('lists and confirms the orders waiting for the signed-in checker', () => {
    api.listMyChemoChecks().subscribe();
    const first = http.expectOne(`${EMR}/chemo-checks`);
    expect(first.request.params.has('page')).toBe(false);
    first.flush({});
    api.listMyChemoChecks(2).subscribe();
    const second = http.expectOne((r) => r.url === `${EMR}/chemo-checks`);
    expect(second.request.params.get('page')).toBe('2');
    second.flush({});
    api.getChemoCheck('o1').subscribe();
    expectCall('GET', `${EMR}/chemo-checks/o1`);
    api.confirmChemoCheck('o1', FP).subscribe();
    expectCall('POST', `${EMR}/chemo-checks/o1/confirm`, { fingerprint: FP });
  });

  it('lists and creates visit templates', () => {
    api.listVisitTemplates().subscribe();
    expectCall('GET', `${EMR}/visit-templates`);
    api.createVisitTemplate({ name: 'Viral fever', subLine: null, items: { advice: [{ itemKey: null, text: 'Rest', newToList: true }] } }).subscribe();
    expectCall('POST', `${EMR}/visit-templates`, { name: 'Viral fever', subLine: null, items: { advice: [{ itemKey: null, text: 'Rest', newToList: true }] } });
  });
});

describe('specialty registry (ADR-023 D1)', () => {
  it('has an editor for each of the 34 specialty components', () => {
    expect(Object.keys(SPECIALTY_EDITORS)).toHaveLength(34);
  });

  it('shows an unknown component with the generic renderer', () => {
    expect(editorLoader('SOMETHING_NEW')).toBeNull();
    expect(editorLoader(null)).toBeNull();
    expect(editorLoader('TOOTH_CHART')).toBeTypeOf('function');
  });

  it('loads every editor lazily and each is a component', async () => {
    for (const [component, loader] of Object.entries(SPECIALTY_EDITORS)) {
      const type = await loader();
      expect(type, component).toBeTypeOf('function');
    }
  });
});

describe('reference list names (opd.yaml 0.5.1, ADR-023 D13.1)', () => {
  it('are the 44 closed names of the contract, each used as its own key', () => {
    expect(REFERENCE_LIST_NAMES).toHaveLength(44);
    expect(new Set(REFERENCE_LIST_NAMES).size).toBe(44);
    expect(Object.keys(REFERENCE_LISTS)).toEqual([...REFERENCE_LIST_NAMES]);
    expect(Object.entries(REFERENCE_LISTS).every(([k, v]) => k === v)).toBe(true);
  });

  it('include the lists whose keys the client stores (table keys, enum values, property names)', () => {
    for (const name of ['iapGroups', 'iapVaccines', 'milestones', 'jointRom', 'jointMmt', 'jointSpecialTests', 'outcomeScales', 'oncTakeHome', 'oncReferrals', 'oncSystems', 'oncSystemChips', 'gmSystems']) {
      expect(REFERENCE_LIST_NAMES).toContain(name);
    }
  });
});
