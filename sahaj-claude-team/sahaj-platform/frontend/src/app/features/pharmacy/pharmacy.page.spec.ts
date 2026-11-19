import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from './pharmacy-context.store';
import { PharmacyPage } from './pharmacy.page';
import { OWNER_CONTEXT, PHARMACIST_CONTEXT, STORE_USER_CONTEXT } from './pharmacy.fixtures';
import { PharmacyContext } from './pharmacy.model';

const URL = '/api/v1/pharmacy/context';

describe('PharmacyPage', () => {
  let fixture: ComponentFixture<PharmacyPage>;
  let http: HttpTestingController;

  function create(): HTMLElement {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PharmacyPage);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function openWith(context: PharmacyContext): Promise<HTMLElement> {
    const el = create();
    http.expectOne(URL).flush(context);
    await settle();
    return el;
  }

  const q = (el: HTMLElement, id: string) => el.querySelector(`[data-testid="${id}"]`);

  afterEach(() => http.verify());

  it('shows a loading state until the server has answered', () => {
    const el = create();
    expect(q(el, 'pharmacy-loading')).toBeTruthy();
    expect(q(el, 'tab-inventory-setup')).toBeNull();
    http.expectOne(URL).flush(OWNER_CONTEXT);
  });

  it('shows the Inventory setup link to the owner and no other link (only released screens)', async () => {
    const el = await openWith(OWNER_CONTEXT);
    const links = Array.from(el.querySelectorAll('nav[aria-label="Pharmacy and inventory"] a')).map((t) =>
      t.textContent?.trim(),
    );
    expect(links).toEqual(['Inventory setup']);
    expect(el.querySelectorAll('[role="tab"], [role="tablist"]').length).toBe(0);
    expect(el.textContent).not.toContain('coming soon');
  });

  it('shows "<name> · <role label>" beside the working store (mockup 16, OQ-78-5)', async () => {
    const el = await openWith(PHARMACIST_CONTEXT);
    expect(q(el, 'caller-label')?.textContent?.trim()).toBe('Test Pharmacist One · Pharmacist');
    expect(el.querySelector('[data-testid="working-store"]')?.contains(q(el, 'caller-label'))).toBe(true);
  });

  it('shows a pharmacist the Inventory setup tab (view access)', async () => {
    const el = await openWith(PHARMACIST_CONTEXT);
    expect(q(el, 'tab-inventory-setup')).toBeTruthy();
  });

  it('shows no Inventory setup tab to a store user, only the working store', async () => {
    const el = await openWith(STORE_USER_CONTEXT);
    expect(q(el, 'tab-inventory-setup')).toBeNull();
    expect(q(el, 'working-store-select')).toBeTruthy();
    expect(q(el, 'must-pick')).toBeNull();
  });

  it('says "You are not mapped to a store" for an owner with no mapping, instead of the select', async () => {
    const el = await openWith(OWNER_CONTEXT);
    expect(q(el, 'not-mapped')?.textContent).toContain('You are not mapped to a store. Ask your owner.');
    expect(q(el, 'working-store-select')).toBeNull();
    expect(q(el, 'tab-inventory-setup')).toBeTruthy();
  });

  it('asks a person with several stores to pick a working store', async () => {
    const el = await openWith(PHARMACIST_CONTEXT);
    expect(q(el, 'must-pick')).toBeTruthy();
    expect(q(el, 'working-store-select')).toBeTruthy();
  });

  it('saves the picked working store and then drops the prompt', async () => {
    const el = await openWith(PHARMACIST_CONTEXT);
    (fixture.componentInstance as unknown as { pick(id: string): void }).pick('store-opd');
    const put = http.expectOne(`${URL}/working-store`);
    expect(put.request.body).toEqual({ storeId: 'store-opd' });
    put.flush({ ...PHARMACIST_CONTEXT, workingStoreId: 'store-opd', mustPickWorkingStore: false });
    await settle();
    expect(q(el, 'must-pick')).toBeNull();
    expect(TestBed.inject(PharmacyContextStore).workingStore()?.name).toBe('Test OPD Store');
  });

  it('shows the server sentence when the clinic has the module off', async () => {
    const el = create();
    http.expectOne(URL).flush(
      {
        type: 'https://sahaj.in/problems/module-off',
        title: 'Module off',
        status: 403,
        detail: 'Pharmacy & Inventory is not switched on for your clinic.',
      },
      { status: 403, statusText: 'Forbidden' },
    );
    await settle();
    expect(q(el, 'pharmacy-no-access')?.textContent).toContain(
      'Pharmacy & Inventory is not switched on for your clinic.',
    );
  });

  it('says "You do not have access" to a person with no store and no role', async () => {
    const el = create();
    http.expectOne(URL).flush(
      {
        type: 'https://sahaj.in/problems/forbidden',
        title: 'Not allowed',
        status: 403,
        detail: 'You do not have access to Pharmacy & Inventory. Ask your owner.',
      },
      { status: 403, statusText: 'Forbidden' },
    );
    await settle();
    expect(q(el, 'pharmacy-no-access')?.textContent).toContain(
      'You do not have access to Pharmacy & Inventory. Ask your owner.',
    );
    expect(q(el, 'tab-inventory-setup')).toBeNull();
  });

  it('shows "Could not load. Try again." with a Try again button and reads again on tap', async () => {
    const el = create();
    http.expectOne(URL).error(new ProgressEvent('error'));
    await settle();
    expect(q(el, 'pharmacy-error')?.textContent).toContain('Could not load. Try again.');
    const retry = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Try again'));
    retry?.click();
    http.expectOne(URL).flush(OWNER_CONTEXT);
    await settle();
    expect(q(el, 'pharmacy-error')).toBeNull();
    expect(q(el, 'tab-inventory-setup')).toBeTruthy();
  });

  it('forgets the context when the page is left', async () => {
    await openWith(OWNER_CONTEXT);
    fixture.destroy();
    expect(TestBed.inject(PharmacyContextStore).context()).toBeNull();
  });
});
