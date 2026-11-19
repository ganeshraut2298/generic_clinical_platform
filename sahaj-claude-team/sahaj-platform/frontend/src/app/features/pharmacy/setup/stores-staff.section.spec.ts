import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import {
  CENTRAL,
  OPD_STORE,
  OWNER_CONTEXT,
  PHARMACIST_CONTEXT,
  PHARMACIST_MAPPING,
  SETTINGS,
  STORE_STAFF_MAPPING,
  WARD_STORE,
  mapping,
  store,
} from '../pharmacy.fixtures';
import { PharmacyContext, Store } from '../pharmacy.model';
import { StoresStaffSection } from './stores-staff.section';

const BASE = '/api/v1/pharmacy';

describe('StoresStaffSection', () => {
  let fixture: ComponentFixture<StoresStaffSection>;
  let http: HttpTestingController;

  async function open(
    context: PharmacyContext,
    stores: Store[],
    staffOfFirst = [mapping(), STORE_STAFF_MAPPING, PHARMACIST_MAPPING],
  ): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(StoresStaffSection);
    fixture.detectChanges();
    http.expectOne(`${BASE}/unit-settings`).flush(SETTINGS);
    http.expectOne(`${BASE}/stores`).flush({
      items: stores,
      centralStoreId: stores.find((s) => s.type === 'CENTRAL')?.id ?? null,
    });
    if (stores.length > 0) {
      http.expectOne(`${BASE}/stores/${stores[0].id}/staff`).flush({ items: staffOfFirst });
    }
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

  function dialogReturns(value: unknown) {
    return vi
      .spyOn(TestBed.inject(MatDialog), 'open')
      .mockReturnValue({ afterClosed: () => of(value) } as never);
  }

  afterEach(() => http.verify());

  it('shows the empty state with one + Store button when the unit has no stores', async () => {
    const el = await open(OWNER_CONTEXT, []);
    expect(text(el, 'stores-empty')).toContain('No stores yet. Create your Central Store to start.');
    expect(el.querySelectorAll('[data-testid="add-store"]').length).toBe(1);
  });

  it('shows no + Store button and a shorter empty line to a view-only person', async () => {
    const el = await open(PHARMACIST_CONTEXT, []);
    expect(text(el, 'stores-empty')).toContain('No stores yet.');
    expect(text(el, 'stores-empty')).not.toContain('Create your Central Store');
    expect(q(el, 'add-store')).toBeNull();
  });

  it('draws the tree with the Central Store first and the first store selected', async () => {
    const el = await open(OWNER_CONTEXT, [CENTRAL, OPD_STORE, WARD_STORE]);
    const nodes = Array.from(el.querySelectorAll('[data-testid="store-tree"] .node'));
    expect(nodes.map((n) => n.querySelector('strong')?.textContent)).toEqual([
      'Test Central Store',
      'Test OPD Store',
      'Test Ward 3B Store',
    ]);
    expect(nodes[0].textContent).toContain('Buys from vendors · issues to all stores');
    expect(nodes[1].textContent).toContain('Issues from Test Central Store · 5 staff');
    expect(nodes[2].textContent).toContain('Ward · consumption');
    expect(nodes[0].getAttribute('aria-current')).toBe('true');
    expect(el.querySelector('#sd-h')?.textContent).toBe('Test Central Store');
  });

  it('says "Buys from vendors" for the Central Store and "Not set" for an empty delegate (WP-A follow-up)', async () => {
    const el = await open(OWNER_CONTEXT, [CENTRAL, OPD_STORE], []);
    expect(text(el, 'f-issues')).toBe('Buys from vendors');
    expect(text(el, 'f-hod')).toBe('Not set');
    expect(text(el, 'f-delegate')).toBe('Not set');
  });

  it('names the icon buttons for a screen reader: "Add store" and "Map staff to this store"', async () => {
    const el = await open(OWNER_CONTEXT, [CENTRAL, OPD_STORE], []);
    expect(q(el, 'add-store')?.getAttribute('aria-label')).toBe('Add store');
    expect(q(el, 'map-staff')?.getAttribute('aria-label')).toBe('Map staff to this store');
  });

  it('shows the detail fields in the order of mockup 16', async () => {
    const el = await open(OWNER_CONTEXT, [OPD_STORE, CENTRAL], []);
    const labels = Array.from(el.querySelectorAll('[data-testid="store-detail"] dt')).map((d) => d.textContent);
    expect(labels).toEqual([
      'Store type',
      'Issues from',
      'Approver (HOD)',
      'Delegate approver',
      'Sale to patients',
      'Consumption entry',
    ]);
    expect(text(el, 'f-type')).toBe('Sub-store');
    expect(text(el, 'f-hod')).toBe('Test Doctor One');
    expect(text(el, 'f-delegate')).toBe('Test Doctor Two · till 30 Nov');
    expect(text(el, 'f-sale')).toBe('No');
    expect(text(el, 'f-consumption')).toBe('No');
  });

  it('draws the staff table with Yes and No columns, Schedule X, other stores and the HOD line', async () => {
    const el = await open(OWNER_CONTEXT, [OPD_STORE]);
    const headers = Array.from(el.querySelectorAll('[data-testid="staff-table"] th')).map((h) =>
      h.textContent?.trim(),
    );
    expect(headers.slice(0, 7)).toEqual([
      'Staff',
      'Store role',
      'Raise indent',
      'Approve',
      'Receive',
      'Consume / return',
      'Other stores',
    ]);
    const hod = text(el, 'staff-map-1')!;
    expect(hod).toContain('HOD, Test OPD Store');
    expect(hod).toContain('Approver');
    const keeper = text(el, 'staff-map-3')!;
    expect(keeper).toContain('Store staff');
    expect(keeper).toContain('Store user');
    const pharmacist = text(el, 'staff-map-4')!;
    expect(pharmacist).toContain('Yes · incl. Schedule X');
    expect(pharmacist).toContain('Test Main Pharmacy');
    expect(text(el, 'staff-map-3')).not.toContain('Schedule X');
  });

  it('gives the owner Change and Remove buttons, but no tick boxes for access', async () => {
    const el = await open(OWNER_CONTEXT, [OPD_STORE]);
    expect(q(el, 'map-staff')).toBeTruthy();
    expect(q(el, 'edit-store')).toBeTruthy();
    expect(el.querySelectorAll('[data-testid="staff-table"] button').length).toBe(6);
    expect(el.querySelectorAll('[data-testid="staff-table"] input[type="checkbox"]').length).toBe(0);
  });

  it('is read-only for a pharmacist: no + Store, Map staff, Edit, Switch off, Change or Remove', async () => {
    const el = await open(PHARMACIST_CONTEXT, [OPD_STORE]);
    expect(q(el, 'add-store')).toBeNull();
    expect(q(el, 'map-staff')).toBeNull();
    expect(q(el, 'edit-store')).toBeNull();
    expect(q(el, 'switch-off')).toBeNull();
    expect(el.querySelectorAll('[data-testid="staff-table"] button').length).toBe(0);
    expect(q(el, 'settings-readonly')).toBeTruthy();
  });

  it('shows "Cannot sign in" and "Needs review" on a row', async () => {
    const el = await open(OWNER_CONTEXT, [OPD_STORE], [
      mapping({ id: 'x1', memberState: 'CANNOT_SIGN_IN', needsReview: true }),
    ]);
    expect(q(el, 'cannot-sign-in')?.textContent).toContain('Cannot sign in');
    expect(q(el, 'needs-review')).toBeTruthy();
  });

  it('shows "No approver set" and its note when the store has no active HOD', async () => {
    const el = await open(OWNER_CONTEXT, [CENTRAL], []);
    expect(q(el, 'no-approver')?.textContent).toContain('No approver set');
    expect(q(el, 'no-approver-note')).toBeTruthy();
  });

  it('says "Delegate ended 30 Nov" on the store row once the delegation is over', async () => {
    const ended = store({ ...OPD_STORE, delegate: { ...OPD_STORE.delegate!, state: 'ENDED' } });
    const el = await open(OWNER_CONTEXT, [CENTRAL, ended], []);
    expect(q(el, 'delegate-ended')?.textContent).toContain('Delegate ended 30 Nov');
  });

  it('shows a locked note when the store has stock', async () => {
    const locked = store({ ...OPD_STORE, hasStock: true, lockedFields: ['TYPE', 'CODE', 'ISSUES_FROM'] });
    const el = await open(OWNER_CONTEXT, [locked], []);
    expect(q(el, 'locked-note')).toBeTruthy();
  });

  it('reads the staff of another store when it is selected', async () => {
    const el = await open(OWNER_CONTEXT, [CENTRAL, OPD_STORE, WARD_STORE], []);
    q(el, 'store-store-ward')?.click();
    http.expectOne(`${BASE}/stores/store-ward/staff`).flush({ items: [] });
    await settle();
    expect(el.querySelector('#sd-h')?.textContent).toBe('Test Ward 3B Store');
    expect(el.textContent).toContain('No staff mapped to this store yet. Tap Map staff');
  });

  it('shows "Could not load. Try again." for the stores and reads them again on tap', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(OWNER_CONTEXT);
    fixture = TestBed.createComponent(StoresStaffSection);
    fixture.detectChanges();
    http.expectOne(`${BASE}/unit-settings`).flush(SETTINGS);
    http.expectOne(`${BASE}/stores`).error(new ProgressEvent('error'));
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    expect(q(el, 'stores-error')?.textContent).toContain('Could not load. Try again.');
    Array.from(el.querySelectorAll('button'))
      .find((b) => b.textContent?.includes('Try again'))
      ?.click();
    http.expectOne(`${BASE}/stores`).flush({ items: [], centralStoreId: null });
    await settle();
    expect(q(el, 'stores-error')).toBeNull();
  });

  describe('changes', () => {
    it('opens the store form for the first store with hasCentral false', async () => {
      const el = await open(OWNER_CONTEXT, []);
      const spy = dialogReturns(null);
      q(el, 'add-store')?.click();
      const data = spy.mock.calls[0][1]?.data as { mode: string; hasCentral: boolean };
      expect(data.mode).toBe('create');
      expect(data.hasCentral).toBe(false);
      http.expectOne(`${BASE}/stores`).flush({ items: [], centralStoreId: null });
    });

    it('after a saved store it reads the stores again, refreshes the context and tells the page', async () => {
      const el = await open(OWNER_CONTEXT, [CENTRAL], []);
      let changed = 0;
      fixture.componentInstance.changed.subscribe(() => changed++);
      dialogReturns(store({ id: 'store-new', name: 'Test New Store', type: 'PHARMACY' }));
      q(el, 'add-store')?.click();
      http.expectOne(`${BASE}/stores`).flush({ items: [CENTRAL, OPD_STORE], centralStoreId: 'store-central' });
      http.expectOne(`${BASE}/context`).flush(OWNER_CONTEXT);
      http.expectOne(`${BASE}/stores/store-central/staff`).flush({ items: [] });
      await settle();
      expect(changed).toBe(1);
    });

    it('switches a store off only after the owner confirms, and shows the server refusal', async () => {
      const el = await open(OWNER_CONTEXT, [CENTRAL, OPD_STORE], []);
      dialogReturns(false);
      q(el, 'switch-off')?.click();
      http.expectNone(`${BASE}/stores/store-central/switch-off`);

      dialogReturns(true);
      q(el, 'switch-off')?.click();
      http.expectOne(`${BASE}/stores/store-central/switch-off`).flush(
        {
          type: 'https://sahaj.in/problems/central-store-in-use',
          title: 'In use',
          status: 409,
          detail: 'Switch off the other stores first.',
        },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();
      expect(q(el, 'action-error')?.textContent).toContain('Switch off the other stores first.');
    });

    it('shows the open-import sentence when a store with an opening stock file is switched off', async () => {
      const el = await open(OWNER_CONTEXT, [CENTRAL, OPD_STORE], []);
      dialogReturns(true);
      q(el, 'switch-off')?.click();
      http.expectOne(`${BASE}/stores/store-central/switch-off`).flush(
        {
          type: 'https://sahaj.in/problems/open-import',
          title: 'Opening stock file is open',
          status: 409,
          detail: 'Finish or reject the opening stock file of this store first.',
        },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();
      expect(q(el, 'action-error')?.textContent).toContain('Finish or reject the opening stock file of this store first.');
    });

    it('removes a person from a store only after the owner confirms', async () => {
      const el = await open(OWNER_CONTEXT, [OPD_STORE], [STORE_STAFF_MAPPING]);
      dialogReturns(true);
      el.querySelector<HTMLElement>('[aria-label="Remove Test Store Staff One from store"]')?.click();
      http.expectOne(`${BASE}/stores/store-opd/staff/map-3/remove`).flush({ ...STORE_STAFF_MAPPING, status: 'REMOVED' });
      http.expectOne(`${BASE}/stores`).flush({ items: [OPD_STORE], centralStoreId: null });
      http.expectOne(`${BASE}/context`).flush(OWNER_CONTEXT);
      http.expectOne(`${BASE}/stores/store-opd/staff`).flush({ items: [] });
      await settle();
      expect(el.textContent).toContain('No staff mapped to this store yet.');
    });

    it('opens the map-staff sheet for the selected store', async () => {
      const el = await open(OWNER_CONTEXT, [OPD_STORE], []);
      const spy = dialogReturns(null);
      q(el, 'map-staff')?.click();
      const data = spy.mock.calls[0][1]?.data as { storeId: string; storeName: string };
      expect(data).toEqual({ storeId: 'store-opd', storeName: 'Test OPD Store' });
    });
  });
});
