import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AdminAuthResult } from './admin-auth.model';
import { AdminAuthService } from './admin-auth.service';
import { AdminSessionStore } from './admin-session.store';

const BASE = '/api/v1/admin/auth';
const PRE_AUTH = 'A'.repeat(43);

function result(overrides: Partial<AdminAuthResult> = {}): AdminAuthResult {
  return {
    accessToken: 'jwt-1',
    expiresInSeconds: 600,
    admin: { id: 'id-1', name: 'Neha Rao', role: 'PLATFORM_ADMIN', roleLabel: 'Platform admin' },
    ...overrides,
  };
}

describe('AdminAuthService', () => {
  let service: AdminAuthService;
  let store: AdminSessionStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AdminAuthService);
    store = TestBed.inject(AdminSessionStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('login posts the trimmed email and password with credentials, and never stores an access token', () => {
    let next: string | undefined;
    service.login('  neha@sahaj.in ', 'a-long-password').subscribe((r) => (next = r.next));
    const req = http.expectOne(`${BASE}/login`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'neha@sahaj.in', password: 'a-long-password' });
    expect(req.request.withCredentials).toBe(true);
    req.flush({ next: 'TOTP', preAuthToken: PRE_AUTH, expiresInSeconds: 300 });
    expect(next).toBe('TOTP');
    expect(service.isAuthenticated()).toBe(false);
  });

  it('enrol posts the pre-auth token', () => {
    service.enrol(PRE_AUTH).subscribe();
    const req = http.expectOne(`${BASE}/totp/enrol`);
    expect(req.request.body).toEqual({ preAuthToken: PRE_AUTH });
    req.flush({});
  });

  it('verify keeps the token and admin in memory, returns the recovery codes, and stores neither in the browser', () => {
    const codes = Array.from({ length: 10 }, () => 'ABCD-EFGH-IJKL-MNOP');
    let received: AdminAuthResult | undefined;
    service.verify({ preAuthToken: PRE_AUTH, code: '123456' }).subscribe((r) => (received = r));
    const req = http.expectOne(`${BASE}/totp/verify`);
    expect(req.request.body).toEqual({ preAuthToken: PRE_AUTH, code: '123456' });
    req.flush(result({ recoveryCodes: codes }));

    expect(received?.recoveryCodes).toEqual(codes);
    expect(service.accessToken()).toBe('jwt-1');
    expect(service.admin()?.name).toBe('Neha Rao');
    expect(JSON.stringify(store)).not.toContain('ABCD');
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('verify with a recovery code sends recoveryCode only', () => {
    service.verify({ preAuthToken: PRE_AUTH, recoveryCode: 'ABCD-EFGH-IJKL-MNOP' }).subscribe();
    const req = http.expectOne(`${BASE}/totp/verify`);
    expect(req.request.body).toEqual({ preAuthToken: PRE_AUTH, recoveryCode: 'ABCD-EFGH-IJKL-MNOP' });
    req.flush(result());
  });

  it('refresh shares one call, stores the new token and rotates through the cookie', () => {
    const outcomes: boolean[] = [];
    service.refresh().subscribe((ok) => outcomes.push(ok));
    service.refresh().subscribe((ok) => outcomes.push(ok));
    const req = http.expectOne(`${BASE}/refresh`);
    expect(req.request.withCredentials).toBe(true);
    expect(req.request.body).toBeNull();
    req.flush(result({ accessToken: 'jwt-2' }));
    expect(outcomes).toEqual([true, true]);
    expect(service.accessToken()).toBe('jwt-2');
  });

  it('a failed refresh clears the session and reports false', () => {
    store.set(result());
    let ok: boolean | undefined;
    service.refresh().subscribe((value) => (ok = value));
    http.expectOne(`${BASE}/refresh`).flush('no', { status: 401, statusText: 'Unauthorized' });
    expect(ok).toBe(false);
    expect(service.isAuthenticated()).toBe(false);
  });

  it('restore does not call the server when already signed in, and tries the cookie when not', () => {
    store.set(result());
    let ok: boolean | undefined;
    service.restore().subscribe((value) => (ok = value));
    http.expectNone(`${BASE}/refresh`);
    expect(ok).toBe(true);

    store.clear();
    service.restore().subscribe((value) => (ok = value));
    http.expectOne(`${BASE}/refresh`).flush(result({ accessToken: 'jwt-3' }));
    expect(ok).toBe(true);
    expect(service.accessToken()).toBe('jwt-3');
  });

  it('signOut clears the session even when the server call fails', () => {
    store.set(result());
    let done = false;
    service.signOut().subscribe(() => (done = true));
    http.expectOne(`${BASE}/sign-out`).flush('no', { status: 500, statusText: 'Server error' });
    expect(done).toBe(true);
    expect(service.isAuthenticated()).toBe(false);
  });

  it('signOut posts to sign-out and clears the session', () => {
    store.set(result());
    service.signOut().subscribe();
    const req = http.expectOne(`${BASE}/sign-out`);
    expect(req.request.method).toBe('POST');
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(service.accessToken()).toBeNull();
    expect(service.admin()).toBeNull();
  });
});
