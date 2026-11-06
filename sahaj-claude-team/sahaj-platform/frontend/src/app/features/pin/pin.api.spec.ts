import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PinApi } from './pin.api';

describe('PinApi', () => {
  let api: PinApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(PinApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('reads my status', () => {
    api.status().subscribe();
    const req = http.expectOne('/api/v1/platform/me/pin');
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('asks for a code with no body', () => {
    api.sendCode().subscribe();
    const req = http.expectOne('/api/v1/platform/me/pin/code');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush({});
  });

  it('puts the code and the PIN twice, as given', () => {
    const body = { code: '493017', newPin: '4821', confirmPin: '4821' };
    api.set(body).subscribe();
    const req = http.expectOne('/api/v1/platform/me/pin');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });
});
