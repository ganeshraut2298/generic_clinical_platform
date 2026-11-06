import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { FollowUpsApi } from './followups.api';
import { FollowUpList } from './followups.model';

describe('FollowUpsApi', () => {
  let api: FollowUpsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(FollowUpsApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('calls GET /opd/follow-ups with the status param', () => {
    const body: FollowUpList = {
      counts: { due: 1, missed: 2, upcoming: 3, returned: 4 },
      items: [],
    };
    let result: FollowUpList | undefined;
    api.list('MISSED').subscribe((r) => (result = r));
    const req = http.expectOne((r) => r.url === '/api/v1/opd/follow-ups');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('status')).toBe('MISSED');
    req.flush(body);
    expect(result).toEqual(body);
  });
});
