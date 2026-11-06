import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MarApi } from './mar.api';
import { DoseInput } from './mar.model';

const IPD = '/api/v1/ipd';

describe('MarApi', () => {
  let api: MarApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(MarApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the MAR of an admission', () => {
    api.mar('a1').subscribe();
    const req = http.expectOne(`${IPD}/admissions/a1/mar`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('posts a dose to the order', () => {
    const body: DoseInput = {
      clientKey: '6f1c1c4e-3a8a-4a43-9a1b-0c5f8b3c1d11',
      slotAt: '2026-11-06T02:30:00Z',
      outcome: 'NOT_GIVEN',
      reasonKey: 'PATIENT_REFUSED',
    };
    api.give('o1', body).subscribe();
    const req = http.expectOne(`${IPD}/medicine-orders/o1/doses`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({}, { status: 201, statusText: 'Created' });
  });
});
