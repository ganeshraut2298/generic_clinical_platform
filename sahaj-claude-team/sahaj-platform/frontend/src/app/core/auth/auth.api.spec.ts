import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthApi } from './auth.api';

const BASE = '/api/v1/platform/auth';

describe('AuthApi', () => {
  let api: AuthApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(AuthApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('posts the mobile and mode to request a code, with credentials', () => {
    api.requestOtp({ mobile: '9822000001', mode: 'LOGIN' }).subscribe();
    const req = http.expectOne(`${BASE}/otp/request`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ mobile: '9822000001', mode: 'LOGIN' });
    expect(req.request.withCredentials).toBe(true);
    req.flush({ resendAfterSeconds: 30 });
  });

  it('posts mobile, code, mode and consent to verify', () => {
    const consent = { termsVersion: 'v0.1', privacyVersion: 'v0.1' };
    api
      .verifyOtp({ mobile: '9822000001', code: '123456', mode: 'SIGNUP', consent })
      .subscribe();
    const req = http.expectOne(`${BASE}/otp/verify`);
    expect(req.request.body).toEqual({
      mobile: '9822000001',
      code: '123456',
      mode: 'SIGNUP',
      consent,
    });
    req.flush({ next: 'SETUP' });
  });

  it('never logs or exposes the code: the body is the only place it travels', () => {
    api.verifyOtp({ mobile: '9822000001', code: '000000', mode: 'LOGIN' }).subscribe();
    const req = http.expectOne(`${BASE}/otp/verify`);
    expect(req.request.urlWithParams).toBe(`${BASE}/otp/verify`);
    req.flush({ next: 'APP', accessToken: 'token', expiresInSeconds: 900 });
  });

  it('posts the selection token and tenant id to select a clinic', () => {
    api.selectClinic({ selectionToken: 'sel-1', tenantId: 'tenant-1' }).subscribe();
    const req = http.expectOne(`${BASE}/select-clinic`);
    expect(req.request.body).toEqual({ selectionToken: 'sel-1', tenantId: 'tenant-1' });
    req.flush({ next: 'APP', accessToken: 'token' });
  });

  it('calls refresh with no body and credentials, for the HttpOnly cookie', () => {
    api.refresh().subscribe();
    const req = http.expectOne(`${BASE}/refresh`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    expect(req.request.withCredentials).toBe(true);
    req.flush({ next: 'APP', accessToken: 'token' });
  });

  it('calls logout and logout-all', () => {
    api.logout().subscribe();
    http.expectOne(`${BASE}/logout`).flush(null, { status: 204, statusText: 'No content' });

    api.logoutAll().subscribe();
    http.expectOne(`${BASE}/logout-all`).flush(null, { status: 204, statusText: 'No content' });
  });
});
