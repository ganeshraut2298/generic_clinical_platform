import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE_URL } from '../../../core/config/api-config';
import { BOOTSTRAP_CALL, isBootstrapCall } from '../../../core/http/bootstrap-call';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { AdminBootstrapApi } from './admin-bootstrap.api';

describe('AdminBootstrapApi', () => {
  function setup(base?: string) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        ...(base ? [{ provide: API_BASE_URL, useValue: base }] : []),
      ],
    });
    return {
      api: TestBed.inject(AdminBootstrapApi),
      http: TestBed.inject(HttpTestingController),
    };
  }

  it('posts the PIN in the body only, with credentials, marked as a bootstrap call', () => {
    const { api, http } = setup();
    api.unlock({ pin: 'throwaway-1' }).subscribe();
    const req = http.expectOne('/api/v1/admin/bootstrap/unlock');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ pin: 'throwaway-1' });
    expect(req.request.url).not.toContain('throwaway');
    expect(req.request.urlWithParams).not.toContain('throwaway');
    expect(req.request.headers.keys()).toEqual([]);
    expect(req.request.withCredentials).toBe(true);
    expect(req.request.context.get(BOOTSTRAP_CALL)).toBe(true);
    req.flush({ ticket: 'T'.repeat(43), expiresInSeconds: 300 });
    http.verify();
  });

  it('posts the registration with the ticket in the body, never in the URL or a header', () => {
    const { api, http } = setup();
    const body = {
      ticket: 'T'.repeat(43),
      fullName: 'First admin',
      email: 'first.admin@example.test',
      password: 'throwaway-password-1',
    };
    let answered: unknown = 'not answered';
    api.register(body).subscribe((value) => (answered = value));
    const req = http.expectOne('/api/v1/admin/bootstrap/register');
    expect(req.request.body).toEqual(body);
    expect(req.request.urlWithParams).not.toContain('TTTT');
    expect(req.request.headers.keys()).toEqual([]);
    expect(req.request.withCredentials).toBe(true);
    expect(req.request.context.get(BOOTSTRAP_CALL)).toBe(true);
    // The 201 has no body (ADR-018 Revision 5): it arrives as null and nothing is read from it.
    req.flush(null, { status: 201, statusText: 'Created' });
    expect(answered).toBeNull();
    http.verify();
  });

  it('sends both calls on paths the interceptors treat as bootstrap calls, under the default base and the development base', () => {
    for (const base of [undefined, 'http://localhost:8080/api/v1']) {
      TestBed.resetTestingModule();
      const { api, http } = setup(base);
      api.unlock({ pin: 'throwaway-3' }).subscribe();
      api
        .register({
          ticket: 'T'.repeat(43),
          fullName: 'First admin',
          email: 'first.admin@example.test',
          password: 'throwaway-password-2',
        })
        .subscribe();
      const unlock = http.expectOne((r) => r.url.endsWith('/unlock'));
      const register = http.expectOne((r) => r.url.endsWith('/register'));
      expect(isBootstrapCall(unlock.request), `unlock, base ${base}`).toBe(true);
      expect(isBootstrapCall(register.request), `register, base ${base}`).toBe(true);
      unlock.flush({ ticket: 'T'.repeat(43), expiresInSeconds: 300 });
      register.flush(null, { status: 201, statusText: 'Created' });
      http.verify();
    }
  });

  it('stays same-origin even when the app API base is another origin (development, port 8080)', () => {
    const { api, http } = setup('http://localhost:8080/api/v1');
    api.unlock({ pin: 'throwaway-2' }).subscribe();
    http.expectOne('/api/v1/admin/bootstrap/unlock').flush({ ticket: 'T'.repeat(43), expiresInSeconds: 300 });
    http.verify();
  });
});
