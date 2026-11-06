import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SafetyApi } from './safety.api';

const IPD = '/api/v1/ipd';

describe('SafetyApi', () => {
  let api: SafetyApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(SafetyApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the board and the counts-only summary', () => {
    api.board().subscribe();
    const board = http.expectOne(`${IPD}/safety`);
    expect(board.request.method).toBe('GET');
    board.flush({});
    api.summary().subscribe();
    const summary = http.expectOne(`${IPD}/safety/summary`);
    expect(summary.request.method).toBe('GET');
    summary.flush({ open: 1, pastTarget: 0, changeToken: 'AAAAAAAAAAAAAAAAAAAAAA', asOf: '2026-11-05T05:47:00Z' });
  });

  it('acknowledges with the version the doctor saw', () => {
    api.acknowledge('e1', 3).subscribe();
    const req = http.expectOne(`${IPD}/escalations/e1/acknowledge`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ version: 3 });
    req.flush({});
  });

  it('responds with version, action and plan', () => {
    api.respond('e1', { version: 3, action: 'NO_CHANGE', note: 'Repeat vitals in 30 minutes' }).subscribe();
    const req = http.expectOne(`${IPD}/escalations/e1/respond`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ version: 3, action: 'NO_CHANGE', note: 'Repeat vitals in 30 minutes' });
    req.flush({});
  });

  it('calls the senior with the version and the expected count of calls', () => {
    api.callSenior('e1', { version: 3, seniorCalls: 1 }).subscribe();
    const req = http.expectOne(`${IPD}/escalations/e1/call-senior`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ version: 3, seniorCalls: 1 });
    req.flush({});
  });

  it('reads clinical events with the limit, and the cursor only when there is one', () => {
    api.events(14).subscribe();
    let req = http.expectOne((r) => r.url === `${IPD}/clinical-events`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('limit')).toBe('14');
    expect(req.request.params.has('cursor')).toBe(false);
    req.flush({});

    api.events(50, 'abc_-123').subscribe();
    req = http.expectOne((r) => r.url === `${IPD}/clinical-events`);
    expect(req.request.params.get('limit')).toBe('50');
    expect(req.request.params.get('cursor')).toBe('abc_-123');
    req.flush({});
  });
});
