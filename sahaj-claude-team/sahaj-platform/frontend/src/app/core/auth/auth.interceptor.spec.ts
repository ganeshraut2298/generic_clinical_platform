import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { BOOTSTRAP_CALL } from '../http/bootstrap-call';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let auth: { accessToken: ReturnType<typeof vi.fn>; refresh: ReturnType<typeof vi.fn> };
  let router: Router;

  beforeEach(() => {
    auth = { accessToken: vi.fn().mockReturnValue('token-1'), refresh: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: auth },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => httpMock.verify());

  it('attaches the access token to a protected request', () => {
    http.get('/api/v1/platform/me').subscribe();
    const req = httpMock.expectOne('/api/v1/platform/me');
    expect(req.request.headers.get('Authorization')).toBe('Bearer token-1');
    req.flush({});
  });

  it('never attaches the access token to an /auth/* call', () => {
    http.post('/api/v1/platform/auth/otp/request', {}).subscribe();
    const req = httpMock.expectOne('/api/v1/platform/auth/otp/request');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('attaches the access token to logout and logout-all', () => {
    for (const path of ['logout', 'logout-all']) {
      http.post(`/api/v1/platform/auth/${path}`, null).subscribe();
      const req = httpMock.expectOne(`/api/v1/platform/auth/${path}`);
      expect(req.request.headers.get('Authorization')).toBe('Bearer token-1');
      req.flush(null);
    }
  });

  it('never attaches the token to select-clinic or refresh', () => {
    for (const path of ['select-clinic', 'refresh']) {
      http.post(`/api/v1/platform/auth/${path}`, {}).subscribe();
      const req = httpMock.expectOne(`/api/v1/platform/auth/${path}`);
      expect(req.request.headers.has('Authorization')).toBe(false);
      req.flush({});
    }
  });

  it('lets a marked bootstrap call pass: no clinic token and no refresh on a 401 (dev origin case)', () => {
    // In development the API base is another origin, so the URL rule alone does not see the call.
    const url = 'http://localhost:4200/api/v1/admin/bootstrap/unlock';
    http
      .post(url, {}, { context: new HttpContext().set(BOOTSTRAP_CALL, true) })
      .subscribe({ error: () => undefined });
    const req = httpMock.expectOne(url);
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush('no', { status: 401, statusText: 'Unauthorized' });
    expect(auth.refresh).not.toHaveBeenCalled();
    expect(auth.accessToken).not.toHaveBeenCalled();
  });

  it('ignores the marker on any other path: the token is attached and a 401 still refreshes', () => {
    auth.accessToken.mockReturnValueOnce('token-1').mockReturnValue('token-2');
    auth.refresh.mockReturnValue(of(true));
    const marked = { context: new HttpContext().set(BOOTSTRAP_CALL, true) };
    http.get('/api/v1/platform/me', marked).subscribe();
    const first = httpMock.expectOne('/api/v1/platform/me');
    expect(first.request.headers.get('Authorization')).toBe('Bearer token-1');
    first.flush('no', { status: 401, statusText: 'Unauthorized' });
    expect(auth.refresh).toHaveBeenCalledTimes(1);
    httpMock.expectOne('/api/v1/platform/me').flush({});
  });

  it('retries once through refresh after a 401, then succeeds', () => {
    auth.accessToken.mockReturnValueOnce('token-1').mockReturnValue('token-2');
    auth.refresh.mockReturnValue(of(true));
    let result: unknown;
    http.get('/api/v1/platform/me').subscribe((value) => (result = value));

    const first = httpMock.expectOne('/api/v1/platform/me');
    expect(first.request.headers.get('Authorization')).toBe('Bearer token-1');
    first.flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });
    expect(auth.refresh).toHaveBeenCalledTimes(1);

    const retried = httpMock.expectOne('/api/v1/platform/me');
    expect(retried.request.headers.get('Authorization')).toBe('Bearer token-2');
    retried.flush({ ok: true });
    expect(result).toEqual({ ok: true });
  });

  it('redirects to /login when the refresh also fails', () => {
    auth.refresh.mockReturnValue(of(false));
    const navigate = vi.spyOn(router, 'navigate');
    let error: unknown;
    http.get('/api/v1/platform/me').subscribe({ error: (e: unknown) => (error = e) });

    httpMock.expectOne('/api/v1/platform/me').flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });
    expect(navigate).toHaveBeenCalledWith(['/login']);
    expect(error).toBeTruthy();
  });

  it('does not retry a 401 from an /auth/* call', () => {
    let error: unknown;
    http.post('/api/v1/platform/auth/otp/verify', {}).subscribe({ error: (e: unknown) => (error = e) });
    httpMock
      .expectOne('/api/v1/platform/auth/otp/verify')
      .flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });
    expect(auth.refresh).not.toHaveBeenCalled();
    expect(error).toBeTruthy();
  });

  describe('admin realm isolation (ADR-003 D8)', () => {
    const adminUrls = [
      '/api/v1/admin/me',
      '/api/v1/admin/clinics?page=0',
      '/api/v1/admin/auth/sign-out',
      '/api/v1/admin/auth/login',
      '/api/v1/admin/../admin/me',
      '/api/v1/%61dmin/me',
      '/api/v1/Admin/me',
    ];

    it('never attaches the clinic token to /api/v1/admin/**', () => {
      for (const url of adminUrls) {
        http.get(url).subscribe();
        const req = httpMock.expectOne((r) => r.url === url);
        expect(req.request.headers.has('Authorization'), url).toBe(false);
        req.flush({});
      }
    });

    it('does not refresh the clinic session or go to /login after a 401 from an admin call', () => {
      const navigate = vi.spyOn(router, 'navigate');
      let error: unknown;
      http.get('/api/v1/admin/me').subscribe({ error: (e: unknown) => (error = e) });
      httpMock
        .expectOne('/api/v1/admin/me')
        .flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });
      expect(auth.refresh).not.toHaveBeenCalled();
      expect(navigate).not.toHaveBeenCalled();
      expect(error).toBeTruthy();
    });

    it('still attaches the clinic token to a clinic URL that only looks similar', () => {
      http.get('/api/v1/platform/admin-notes').subscribe();
      const req = httpMock.expectOne('/api/v1/platform/admin-notes');
      expect(req.request.headers.get('Authorization')).toBe('Bearer token-1');
      req.flush({});
    });
  });
});
