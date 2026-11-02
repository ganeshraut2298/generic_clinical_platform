import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { QueueApi } from './queue.api';

const BASE = '/api/v1/opd';

describe('QueueApi', () => {
  let api: QueueApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(QueueApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads today without mine by default', () => {
    api.today().subscribe();
    const req = http.expectOne(`${BASE}/visits/today`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys().length).toBe(0);
    req.flush([]);
  });

  it('loads today with mine=true', () => {
    api.today(true).subscribe();
    const req = http.expectOne((r) => r.url === `${BASE}/visits/today` && r.params.get('mine') === 'true');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('issues a token', () => {
    const body = { patientId: 'p1', doctorMembershipId: 'd1' };
    api.create(body).subscribe();
    const req = http.expectOne(`${BASE}/visits`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });

  it('moves a visit to a new stage', () => {
    api.moveStage('v1', 'VITALS').subscribe();
    const req = http.expectOne(`${BASE}/visits/v1/stage`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ stage: 'VITALS' });
    req.flush({});
  });
});
