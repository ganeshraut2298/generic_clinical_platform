import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DeskApi } from './desk.api';

const BASE = '/api/v1/opd/desk';

describe('DeskApi', () => {
  let api: DeskApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(DeskApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the snapshot with no parameters until a doctor is selected', () => {
    api.getSnapshot().subscribe();
    const req = http.expectOne(`${BASE}/snapshot`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys()).toEqual([]);
  });

  it('sends the selected doctor and day to the snapshot', () => {
    api.getSnapshot({ doctorMembershipId: 'd1', date: '2026-11-25' }).subscribe();
    const req = http.expectOne((r) => r.url === `${BASE}/snapshot`);
    expect(req.request.params.get('doctorMembershipId')).toBe('d1');
    expect(req.request.params.get('date')).toBe('2026-11-25');
  });

  it('reads one doctor day of slots', () => {
    api.getDaySlots('d1', '2026-11-25').subscribe();
    const req = http.expectOne((r) => r.url === `${BASE}/doctors/d1/day-slots`);
    expect(req.request.params.get('date')).toBe('2026-11-25');
  });
});
