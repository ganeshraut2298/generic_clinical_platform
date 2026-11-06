import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { EmergencyApi } from './emergency.api';
import { EmergencyCaseInput, EmergencySettingsInput } from './emergency.model';

const IPD = '/api/v1/ipd';

describe('EmergencyApi', () => {
  let api: EmergencyApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(EmergencyApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the queue and the summary', () => {
    api.queue().subscribe();
    expect(http.expectOne(`${IPD}/emergency`).request.method).toBe('GET');
    api.summary().subscribe();
    expect(http.expectOne(`${IPD}/emergency/summary`).request.method).toBe('GET');
  });

  it('registers with the whole body and the client key, taking 201 and 200 alike', () => {
    const body: EmergencyCaseInput = {
      clientKey: '6f1c1c4e-3a8a-4a43-9a1b-0c5f8b3c1d11',
      identityUnknown: false,
      name: 'Asha Patil',
      age: 56,
      sex: 'F',
      mobile: null,
      complaint: 'Chest pain',
      level: 2,
    };
    const results: number[] = [];
    api.register(body).subscribe((q) => results.push(q.counts.queue));
    api.register(body).subscribe((q) => results.push(q.counts.queue));
    const [first, repeat] = http.match(`${IPD}/emergency/cases`);
    expect(first.request.method).toBe('POST');
    expect(first.request.body).toEqual(body);
    const queue = { counts: { writable: true, queue: 1, overTarget: 0 } };
    first.flush(queue, { status: 201, statusText: 'Created' });
    repeat.flush(queue, { status: 200, statusText: 'OK' });
    expect(results).toEqual([1, 1]);
  });

  it('calls the four case actions on the case id', () => {
    api.seen('c1').subscribe();
    let req = http.expectOne(`${IPD}/emergency/cases/c1/seen`);
    expect(req.request.method).toBe('POST');
    req.flush({});

    api.setLevel('c1', 3).subscribe();
    req = http.expectOne(`${IPD}/emergency/cases/c1/level`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ level: 3 });
    req.flush({});

    api.release('c1').subscribe();
    req = http.expectOne(`${IPD}/emergency/cases/c1/release`);
    expect(req.request.method).toBe('POST');
    req.flush({});

    api.refer('c1').subscribe();
    req = http.expectOne(`${IPD}/emergency/cases/c1/refer`);
    expect(req.request.method).toBe('POST');
    req.flush({});
  });

  it('reads and saves the whole setting', () => {
    api.settings().subscribe();
    expect(http.expectOne(`${IPD}/emergency-settings`).request.method).toBe('GET');
    const body: EmergencySettingsInput = {
      scale: 5,
      targets3: [5, 30, 120],
      targets5: [0, 10, 30, 60, 120],
      bays: 3,
      unknownPatientRegistration: false,
    };
    api.saveSettings(body).subscribe();
    const req = http.expectOne(`${IPD}/emergency-settings`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });
});
