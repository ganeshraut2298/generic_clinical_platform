import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ColleagueTriesNoticeStore, NOTICE_RETRY_MS } from './colleague-tries-notice.store';
import { ColleagueTriesNotice } from './pin.model';

const URL = '/api/v1/platform/me/pin/colleague-tries';
const NOTICE: ColleagueTriesNotice = {
  unseenCount: 3,
  latestAt: '2026-11-07T05:35:12.123456Z',
  state: 'SET',
  lockedUntil: null,
  notSetReason: null,
};

describe('ColleagueTriesNoticeStore', () => {
  let store: ColleagueTriesNoticeStore;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    snack = vi.fn();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: MatSnackBar, useValue: { open: snack } }],
    });
    store = TestBed.inject(ColleagueTriesNoticeStore);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('shows nothing until it is loaded, and nothing for zero tries', () => {
    expect(store.visible()).toBe(false);
    store.load();
    http.expectOne(URL).flush({ ...NOTICE, unseenCount: 0, latestAt: null });
    expect(store.visible()).toBe(false);
    expect(store.notice()).toBeNull();
  });

  it('keeps the notice when there are unseen tries', () => {
    store.load();
    http.expectOne(URL).flush(NOTICE);
    expect(store.visible()).toBe(true);
    expect(store.notice()).toEqual(NOTICE);
  });

  it('does not start a second load while one runs', () => {
    store.load();
    store.load();
    http.expectOne(URL).flush(NOTICE);
  });

  it('a failed load shows nothing and loses nothing', () => {
    store.load(1_000);
    http.expectOne(URL).flush({}, { status: 500, statusText: 'Server Error' });
    expect(store.visible()).toBe(false);
  });

  it('retries after a failure only once the minute has passed', () => {
    store.load(1_000);
    http.expectOne(URL).flush({}, { status: 500, statusText: 'Server Error' });
    store.retryAfterFailure(1_000 + NOTICE_RETRY_MS - 1);
    http.expectNone(URL);
    store.retryAfterFailure(1_000 + NOTICE_RETRY_MS);
    http.expectOne(URL).flush(NOTICE);
    expect(store.visible()).toBe(true);
  });

  it('does not retry after a good load', () => {
    store.load(1_000);
    http.expectOne(URL).flush(NOTICE);
    store.retryAfterFailure(1_000 + 10 * NOTICE_RETRY_MS);
    http.expectNone(URL);
  });

  it('"Got it" closes at once and posts latestAt once, also on a double tap', () => {
    store.load();
    http.expectOne(URL).flush(NOTICE);
    store.dismiss();
    store.dismiss();
    expect(store.visible()).toBe(false);
    const req = http.expectOne(`${URL}/seen`);
    expect(req.request.body).toEqual({ seenThrough: NOTICE.latestAt });
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('a failed post brings the banner back with a toast, and a second "Got it" can post again (R38-4)', () => {
    store.load();
    http.expectOne(URL).flush(NOTICE);
    store.dismiss();
    expect(store.visible()).toBe(false);
    http.expectOne(`${URL}/seen`).flush({}, { status: 500, statusText: 'Server Error' });
    expect(store.visible()).toBe(true);
    expect(store.notice()).toEqual(NOTICE);
    expect(snack).toHaveBeenCalledTimes(1);
    expect(snack.mock.calls[0][0]).toBe('We could not save that. Try again.');
    store.retryAfterFailure(Date.now() + 10 * NOTICE_RETRY_MS);
    http.expectNone(URL);
    store.dismiss();
    expect(store.visible()).toBe(false);
    http.expectOne(`${URL}/seen`).flush(null, { status: 204, statusText: 'No Content' });
    expect(snack).toHaveBeenCalledTimes(1);
  });

  it('a failed post does not overwrite a newer notice that was loaded meanwhile', () => {
    store.load();
    http.expectOne(URL).flush(NOTICE);
    store.dismiss();
    const newer = { ...NOTICE, unseenCount: 5 };
    store.notice.set(newer);
    http.expectOne(`${URL}/seen`).flush({}, { status: 500, statusText: 'Server Error' });
    expect(store.notice()).toEqual(newer);
  });

  it('follows a new PIN state from the Set PIN sheet but keeps the tries until "Got it"', () => {
    store.load();
    http.expectOne(URL).flush({ ...NOTICE, state: 'NOT_SET', notSetReason: 'TOO_MANY_LOCKS' });
    store.applyStatus({ state: 'SET', lockedUntil: null, notSetReason: null });
    expect(store.notice()).toMatchObject({ unseenCount: 3, state: 'SET', notSetReason: null });
  });

  it('forgets everything on reset', () => {
    store.load();
    http.expectOne(URL).flush(NOTICE);
    store.reset();
    expect(store.notice()).toBeNull();
  });
});
