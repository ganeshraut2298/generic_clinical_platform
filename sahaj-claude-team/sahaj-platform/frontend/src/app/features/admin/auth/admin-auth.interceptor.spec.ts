import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { adminAuthInterceptor } from './admin-auth.interceptor';
import { AdminAuthService } from './admin-auth.service';

describe('adminAuthInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let router: Router;
  let auth: {
    accessToken: ReturnType<typeof vi.fn>;
    refresh: ReturnType<typeof vi.fn>;
    endSession: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    auth = {
      accessToken: vi.fn().mockReturnValue('admin-token-1'),
      refresh: vi.fn(),
      endSession: vi.fn(),
    };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([adminAuthInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AdminAuthService, useValue: auth },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => httpMock.verify());

  it('attaches the admin token to /api/v1/admin/**', () => {
    for (const url of ['/api/v1/admin/me', '/api/v1/admin/clinics?page=0', '/api/v1/admin/auth/sign-out']) {
      http.get(url).subscribe();
      const req = httpMock.expectOne(url);
      expect(req.request.headers.get('Authorization'), url).toBe('Bearer admin-token-1');
      req.flush({});
    }
  });

  it('never attaches the admin token to any other URL', () => {
    const others = [
      '/api/v1/platform/me',
      '/api/v1/platform/admin/x',
      '/api/v1/patient/patients',
      '/api/v1/opd/visits',
      '/api/v1/administrator',
      '/api/v1/admin/../platform/me',
      'https://evil.example/api/v1/admin/me',
      '/assets/config.json',
    ];
    for (const url of others) {
      http.get(url).subscribe();
      const req = httpMock.expectOne(url);
      expect(req.request.headers.has('Authorization'), url).toBe(false);
      req.flush({});
    }
    expect(auth.accessToken).not.toHaveBeenCalled();
  });

  it('never attaches the token to the four public auth calls', () => {
    for (const path of ['login', 'totp/enrol', 'totp/verify', 'refresh']) {
      http.post(`/api/v1/admin/auth/${path}`, {}).subscribe();
      const req = httpMock.expectOne(`/api/v1/admin/auth/${path}`);
      expect(req.request.headers.has('Authorization'), path).toBe(false);
      req.flush({});
    }
  });

  it('does not try to refresh after a 401 from a public auth call', () => {
    let error: unknown;
    http.post('/api/v1/admin/auth/login', {}).subscribe({ error: (e: unknown) => (error = e) });
    httpMock
      .expectOne('/api/v1/admin/auth/login')
      .flush('no', { status: 401, statusText: 'Unauthorized' });
    expect(auth.refresh).not.toHaveBeenCalled();
    expect(error).toBeTruthy();
  });

  it('refreshes once after a 401 and retries with the new token', () => {
    auth.accessToken.mockReturnValueOnce('admin-token-1').mockReturnValue('admin-token-2');
    auth.refresh.mockReturnValue(of(true));
    let result: unknown;
    http.get('/api/v1/admin/me').subscribe((value) => (result = value));

    httpMock.expectOne('/api/v1/admin/me').flush('no', { status: 401, statusText: 'Unauthorized' });
    expect(auth.refresh).toHaveBeenCalledTimes(1);
    const retried = httpMock.expectOne('/api/v1/admin/me');
    expect(retried.request.headers.get('Authorization')).toBe('Bearer admin-token-2');
    retried.flush({ ok: true });
    expect(result).toEqual({ ok: true });
  });

  it('ends the session and returns to /admin/sign-in when the refresh fails', () => {
    auth.refresh.mockReturnValue(of(false));
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    let error: unknown;
    http.get('/api/v1/admin/me').subscribe({ error: (e: unknown) => (error = e) });

    httpMock.expectOne('/api/v1/admin/me').flush('no', { status: 401, statusText: 'Unauthorized' });
    expect(auth.endSession).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/admin/sign-in'], { queryParams: { reason: 'ended' } });
    expect(error).toBeTruthy();
  });

  it('leaves non-401 errors alone', () => {
    let error: unknown;
    http.get('/api/v1/admin/me').subscribe({ error: (e: unknown) => (error = e) });
    httpMock.expectOne('/api/v1/admin/me').flush('no', { status: 403, statusText: 'Forbidden' });
    expect(auth.refresh).not.toHaveBeenCalled();
    expect(error).toBeTruthy();
  });

  it('does not touch a 401 from a non-admin URL', () => {
    let error: unknown;
    http.get('/api/v1/platform/me').subscribe({ error: (e: unknown) => (error = e) });
    httpMock
      .expectOne('/api/v1/platform/me')
      .flush('no', { status: 401, statusText: 'Unauthorized' });
    expect(auth.refresh).not.toHaveBeenCalled();
    expect(error).toBeTruthy();
  });
});
