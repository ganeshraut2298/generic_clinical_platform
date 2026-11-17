import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { EmrApi } from './emr.api';
import { sheetOf } from './emr.fixtures';

describe('EmrApi', () => {
  let api: EmrApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(EmrApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('reads the EMR sheet of an admission', () => {
    api.sheet('a1').subscribe((s) => expect(s.admissionId).toBe('a1'));
    const req = http.expectOne('/api/v1/ipd/admissions/a1/emr');
    expect(req.request.method).toBe('GET');
    req.flush(sheetOf());
  });

  it('posts an order with the body as given', () => {
    const body = {
      clientKey: 'k1',
      drugKey: 'asp',
      frequencyKey: 'od',
      doseAmount: 1,
      doseUnit: 'TABLET',
      routeKey: 'ORAL',
      priority: 'ROUTINE' as const,
    };
    api.order('a1', body).subscribe();
    const req = http.expectOne('/api/v1/ipd/admissions/a1/medicine-orders');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });

  it('stops an order with its version and reason', () => {
    const body = { clientKey: 'k1', orderVersion: 2, reason: 'Course finished' };
    api.stop('o1', body).subscribe();
    const req = http.expectOne('/api/v1/ipd/medicine-orders/o1/stop');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush(sheetOf());
  });

  it.each([
    ['change', { clientKey: 'k', orderVersion: 1, reason: 'Dose up', doseAmount: 2, doseUnit: 'TABLET', routeKey: 'ORAL', priority: 'ROUTINE' as const }],
    ['hold', { clientKey: 'k', orderVersion: 1, reason: 'Nil by mouth' }],
    ['resume', { clientKey: 'k', orderVersion: 1 }],
    ['query-reply', { clientKey: 'k', orderVersion: 1, reply: 'Checked' }],
  ] as const)('posts %s to its own path with the body', (name, body) => {
    const call = {
      change: () => api.change('o1', body as never),
      hold: () => api.hold('o1', body as never),
      resume: () => api.resume('o1', body as never),
      'query-reply': () => api.queryReply('o1', body as never),
    }[name];
    call().subscribe();
    const req = http.expectOne(`/api/v1/ipd/medicine-orders/o1/${name}`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush(sheetOf());
  });
});
