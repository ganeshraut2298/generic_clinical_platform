import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ShellApi } from './shell.api';

const BASE = '/api/v1/platform/me';

describe('ShellApi', () => {
  let api: ShellApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ShellApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('gets the signed-in user', () => {
    api.getMe().subscribe();
    const req = http.expectOne(BASE);
    expect(req.request.method).toBe('GET');
    req.flush({ userId: 'u1' });
  });

  it('patches preferences', () => {
    api.updatePreferences({ theme: 'dark' }).subscribe();
    const req = http.expectOne(`${BASE}/preferences`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ theme: 'dark' });
    req.flush({ userId: 'u1' });
  });
});
