import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Route, Router, UrlTree, provideRouter } from '@angular/router';
import { Observable } from 'rxjs';
import { shellGuard } from '../../core/shell/shell.guard';
import { ShellStore } from '../../core/shell/shell.store';
import { NAV_ITEMS, SIMPLE_MENU_KEYS, navItemByKey } from '../../core/shell/nav.config';
import { ROLE_LABELS } from '../../core/platform/platform.model';
import { SHELL_ROUTES } from '../shell/shell.routes';
import { storeStaffLandingGuard } from './pharmacy-landing';

function runGuard(menu: string[], role: string | null = null): true | string {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      {
        provide: ShellStore,
        useValue: { menu: () => menu, me: () => (role === null ? null : { role }) },
      },
    ],
  });
  const result = TestBed.runInInjectionContext(() =>
    storeStaffLandingGuard({} as never, {} as never),
  );
  return result === true ? true : (result as UrlTree).toString();
}

describe('storeStaffLandingGuard (runs on the today route, after /me has loaded)', () => {
  it('lets every role that has Today through, exactly as before', () => {
    expect(runGuard(['today', 'queue', 'patients'])).toBe(true);
    expect(runGuard(['today', 'pharmacy', 'config'])).toBe(true);
  });

  it('sends a Store staff member with a store (pharmacy, no today) to Pharmacy & Inventory', () => {
    expect(runGuard(['pharmacy'])).toBe('/app/pharmacy');
  });

  it('keeps today behaviour for an empty menu, which is also what /me not loaded yet looks like', () => {
    expect(runGuard([])).toBe(true);
  });

  it('keeps today behaviour for a menu with other keys and no Today', () => {
    expect(runGuard(['queue'])).toBe(true);
  });

  it('sends Store staff with no mapped store (empty menu) to Pharmacy & Inventory, not to a broken Today', () => {
    expect(runGuard([], 'STORE_STAFF')).toBe('/app/pharmacy');
  });

  it('sends Store staff to Pharmacy & Inventory whatever the menu', () => {
    expect(runGuard(['pharmacy'], 'STORE_STAFF')).toBe('/app/pharmacy');
    expect(runGuard(['today', 'pharmacy'], 'STORE_STAFF')).toBe('/app/pharmacy');
  });

  it('keeps an empty menu on Today for every role that is not Store staff', () => {
    for (const role of ['OWNER', 'DOCTOR', 'VISITING_DOCTOR', 'NURSE', 'FRONT_DESK', 'BILLING', 'PHARMACIST', 'COUNSELLOR']) {
      expect(runGuard([], role)).toBe(true);
    }
  });
});

describe('shell routes for Pharmacy & Inventory', () => {
  const children: Route[] = SHELL_ROUTES[0].children ?? [];

  it('mounts /app/pharmacy as a lazy route with no menu guard (the page asks the server)', () => {
    const route = children.find((r) => r.path === 'pharmacy');
    expect(route).toBeDefined();
    expect(route?.loadChildren).toBeDefined();
    expect(route?.canActivate).toBeUndefined();
  });

  it('redirects /app to today with a plain path, not a function that reads the menu', () => {
    const redirect = children.find((r) => r.path === '');
    expect(redirect?.pathMatch).toBe('full');
    expect(redirect?.redirectTo).toBe('today');
  });

  it('guards the today route with storeStaffLandingGuard', () => {
    const today = children.find((r) => r.path === 'today');
    expect(today?.canActivate).toEqual([storeStaffLandingGuard]);
  });
});

describe('fresh load of /app (the menu is empty until shellGuard has loaded /me)', () => {
  const children: Route[] = SHELL_ROUTES[0].children ?? [];

  /**
   * Mirrors the real tree: `/app` is guarded by the real shellGuard, which loads /me, and the child
   * routes use the real `''` redirect and `today` guards from SHELL_ROUTES (only the lazy page is
   * left out). The fake store has an empty menu until `load()` is subscribed, like the real one.
   */
  async function freshLoad(menuFromMe: string[], role = 'OWNER'): Promise<string> {
    const meMenu = signal<string[]>([]);
    const loaded = signal(false);
    const store = {
      menu: () => meMenu(),
      me: () => (loaded() ? { role } : null),
      load: () =>
        new Observable<unknown>((subscriber) => {
          meMenu.set(menuFromMe);
          loaded.set(true);
          subscriber.next({});
          subscriber.complete();
        }),
    };
    const redirect = children.find((r) => r.path === '') as Route;
    const today = children.find((r) => r.path === 'today') as Route;
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: 'app',
            canActivate: [shellGuard],
            children: [
              redirect,
              { path: 'today', canActivate: today.canActivate, children: [] },
              { path: 'pharmacy', children: [] },
            ],
          },
        ]),
        { provide: ShellStore, useValue: store },
      ],
    });
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/app');
    return router.url;
  }

  it('lands existing roles (owner, doctor, front desk, nurse, pharmacist) on Today', async () => {
    expect(await freshLoad(['today', 'queue', 'patients', 'config'])).toBe('/app/today');
    expect(await freshLoad(['today', 'queue', 'patients'])).toBe('/app/today');
    expect(await freshLoad(['today', 'pharmacy', 'config'])).toBe('/app/today');
  });

  it('lands Store staff with a mapped store on Pharmacy & Inventory', async () => {
    expect(await freshLoad(['pharmacy'])).toBe('/app/pharmacy');
  });

  it('lands a user who is not Store staff, with an empty menu, on Today, as before this story', async () => {
    expect(await freshLoad([], 'OWNER')).toBe('/app/today');
    expect(await freshLoad([], 'NURSE')).toBe('/app/today');
  });

  it('lands Store staff with no mapped store (empty menu) on Pharmacy & Inventory, not Today', async () => {
    expect(await freshLoad([], 'STORE_STAFF')).toBe('/app/pharmacy');
  });
});

describe('navigation entry', () => {
  it('has one pharmacy entry with the route, a label key and the pill icon', () => {
    const item = navItemByKey('pharmacy');
    expect(item?.route).toBe('pharmacy');
    expect(item?.icon).toBe('pill');
    expect(item?.labelKey).toBe('nav.pharmacy');
    expect(NAV_ITEMS.filter((i) => i.key === 'pharmacy').length).toBe(1);
  });

  it('sits before Staff and leave and Clinic setup', () => {
    const keys = NAV_ITEMS.map((i) => i.key);
    expect(keys.indexOf('pharmacy')).toBeLessThan(keys.indexOf('staff'));
    expect(keys.indexOf('pharmacy')).toBeLessThan(keys.indexOf('config'));
  });

  it('is in both menu modes', () => {
    expect(SIMPLE_MENU_KEYS).toContain('pharmacy');
  });

  it('labels the Store staff role', () => {
    expect(ROLE_LABELS.STORE_STAFF).toBe('Store staff');
  });
});
