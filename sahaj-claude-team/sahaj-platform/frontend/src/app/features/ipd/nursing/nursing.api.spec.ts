import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NursingApi } from './nursing.api';
import { VitalsInput } from './nursing.model';

const IPD = '/api/v1/ipd';

describe('NursingApi', () => {
  let api: NursingApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(NursingApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the Nursing sheet of an admission', () => {
    api.sheet('a1').subscribe();
    const req = http.expectOne(`${IPD}/admissions/a1/nursing`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('posts the vitals to the admission', () => {
    const body: VitalsInput = {
      clientKey: '6f1c1c4e-3a8a-4a43-9a1b-0c5f8b3c1d11',
      bp: '120/80',
      pulse: '80',
      spo2: '98',
      temp: '37.0',
      rr: '16',
      note: null,
    };
    api.record('a1', body).subscribe();
    const req = http.expectOne(`${IPD}/admissions/a1/vitals`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({}, { status: 201, statusText: 'Created' });
  });
});
