import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SetupApi } from './setup.api';

const BASE = '/api/v1/platform/setup';

describe('SetupApi', () => {
  let api: SetupApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(SetupApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('gets the saved progress', () => {
    api.getState().subscribe();
    const req = http.expectOne(BASE);
    expect(req.request.method).toBe('GET');
    req.flush({ currentStep: 1, answers: {}, limits: { doctors: 2, users: 5 } });
  });

  it('puts a step body to /setup/steps/{step}', () => {
    api.saveStep(1, { name: 'Shree Sai Clinic', city: 'Pune' }).subscribe();
    const req = http.expectOne(`${BASE}/steps/1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ name: 'Shree Sai Clinic', city: 'Pune' });
    req.flush({ currentStep: 2, answers: {}, limits: { doctors: 2, users: 5 } });
  });

  it('checks slug availability with a query parameter', () => {
    api.checkSlugAvailability('shreesai').subscribe();
    const req = http.expectOne(`${BASE}/slug-availability?slug=shreesai`);
    expect(req.request.method).toBe('GET');
    req.flush({ available: true, alternatives: [] });
  });

  it('posts to complete with no body', () => {
    api.complete().subscribe();
    const req = http.expectOne(`${BASE}/complete`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush({ next: 'APP', brand: 'blue' });
  });
});
