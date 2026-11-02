import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PatientApi } from './patient.api';

const BASE = '/api/v1/patient';

describe('PatientApi', () => {
  let api: PatientApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(PatientApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists patients with no params by default', () => {
    api.list().subscribe();
    const req = http.expectOne(`${BASE}/patients`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys().length).toBe(0);
    req.flush({ items: [], total: 0 });
  });

  it('lists patients with query, filter and page', () => {
    api.list({ q: 'Rohit', filter: 'chronic', page: 2 }).subscribe();
    const req = http.expectOne(
      (r) => r.url === `${BASE}/patients` && r.params.get('q') === 'Rohit',
    );
    expect(req.request.params.get('filter')).toBe('chronic');
    expect(req.request.params.get('page')).toBe('2');
    req.flush({ items: [], total: 0 });
  });

  it('registers a patient', () => {
    const body = { name: 'Rohit Patil', age: 30, sex: 'M' as const, mobile: '9876543210' };
    api.create(body).subscribe();
    const req = http.expectOne(`${BASE}/patients`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });

  it('reads a patient record', () => {
    api.get('p1').subscribe();
    const req = http.expectOne(`${BASE}/patients/p1`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('updates a patient', () => {
    api.update('p1', { chronic: 'Diabetes' }).subscribe();
    const req = http.expectOne(`${BASE}/patients/p1`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ chronic: 'Diabetes' });
    req.flush({});
  });

  it('lists the family for a patient', () => {
    api.family('p1').subscribe();
    const req = http.expectOne(`${BASE}/patients/p1/family`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('merges two patients', () => {
    api.merge('keep1', 'merge1').subscribe();
    const req = http.expectOne(`${BASE}/patients/merge`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ keepId: 'keep1', mergeId: 'merge1' });
    req.flush({});
  });
});
