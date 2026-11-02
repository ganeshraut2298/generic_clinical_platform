import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiError } from './problem-detail';
import { problemDetailInterceptor } from './problem-detail.interceptor';

describe('problemDetailInterceptor', () => {
  let http: HttpClient;
  let controller: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  function failWith(body: string | object | null, status: number, headers: Record<string, string> = {}) {
    let caught: unknown;
    http.get('/x').subscribe({ error: (e: unknown) => (caught = e) });
    controller.expectOne('/x').flush(body, { status, statusText: 'Error', headers });
    return caught;
  }

  it('turns a problem+json body into an ApiError with field errors', () => {
    const caught = failWith(
      {
        type: 'https://sahaj.in/problems/validation',
        title: 'Check the form',
        status: 400,
        fieldErrors: [{ field: 'mobile', message: 'Enter a 10-digit mobile number' }],
      },
      400,
    );
    expect(caught).toBeInstanceOf(ApiError);
    const error = caught as ApiError;
    expect(error.status).toBe(400);
    expect(error.problem.title).toBe('Check the form');
    expect(error.fieldErrors).toEqual([
      { field: 'mobile', message: 'Enter a 10-digit mobile number' },
    ]);
  });

  it('reads retryAfterSeconds from the Retry-After header when the body has none', () => {
    const caught = failWith({ title: 'Too many requests', status: 429 }, 429, {
      'Retry-After': '120',
    }) as ApiError;
    expect(caught.status).toBe(429);
    expect(caught.problem.retryAfterSeconds).toBe(120);
  });

  it('builds a problem for a response that is not problem+json', () => {
    const caught = failWith('<html>bad gateway</html>', 502) as ApiError;
    expect(caught).toBeInstanceOf(ApiError);
    expect(caught.status).toBe(502);
    expect(caught.problem.type).toBe('about:blank');
  });

  it('reports status 0 when the server cannot be reached', () => {
    let caught: unknown;
    http.get('/x').subscribe({ error: (e: unknown) => (caught = e) });
    controller.expectOne('/x').error(new ProgressEvent('error'));
    expect(caught).toBeInstanceOf(ApiError);
    expect((caught as ApiError).status).toBe(0);
  });

  it('passes successful responses through', () => {
    let result: unknown;
    http.get('/x').subscribe((value) => (result = value));
    controller.expectOne('/x').flush({ ok: true });
    expect(result).toEqual({ ok: true });
  });
});
