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
    const body = { clientKey: 'k1', drugKey: 'asp', frequencyKey: 'od' };
    api.order('a1', body).subscribe();
    const req = http.expectOne('/api/v1/ipd/admissions/a1/medicine-orders');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });

  it('stops an order with no body', () => {
    api.stop('o1').subscribe();
    const req = http.expectOne('/api/v1/ipd/medicine-orders/o1/stop');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush(sheetOf());
  });
});
