import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../../core/http/problem-detail';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { SalesEnquiryApi } from './sales-enquiry.api';
import { SalesEnquiryRequest } from './sales-enquiry.model';

const URL = '/api/v1/platform/public/sales-enquiries';

const REQUEST: SalesEnquiryRequest = {
  name: 'Dr. Asha Rao',
  clinicCity: 'Rao Clinic, Pune',
  mobile: '9822012345',
  bestTime: 'AFTER_OPD',
  source: 'BOOK_DEMO',
};

describe('SalesEnquiryApi', () => {
  let api: SalesEnquiryApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    api = TestBed.inject(SalesEnquiryApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('posts the enquiry to the public platform endpoint', () => {
    api.create(REQUEST).subscribe();
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(REQUEST);
    req.flush({ id: '7f1c2d4e-0a1b-4c5d-8e9f-001122334455' }, { status: 201, statusText: 'Created' });
  });

  it('returns the saved id on 201', () => {
    let id: string | undefined;
    api.create(REQUEST).subscribe((result) => (id = result.id));
    http
      .expectOne(URL)
      .flush({ id: '7f1c2d4e-0a1b-4c5d-8e9f-001122334455' }, { status: 201, statusText: 'Created' });
    expect(id).toBe('7f1c2d4e-0a1b-4c5d-8e9f-001122334455');
  });

  it('does not count a 200 as saved', () => {
    let error: unknown;
    api.create(REQUEST).subscribe({ error: (e: unknown) => (error = e) });
    http.expectOne(URL).flush({ id: 'x' }, { status: 200, statusText: 'OK' });
    expect(error).toBeInstanceOf(ApiError);
  });

  it('does not count a 201 without an id as saved', () => {
    let error: unknown;
    api.create(REQUEST).subscribe({ error: (e: unknown) => (error = e) });
    http.expectOne(URL).flush({}, { status: 201, statusText: 'Created' });
    expect(error).toBeInstanceOf(ApiError);
  });

  it('fails with the field errors of a 400', () => {
    let error: ApiError | undefined;
    api.create(REQUEST).subscribe({ error: (e: ApiError) => (error = e) });
    http.expectOne(URL).flush(
      {
        type: 'https://sahaj.in/problems/validation',
        title: 'Check the form',
        status: 400,
        fieldErrors: [{ field: 'mobile', message: 'Enter a valid mobile number' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    expect(error?.status).toBe(400);
    expect(error?.fieldErrors[0]).toEqual({ field: 'mobile', message: 'Enter a valid mobile number' });
  });

  it('fails with 429 when the limit is reached', () => {
    let error: ApiError | undefined;
    api.create(REQUEST).subscribe({ error: (e: ApiError) => (error = e) });
    http
      .expectOne(URL)
      .flush({ title: 'Too many requests', status: 429 }, { status: 429, statusText: 'Too Many Requests' });
    expect(error?.status).toBe(429);
  });
});
