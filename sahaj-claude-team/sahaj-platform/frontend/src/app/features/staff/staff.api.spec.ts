import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { StaffApi } from './staff.api';

describe('StaffApi', () => {
  let api: StaffApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(StaffApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads summary, roster and attendance', () => {
    api.summary().subscribe();
    api.roster().subscribe();
    api.attendance().subscribe();
    expect(http.expectOne('/api/v1/hra/summary').request.method).toBe('GET');
    expect(http.expectOne('/api/v1/hra/roster').request.method).toBe('GET');
    expect(http.expectOne('/api/v1/hra/attendance').request.method).toBe('GET');
  });

  it('puts a roster cell', () => {
    const cell = { membershipId: 'm1', date: '2026-11-05', shift: 'E' as const };
    api.setCell(cell).subscribe();
    const req = http.expectOne('/api/v1/hra/roster/cells');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(cell);
  });

  it('publishes the roster', () => {
    api.publish().subscribe();
    expect(http.expectOne('/api/v1/hra/roster/publish').request.method).toBe('POST');
  });

  it('marks attendance', () => {
    api.mark('m1', 'late').subscribe();
    const req = http.expectOne('/api/v1/hra/attendance/m1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ status: 'late' });
  });

  it('reads leave and posts apply, approve and decline', () => {
    api.leave().subscribe();
    expect(http.expectOne('/api/v1/hra/leave').request.method).toBe('GET');

    const input = { type: 'CL' as const, from: '2026-11-13', to: '2026-11-15' };
    api.applyLeave(input).subscribe();
    const apply = http.expectOne('/api/v1/hra/leave');
    expect(apply.request.method).toBe('POST');
    expect(apply.request.body).toEqual(input);

    api.approveLeave('l1').subscribe();
    expect(http.expectOne('/api/v1/hra/leave/l1/approve').request.method).toBe('POST');
    api.declineLeave('l1').subscribe();
    expect(http.expectOne('/api/v1/hra/leave/l1/decline').request.method).toBe('POST');
  });
});
