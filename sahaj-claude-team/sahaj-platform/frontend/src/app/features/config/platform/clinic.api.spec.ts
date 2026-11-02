import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ClinicApi } from './clinic.api';

const BASE = '/api/v1/platform';

describe('ClinicApi', () => {
  let api: ClinicApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(ClinicApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('saves the profile without the read-only slug', () => {
    api.saveProfile({ name: 'Shree Sai Clinic', slug: 'shreesai', city: 'Pune' }).subscribe();
    const req = http.expectOne(`${BASE}/clinic/profile`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ name: 'Shree Sai Clinic', city: 'Pune' });
    req.flush({ name: 'Shree Sai Clinic' });
  });

  it('adds and removes a holiday', () => {
    api.addHoliday({ date: '2026-11-09', name: 'Diwali', type: 'CLOSED' }).subscribe();
    const add = http.expectOne(`${BASE}/clinic/holidays`);
    expect(add.request.method).toBe('POST');
    expect(add.request.body).toEqual({ date: '2026-11-09', name: 'Diwali', type: 'CLOSED' });
    add.flush({ id: 'h1', date: '2026-11-09', name: 'Diwali', type: 'CLOSED' });

    api.removeHoliday('h1').subscribe();
    const del = http.expectOne(`${BASE}/clinic/holidays/h1`);
    expect(del.request.method).toBe('DELETE');
    del.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('applies a specialty with only the code', () => {
    api.applySpecialty('dental').subscribe();
    const req = http.expectOne(`${BASE}/clinic/specialty`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ code: 'dental' });
    req.flush({ name: 'Clinic', specialtyCode: 'dental' });
  });

  it('asks for the overview and the latest audit events', () => {
    api.getOverview().subscribe();
    http.expectOne(`${BASE}/setup/overview`).flush({ percent: 0, checks: [] });
    api.listAuditEvents(6).subscribe();
    const req = http.expectOne((r) => r.url === `${BASE}/audit-events`);
    expect(req.request.params.get('limit')).toBe('6');
    req.flush([]);
  });
});
