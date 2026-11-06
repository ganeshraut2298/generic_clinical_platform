import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { RevenueApi } from './revenue.api';
import { RevenueReport } from './revenue.model';

describe('RevenueApi', () => {
  let api: RevenueApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(RevenueApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('calls GET /billing/revenue with the range param', () => {
    const body = { range: 'WEEK' } as RevenueReport;
    let result: RevenueReport | undefined;
    api.report('WEEK').subscribe((r) => (result = r));
    const req = http.expectOne((r) => r.url === '/api/v1/billing/revenue');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('range')).toBe('WEEK');
    req.flush(body);
    expect(result).toEqual(body);
  });
});
