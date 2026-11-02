import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
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
});
