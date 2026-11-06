import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IpdApi } from './ipd.api';

const BASE = '/api/v1/ipd';

describe('IpdApi', () => {
  let api: IpdApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(IpdApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  function expectCall(method: string, path: string, body: unknown) {
    const req = http.expectOne(`${BASE}${path}`);
    expect(req.request.method).toBe(method);
    expect(req.request.body).toEqual(body);
    req.flush({});
  }

  it('calls the contract paths', () => {
    api.layout().subscribe();
    expectCall('GET', '/layout', null);
    api.addWard('ICU').subscribe();
    expectCall('POST', '/wards', { name: 'ICU' });
    api.addRoom('w1').subscribe();
    expectCall('POST', '/wards/w1/rooms', null);
    api.addBed('w1').subscribe();
    expectCall('POST', '/beds', { wardId: 'w1' });
    api.addBed('w1', 'r1').subscribe();
    expectCall('POST', '/beds', { wardId: 'w1', roomId: 'r1' });
    api.setBedCategory('b1', 'icu').subscribe();
    expectCall('PUT', '/beds/b1', { categoryKey: 'icu' });
    api.removeBed('b1').subscribe();
    expectCall('DELETE', '/beds/b1', null);
    api.setRoomLevel(false).subscribe();
    expectCall('PUT', '/room-level', { on: false });
    api.setCategory('pvt', 250000, 1.5).subscribe();
    expectCall('PUT', '/categories/pvt', { ratePaise: 250000, multiplier: 1.5 });
    api.setCharges({ bedDayCut: '24h', nursingPaise: 30000, visitPaise: 50000 }).subscribe();
    expectCall('PUT', '/charges', { bedDayCut: '24h', nursingPaise: 30000, visitPaise: 50000 });
  });
});
