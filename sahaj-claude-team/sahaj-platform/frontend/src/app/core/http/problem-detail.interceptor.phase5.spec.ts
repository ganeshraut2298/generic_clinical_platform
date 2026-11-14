import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiError } from './problem-detail';
import { problemDetailInterceptor } from './problem-detail.interceptor';

/** opd.yaml 0.6.0: emr-record-held, emr-not-ready and ipd-order-not-placed carry an extension member. */
describe('problemDetailInterceptor, problem extensions (SAH-51-4, SAH-51-1, SAH-67-1)', () => {
  let http: HttpClient;
  let controller: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()] });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  function failWith(body: object): ApiError {
    let caught: unknown;
    http.get('/x').subscribe({ error: (e: unknown) => (caught = e) });
    controller.expectOne('/x').flush(body, { status: 409, statusText: 'Conflict' });
    return caught as ApiError;
  }

  it('keeps the holder name of emr-record-held', () => {
    const e = failWith({ type: 'https://sahaj.in/problems/emr-record-held', title: 'Held', status: 409, holderName: 'Rao' });
    expect(e.problem.holderName).toBe('Rao');
  });

  it('keeps the blockers of emr-not-ready and the rows of ipd-order-not-placed', () => {
    const blockers = [{ key: 'STILL_NEEDED', message: 'Still needed: diagnosis.', sectionKeys: ['diagnosis'] }];
    expect(failWith({ type: 'https://sahaj.in/problems/emr-not-ready', title: 'x', status: 409, blockers }).problem.blockers).toEqual(blockers);
    const rows = [{ rowId: 'o1', outcome: 'BLOCKED' }];
    expect(failWith({ type: 'https://sahaj.in/problems/ipd-order-not-placed', title: 'x', status: 409, rows }).problem.rows).toEqual(rows);
  });

  it('ignores an extension of the wrong shape', () => {
    const e = failWith({ type: 'https://sahaj.in/problems/emr-record-held', title: 'x', status: 409, holderName: 7, blockers: 'no', rows: {} });
    expect(e.problem.holderName).toBeUndefined();
    expect(e.problem.blockers).toBeUndefined();
    expect(e.problem.rows).toBeUndefined();
  });
});
