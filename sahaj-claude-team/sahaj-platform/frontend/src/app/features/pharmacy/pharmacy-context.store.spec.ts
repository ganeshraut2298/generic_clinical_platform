import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from './pharmacy-context.store';
import { OWNER_CONTEXT, PHARMACIST_CONTEXT, STORE_USER_CONTEXT } from './pharmacy.fixtures';

const URL = '/api/v1/pharmacy/context';

describe('PharmacyContextStore', () => {
  let store: PharmacyContextStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    store = TestBed.inject(PharmacyContextStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('is loading until the server has answered', () => {
    store.start();
    expect(store.state()).toBe('loading');
    http.expectOne(URL).flush(OWNER_CONTEXT);
    expect(store.state()).toBe('ready');
    expect(store.unitName()).toBe('Test Clinic Baner');
  });

  it('lets the owner edit setup, and shows no View only pill', () => {
    store.start();
    http.expectOne(URL).flush(OWNER_CONTEXT);
    expect(store.canEditSetup()).toBe(true);
    expect(store.canViewSetup()).toBe(true);
    expect(store.viewOnly()).toBe(false);
  });

  it('gives a pharmacist view-only setup (capabilities decide)', () => {
    store.start();
    http.expectOne(URL).flush(PHARMACIST_CONTEXT);
    expect(store.canEditSetup()).toBe(false);
    expect(store.canViewSetup()).toBe(true);
    expect(store.viewOnly()).toBe(true);
    expect(store.mustPickWorkingStore()).toBe(true);
    expect(store.stores().length).toBe(2);
  });

  it('reads the opening-stock capabilities and the "<name> · <role label>" line from the context', () => {
    store.start();
    http.expectOne(URL).flush(OWNER_CONTEXT);
    expect(store.canUploadOpeningStock()).toBe(true);
    expect(store.canApproveOpeningStock()).toBe(true);
    expect(store.callerLabel()).toBe('Test Owner · Owner');
    store.reset();
    expect(store.callerLabel()).toBe('');
    store.start();
    http.expectOne(URL).flush(PHARMACIST_CONTEXT);
    expect(store.canUploadOpeningStock()).toBe(true);
    expect(store.canApproveOpeningStock()).toBe(false);
    expect(store.callerLabel()).toBe('Test Pharmacist One · Pharmacist');
    store.reset();
    store.start();
    http.expectOne(URL).flush(STORE_USER_CONTEXT);
    expect(store.canUploadOpeningStock()).toBe(false);
  });

  it('hides setup for a store user whose setup access is NONE', () => {
    store.start();
    http.expectOne(URL).flush(STORE_USER_CONTEXT);
    expect(store.canViewSetup()).toBe(false);
    expect(store.workingStore()?.name).toBe('Test OPD Store');
  });

  it('ignores a capability it does not know', () => {
    store.start();
    http.expectOne(URL).flush({ ...OWNER_CONTEXT, capabilities: ['SETUP_VIEW', 'SOMETHING_LATER'] });
    expect(store.canViewSetup()).toBe(true);
    expect(store.canEditSetup()).toBe(false);
  });

  it('is not mapped when the caller has no stores', () => {
    store.start();
    http.expectOne(URL).flush(OWNER_CONTEXT);
    expect(store.notMapped()).toBe(true);
  });

  it('keeps the server sentence of a 403 and shows no access', () => {
    store.start();
    http.expectOne(URL).flush(
      {
        type: 'https://sahaj.in/problems/module-off',
        title: 'Module off',
        status: 403,
        detail: 'Pharmacy & Inventory is not switched on for your clinic.',
      },
      { status: 403, statusText: 'Forbidden' },
    );
    expect(store.state()).toBe('no-access');
    expect(store.message()).toBe('Pharmacy & Inventory is not switched on for your clinic.');
    expect(store.context()).toBeNull();
  });

  it('says the standard line and shows an error when the server cannot be reached', () => {
    store.start();
    http.expectOne(URL).error(new ProgressEvent('error'));
    expect(store.state()).toBe('error');
    expect(store.message()).toBe('Could not load. Try again.');
  });

  it('saves the working store and takes the new context', () => {
    store.start();
    http.expectOne(URL).flush(PHARMACIST_CONTEXT);
    store.pickWorkingStore('store-opd');
    expect(store.picking()).toBe(true);
    const put = http.expectOne(`${URL}/working-store`);
    expect(put.request.body).toEqual({ storeId: 'store-opd' });
    put.flush({ ...PHARMACIST_CONTEXT, workingStoreId: 'store-opd', mustPickWorkingStore: false });
    expect(store.picking()).toBe(false);
    expect(store.workingStore()?.name).toBe('Test OPD Store');
    expect(store.mustPickWorkingStore()).toBe(false);
  });

  it('does not call the server again for the store that is already the working store', () => {
    store.start();
    http.expectOne(URL).flush({ ...PHARMACIST_CONTEXT, workingStoreId: 'store-opd' });
    store.pickWorkingStore('store-opd');
    http.expectNone(`${URL}/working-store`);
  });

  it('on a 404 keeps the working store, says so and reads the context again', () => {
    store.start();
    http.expectOne(URL).flush({ ...PHARMACIST_CONTEXT, workingStoreId: 'store-pharm' });
    store.pickWorkingStore('store-opd');
    http.expectOne(`${URL}/working-store`).flush(
      { type: 'https://sahaj.in/problems/not-found', title: 'Not found', status: 404, detail: 'This record was not found.' },
      { status: 404, statusText: 'Not Found' },
    );
    expect(store.workingStoreId()).toBe('store-pharm');
    expect(store.pickError()).toBe('This record was not found.');
    http.expectOne(URL).flush({ ...PHARMACIST_CONTEXT, workingStoreId: null, mustPickWorkingStore: true });
    expect(store.mustPickWorkingStore()).toBe(true);
  });

  it('forgets everything on reset', () => {
    store.start();
    http.expectOne(URL).flush(OWNER_CONTEXT);
    store.reset();
    expect(store.context()).toBeNull();
    expect(store.state()).toBe('loading');
  });
});
