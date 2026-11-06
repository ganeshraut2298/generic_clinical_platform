import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PharmacistApi } from './pharmacist.api';
import { checksOf } from './pharmacist.fixtures';

describe('PharmacistApi', () => {
  let api: PharmacistApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(PharmacistApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('reads the first page with a limit and no cursor', () => {
    api.checks(50).subscribe();
    const req = http.expectOne((r) => r.url === '/api/v1/ipd/pharmacist-checks');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('limit')).toBe('50');
    expect(req.request.params.has('cursor')).toBe(false);
    req.flush(checksOf());
  });

  it('reads the next page with the cursor', () => {
    api.checks(50, 'abc_-123').subscribe();
    const req = http.expectOne((r) => r.url === '/api/v1/ipd/pharmacist-checks');
    expect(req.request.params.get('cursor')).toBe('abc_-123');
    req.flush(checksOf());
  });

  it('reads the counts-only summary', () => {
    api.summary().subscribe();
    const req = http.expectOne('/api/v1/ipd/pharmacist-checks/summary');
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('posts the PIN and the checkToken to verify', () => {
    api.verify('o1', { pin: '4821', checkToken: 'A'.repeat(22) }).subscribe();
    const req = http.expectOne('/api/v1/ipd/medicine-orders/o1/verify');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ pin: '4821', checkToken: 'A'.repeat(22) });
    req.flush({});
  });

  it('posts the reason key to reject', () => {
    api.reject('o1', { reasonKey: 'WRONG_DOSE' }).subscribe();
    const req = http.expectOne('/api/v1/ipd/medicine-orders/o1/reject');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reasonKey: 'WRONG_DOSE' });
    req.flush({});
  });
});
