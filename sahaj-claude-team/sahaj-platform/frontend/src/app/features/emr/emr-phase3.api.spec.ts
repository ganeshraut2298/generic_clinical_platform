import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { EmrApi } from './emr.api';
import { EmrFormApi } from './emr-form.api';
import { footDefinition, settings } from './forms/form.fixtures';

const EMR = '/api/v1/opd/emr';

/** The phase 3 calls use exactly the paths, methods, query parameters and bodies of docs/api/opd.yaml 0.4.0. */
describe('EmrApi, phase 3 (ADR-022)', () => {
  let api: EmrApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(EmrApi);
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

  it('reads the allergy classes and the clinic favourites', () => {
    api.listAllergyClasses().subscribe();
    expectCall('GET', `${EMR}/allergy-classes`);
    api.listMedicineFavourites().subscribe();
    expectCall('GET', `${EMR}/medicine-favourites`);
  });

  it('searches the EMR medicines with the text and, past the first page, the page', () => {
    api.searchEmrMedicines('tel').subscribe();
    const first = http.expectOne((r) => r.url === `${EMR}/medicines`);
    expect(first.request.params.get('q')).toBe('tel');
    expect(first.request.params.has('page')).toBe(false);
    first.flush({});
    api.searchEmrMedicines('tel', 2).subscribe();
    const second = http.expectOne((r) => r.url === `${EMR}/medicines`);
    expect(second.request.params.get('page')).toBe('2');
    second.flush({});
  });

  it('copies items with POST and the source in the body', () => {
    api.copyItems('r1', { source: 'LAST_SIGNED_PRESCRIPTION', what: 'MEDICINES' }).subscribe();
    expectCall('POST', `${EMR}/records/r1/copy-items`, { source: 'LAST_SIGNED_PRESCRIPTION', what: 'MEDICINES' });
    api.copyItems('r1', { source: 'PAST_RECORD', sourceRecordId: 'p9', what: 'TESTS' }).subscribe();
    expectCall('POST', `${EMR}/records/r1/copy-items`, { source: 'PAST_RECORD', sourceRecordId: 'p9', what: 'TESTS' });
  });

  it('saves the print choice of this visit with PUT and reads the case paper context', () => {
    api.setPrintPreferences('r1', { language: 'MR', paperSize: 'A5' }).subscribe();
    expectCall('PUT', `${EMR}/records/r1/print-preferences`, { language: 'MR', paperSize: 'A5' });
    api.getCasePaperContext('r1').subscribe();
    expectCall('GET', `${EMR}/records/r1/case-paper`);
  });

  it('reads the history with the type, the search text and the page', () => {
    api.listPastVisits('r1', 'OPD', '  hba1c ', 1).subscribe();
    const req = http.expectOne((r) => r.url === `${EMR}/records/r1/past-visits`);
    expect(req.request.params.get('type')).toBe('OPD');
    expect(req.request.params.get('q')).toBe('hba1c');
    expect(req.request.params.get('page')).toBe('1');
    req.flush({});
    api.listPastVisits('r1', 'ALL', '', 0).subscribe();
    const bare = http.expectOne((r) => r.url === `${EMR}/records/r1/past-visits`);
    expect(bare.request.params.has('q')).toBe(false);
    bare.flush({});
  });

  it('reads one past visit, the signed record behind it and the key facts', () => {
    api.getPastVisitDetail('r1', 'IPD', 'a1').subscribe();
    expectCall('GET', `${EMR}/records/r1/past-visits/IPD/a1`);
    api.getPastRecord('r1', 'p9').subscribe();
    expectCall('GET', `${EMR}/records/r1/past-records/p9`);
    api.getKeyFacts('r1').subscribe();
    expectCall('GET', `${EMR}/records/r1/key-facts`);
  });
});

describe('EmrFormApi (SAH-59)', () => {
  let api: EmrFormApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(EmrFormApi);
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

  it('reads the starters and the clinic forms', () => {
    api.listStarters().subscribe();
    expectCall('GET', `${EMR}/form-starters`);
    api.getStarter('gcs').subscribe();
    expectCall('GET', `${EMR}/form-starters/gcs`);
    api.list().subscribe();
    expectCall('GET', `${EMR}/forms`);
    api.get('f_abcdefghij').subscribe();
    expectCall('GET', `${EMR}/forms/f_abcdefghij`);
  });

  it('starts a form from a starter with the Idempotency-Key of the tap', () => {
    api.create('untitled', 'Wound review', 'key-12345678').subscribe();
    const req = expectCall('POST', `${EMR}/forms`, { starterKey: 'untitled', name: 'Wound review' });
    expect(req.request.headers.get('Idempotency-Key')).toBe('key-12345678');
  });

  it('saves the draft with PUT and the revision', () => {
    const def = footDefinition();
    api.saveDraft('f_abcdefghij', { revision: 4, name: def.name, settings: settings(), fields: def.fields }).subscribe();
    const req = http.expectOne(`${EMR}/forms/f_abcdefghij/draft`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.revision).toBe(4);
    expect(req.request.body.fields).toHaveLength(def.fields.length);
    req.flush({});
  });

  it('sends the revision with submit, approve, publish and retire, and a reason with send back', () => {
    api.submit('f_abcdefghij', 4).subscribe();
    expectCall('POST', `${EMR}/forms/f_abcdefghij/submit`, { revision: 4 });
    api.approve('f_abcdefghij', 5).subscribe();
    expectCall('POST', `${EMR}/forms/f_abcdefghij/approve`, { revision: 5 });
    api.publish('f_abcdefghij', 6).subscribe();
    expectCall('POST', `${EMR}/forms/f_abcdefghij/publish`, { revision: 6 });
    api.retire('f_abcdefghij', 7).subscribe();
    expectCall('POST', `${EMR}/forms/f_abcdefghij/retire`, { revision: 7 });
    api.sendBack('f_abcdefghij', 5, 'Add a Marathi label').subscribe();
    expectCall('POST', `${EMR}/forms/f_abcdefghij/send-back`, { revision: 5, reason: 'Add a Marathi label' });
  });
});
