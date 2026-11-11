import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PackageList } from '../../ipd/ipd.model';
import { IpdAccess } from './ipd-access';
import { PackagesStore } from './packages.store';

const URL = '/api/v1/ipd/packages';
const LIST: PackageList = {
  items: [{ id: 'p1', name: 'Appendicectomy', pricePaise: 4500000, includedDays: 3 }],
};

describe('PackagesStore', () => {
  let http: HttpTestingController;
  let store: PackagesStore;
  let access: IpdAccess;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        PackagesStore,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    access = TestBed.inject(IpdAccess);
    store = TestBed.inject(PackagesStore);
  });
  afterEach(() => http.verify());

  const refuse = (kind: string) =>
    http
      .expectOne(URL)
      .flush(
        {
          type: `https://sahaj.example/problems/${kind}`,
          title: 'Refused',
          status: 403,
          detail: 'Not allowed.',
        },
        { status: 403, statusText: 'Forbidden' },
      );

  it('reads nothing while IPD is not known or is off', async () => {
    TestBed.tick();
    access.state.set('off');
    TestBed.tick();
    http.expectNone(URL);
    expect(store.items()).toBeNull();
    expect(store.pending()).toBe(true);
  });

  it.each(['trial', 'on', 'readonly'] as const)('reads once when IPD is %s', (state) => {
    access.state.set(state);
    TestBed.tick();
    http.expectOne(URL).flush(LIST);
    TestBed.tick();
    http.expectNone(URL);
    expect(store.items()).toEqual(LIST.items);
    expect(store.pending()).toBe(false);
    expect(store.error()).toBeNull();
  });

  it('keeps an empty answer as an empty list, not as not-loaded', () => {
    access.state.set('on');
    TestBed.tick();
    http.expectOne(URL).flush({ items: [] });
    expect(store.items()).toEqual([]);
    expect(store.pending()).toBe(false);
  });

  it('keeps the last list on a 5xx and clears the error when a later read works', () => {
    access.state.set('on');
    TestBed.tick();
    http.expectOne(URL).flush(LIST);
    store.load();
    http
      .expectOne(URL)
      .flush(
        { type: 'about:blank', title: 'Error', status: 500 },
        { status: 500, statusText: 'x' },
      );
    expect(store.items()).toEqual(LIST.items);
    expect(store.error()).toBe('We could not load the packages. Try again.');
    expect(store.moduleOff()).toBe(false);
    store.load();
    http.expectOne(URL).flush(LIST);
    expect(store.error()).toBeNull();
  });

  it('keeps the last list on a network failure', () => {
    access.state.set('on');
    TestBed.tick();
    http.expectOne(URL).flush(LIST);
    store.load();
    http.expectOne(URL).error(new ProgressEvent('error'));
    expect(store.items()).toEqual(LIST.items);
    expect(store.error()).not.toBeNull();
  });

  it('clears the list on a 403 and says so', () => {
    access.state.set('on');
    TestBed.tick();
    http.expectOne(URL).flush(LIST);
    store.load();
    refuse('forbidden');
    expect(store.items()).toBeNull();
    expect(store.moduleOff()).toBe(false);
    expect(store.error()).toBe('Not allowed.');
  });

  it('uses the fixed text for module-off', () => {
    access.state.set('on');
    TestBed.tick();
    http.expectOne(URL).flush(LIST);
    store.load();
    refuse('module-off');
    expect(store.items()).toBeNull();
    expect(store.moduleOff()).toBe(true);
    expect(store.error()).toBe('Packages are not available because the module is off.');
    expect(store.error()).not.toContain('Try again');
  });

  it('sends one read when load is called twice in the same instant', () => {
    access.state.set('on');
    TestBed.tick();
    http.expectOne(URL).flush(LIST);
    store.load();
    store.load();
    const req = http.expectOne(URL);
    expect(store.loading()).toBe(true);
    req.flush(LIST);
    expect(store.loading()).toBe(false);
  });

  it('allows Try again after a failed first read', () => {
    access.state.set('on');
    TestBed.tick();
    http
      .expectOne(URL)
      .flush(
        { type: 'about:blank', title: 'Error', status: 500 },
        { status: 500, statusText: 'x' },
      );
    expect(store.loading()).toBe(false);
    store.load();
    http.expectOne(URL).flush(LIST);
    expect(store.items()).toEqual(LIST.items);
  });

  it('recovers from a 403 when Try again works', () => {
    access.state.set('on');
    TestBed.tick();
    refuse('forbidden');
    store.load();
    http.expectOne(URL).flush(LIST);
    expect(store.moduleOff()).toBe(false);
    expect(store.items()).toEqual(LIST.items);
  });
});
