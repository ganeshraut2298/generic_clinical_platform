import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TeamApi } from './team.api';

const BASE = '/api/v1/platform';

describe('TeamApi', () => {
  let api: TeamApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(TeamApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the team', () => {
    api.list().subscribe();
    const req = http.expectOne(`${BASE}/team`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('adds a person with name, mobile and role', () => {
    const body = { name: 'Meera', mobile: '9876543210', role: 'NURSE' as const };
    api.add(body).subscribe();
    const req = http.expectOne(`${BASE}/team`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });

  it('updates, deactivates, reactivates and removes', () => {
    api.update('m1', { role: 'BILLING' }).subscribe();
    const patch = http.expectOne(`${BASE}/team/m1`);
    expect(patch.request.method).toBe('PATCH');
    patch.flush({});

    api.deactivate('m1').subscribe();
    const off = http.expectOne(`${BASE}/team/m1/deactivate`);
    expect(off.request.method).toBe('POST');
    off.flush({});

    api.reactivate('m1').subscribe();
    const re = http.expectOne(`${BASE}/team/m1/reactivate`);
    expect(re.request.method).toBe('POST');
    re.flush({});

    api.remove('m1').subscribe();
    const del = http.expectOne(`${BASE}/team/m1`);
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
  });

  it('reads and replaces the permission matrix', () => {
    api.getRolePermissions().subscribe();
    http.expectOne(`${BASE}/role-permissions`).flush([]);

    const matrix = [{ role: 'NURSE' as const, permission: 'vitals' as const, allowed: true }];
    api.saveRolePermissions(matrix).subscribe();
    const req = http.expectOne(`${BASE}/role-permissions`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(matrix);
    req.flush(matrix);
  });

  it('reads the colleague tries of one member (SAH-38, owner only)', () => {
    api.colleagueTries('m-nurse').subscribe();
    const req = http.expectOne('/api/v1/platform/team/m-nurse/pin/colleague-tries');
    expect(req.request.method).toBe('GET');
    req.flush({});
  });
});
