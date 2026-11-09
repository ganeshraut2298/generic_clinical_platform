import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AdmissionsApi } from '../admissions.api';
import { CardSetupApi } from './card-setup.api';
import { CardSetupView, CardSetupWrite } from './card-setup.model';
import { ownerView, standardSetup } from './card-setup.fixtures';

const BASE = '/api/v1/ipd/card-setup';

describe('CardSetupApi', () => {
  let api: CardSetupApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(CardSetupApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('reads the card setup', () => {
    let got: unknown;
    api.get().subscribe((v) => (got = v));
    const req = http.expectOne(BASE);
    expect(req.request.method).toBe('GET');
    req.flush(ownerView());
    expect(got).toEqual(ownerView());
  });

  it('saves the draft with both concurrency tokens and the whole setup', () => {
    const body = { baseVersion: 1, draftRevision: null, setup: standardSetup() };
    api.saveDraft(body).subscribe();
    const req = http.expectOne(`${BASE}/draft`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);
    req.flush(ownerView());
  });

  it('discards the draft with the revision as a query parameter', () => {
    api.discardDraft(3).subscribe();
    const req = http.expectOne(`${BASE}/draft?draftRevision=3`);
    expect(req.request.method).toBe('DELETE');
    req.flush(ownerView());
  });

  it('keeps a large opaque draft revision exact from the answer to the written body', () => {
    const big = 1790000000000001;
    const draft = `{"revision":${big},"baseVersion":1,"setup":${JSON.stringify(standardSetup())},"savedAt":"2026-11-09T05:35:00Z","savedByName":"Dr. Owner","changedParts":["layout"]}`;
    const raw = JSON.stringify(ownerView()).replace('"draft":null', `"draft":${draft}`);
    expect(raw).toContain(`"revision":${big}`);
    const parsed = JSON.parse(raw) as CardSetupView;
    expect(Number.isSafeInteger(big)).toBe(true);
    expect(parsed.draft?.revision).toBe(big);

    const body: CardSetupWrite = {
      baseVersion: parsed.live.version,
      draftRevision: parsed.draft?.revision ?? null,
      setup: standardSetup(),
    };
    api.saveDraft(body).subscribe();
    const put = http.expectOne(`${BASE}/draft`);
    expect(put.request.body.draftRevision).toBe(big);
    expect(JSON.stringify(put.request.body)).toContain(`"draftRevision":${big}`);
    expect(JSON.parse(JSON.stringify(put.request.body)).draftRevision).toBe(big);
    put.flush(parsed);

    api.discardDraft(big).subscribe();
    http.expectOne(`${BASE}/draft?draftRevision=1790000000000001`).flush(ownerView());
  });

  it('publishes the editor setup with the tokens', () => {
    const body = { baseVersion: 2, draftRevision: 5, setup: standardSetup() };
    api.publish(body).subscribe();
    const req = http.expectOne(`${BASE}/publish`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush(ownerView());
  });
});

describe('AdmissionsApi.list (SAH-37)', () => {
  let api: AdmissionsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(AdmissionsApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('sends no cardFacts for the Admitted page', () => {
    api.list().subscribe();
    const req = http.expectOne((r) => r.url === '/api/v1/ipd/admissions');
    expect(req.request.params.get('status')).toBe('ADMITTED');
    expect(req.request.params.has('cardFacts')).toBe(false);
    req.flush({});
  });

  it('asks for every card fact only for the owner preview', () => {
    api.list({ cardFacts: 'all' }).subscribe();
    const req = http.expectOne((r) => r.url === '/api/v1/ipd/admissions');
    expect(req.request.params.get('cardFacts')).toBe('all');
    req.flush({});
  });
});
