import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { EmergencyAccessApi } from './emergency-access.api';

const BASE = '/api/v1/ipd/emergency-access';

describe('EmergencyAccessApi (SAH-34, docs/api/ipd.yaml)', () => {
  let api: EmergencyAccessApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(EmergencyAccessApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('reads the picker with GET /emergency-access/patients', () => {
    api.picker().subscribe();
    const req = http.expectOne(`${BASE}/patients`);
    expect(req.request.method).toBe('GET');
    req.flush({ items: [], reasons: [] });
  });

  it('opens a grant with POST /emergency-access and only the three fields: no duration, time or user', () => {
    api.open({ admissionId: 'a1', reasonKey: 'DETERIORATING', confirmed: true }).subscribe();
    const req = http.expectOne(BASE);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ admissionId: 'a1', reasonKey: 'DETERIORATING', confirmed: true });
    req.flush({});
  });

  it('reads the next page with limit 25 and the cursor', () => {
    api.reviewPage('abc_-1').subscribe();
    const req = http.expectOne((r) => r.url === BASE);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('limit')).toBe('25');
    expect(req.request.params.get('cursor')).toBe('abc_-1');
    req.flush({});
  });

  it('marks one grant reviewed with POST /emergency-access/{id}/review and no body', () => {
    api.review('g1').subscribe();
    const req = http.expectOne(`${BASE}/g1/review`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush({});
  });
});
