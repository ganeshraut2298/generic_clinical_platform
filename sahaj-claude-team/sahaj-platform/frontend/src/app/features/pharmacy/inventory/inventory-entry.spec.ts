import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ModuleHomePage } from '../module-home.page';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { PharmacyPage } from '../pharmacy.page';
import { PharmacyContext } from '../pharmacy.model';
import { INVENTORY_OWNER_CONTEXT, READ_ONLY_CONTEXT } from './inventory.fixtures';

const URL = '/api/v1/pharmacy/context';

describe('Inventory entry (SAH-79 work package D)', () => {
  describe('PharmacyContextStore', () => {
    let store: PharmacyContextStore;
    let http: HttpTestingController;

    beforeEach(() => {
      TestBed.configureTestingModule({
        providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
      });
      store = TestBed.inject(PharmacyContextStore);
      http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => http.verify());

    it('reads the Inventory capabilities the server lists, and the store capabilities of the working store', () => {
      store.start();
      http.expectOne(URL).flush(INVENTORY_OWNER_CONTEXT);
      expect(store.canInventory()).toBe(true);
      expect(store.canApprovalsInbox()).toBe(true);
      expect(store.canStockReports()).toBe(true);
      expect(store.canReadStock()).toBe(true);
      expect(store.canAdjustStock()).toBe(true);
      expect(store.isCentralStore()).toBe(true);
      expect(store.canPostOpeningStockAt('store-central')).toBe(true);
    });

    it('lists nothing for a store user who may only read stock', () => {
      store.start();
      http.expectOne(URL).flush(READ_ONLY_CONTEXT);
      expect(store.canInventory()).toBe(true);
      expect(store.canApprovalsInbox()).toBe(false);
      expect(store.canStockReports()).toBe(false);
      expect(store.canAdjustStock()).toBe(false);
      expect(store.canPostOpeningStockAt('store-ward')).toBe(false);
    });

    it('answers false for a store that is not in the person\'s list, and ignores a capability it does not know', () => {
      store.start();
      http.expectOne(URL).flush({ ...INVENTORY_OWNER_CONTEXT, capabilities: ['INVENTORY', 'SOMETHING_LATER'] });
      expect(store.canPostOpeningStockAt('store-unknown')).toBe(false);
      expect(store.canApprovalsInbox()).toBe(false);
    });
  });

  describe('PharmacyPage tabs', () => {
    async function tabs(context: PharmacyContext): Promise<{ labels: (string | undefined)[]; el: HTMLElement }> {
      TestBed.configureTestingModule({
        providers: [
          provideRouter([]),
          provideHttpClient(withInterceptors([problemDetailInterceptor])),
          provideHttpClientTesting(),
        ],
      });
      const http = TestBed.inject(HttpTestingController);
      const fixture = TestBed.createComponent(PharmacyPage);
      fixture.detectChanges();
      http.expectOne(URL).flush(context);
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      return {
        el,
        labels: Array.from(el.querySelectorAll('nav[aria-label="Pharmacy and inventory"] a')).map((t) => t.textContent?.trim()),
      };
    }

    it('shows Inventory setup then Inventory to an owner (the BRD order)', async () => {
      const { labels } = await tabs(INVENTORY_OWNER_CONTEXT);
      expect(labels).toEqual(['Inventory setup', 'Inventory']);
    });

    it('shows only Inventory to a store user with no setup access', async () => {
      const { labels, el } = await tabs(READ_ONLY_CONTEXT);
      expect(labels).toEqual(['Inventory']);
      expect(el.querySelector('[data-testid="tab-inventory"]')).toBeTruthy();
      expect(el.querySelector('[data-testid="tab-inventory-setup"]')).toBeNull();
    });
  });

  describe('ModuleHomePage', () => {
    it('sends a person with no setup tab but with Inventory to Inventory', async () => {
      TestBed.configureTestingModule({
        providers: [
          provideRouter([{ path: 'inventory', children: [] }, { path: '', component: ModuleHomePage }]),
          provideHttpClient(),
          provideHttpClientTesting(),
        ],
      });
      TestBed.inject(PharmacyContextStore).start();
      TestBed.inject(HttpTestingController).expectOne(URL).flush(READ_ONLY_CONTEXT);
      const router = TestBed.inject(Router);
      await router.navigateByUrl('/');
      const fixture = TestBed.createComponent(ModuleHomePage);
      fixture.detectChanges();
      await fixture.whenStable();
      expect(router.url).toBe('/inventory');
    });
  });
});
