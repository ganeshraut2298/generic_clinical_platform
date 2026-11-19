import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { ModuleHomePage } from './module-home.page';
import { PharmacyContextStore } from './pharmacy-context.store';
import { OWNER_CONTEXT, PHARMACIST_CONTEXT, STORE_USER_CONTEXT } from './pharmacy.fixtures';
import { PharmacyContext } from './pharmacy.model';

describe('ModuleHomePage', () => {
  async function open(context: PharmacyContext) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'setup', children: [] }, { path: '', component: ModuleHomePage }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    TestBed.inject(PharmacyContextStore).start();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/pharmacy/context').flush(context);
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/');
    const fixture = TestBed.createComponent(ModuleHomePage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { router, el: fixture.nativeElement as HTMLElement };
  }

  it('sends a person who may see Inventory setup to it, the only released tab', async () => {
    const { router } = await open(OWNER_CONTEXT);
    expect(router.url).toBe('/setup');
  });

  it('sends a pharmacist (view access) to Inventory setup too', async () => {
    const { router } = await open(PHARMACIST_CONTEXT);
    expect(router.url).toBe('/setup');
  });

  it('shows a store user the working store and the role, and no setup', async () => {
    const { router, el } = await open(STORE_USER_CONTEXT);
    expect(router.url).toBe('/');
    expect(el.querySelector('[data-testid="home-working-store"]')?.textContent).toContain('Test OPD Store');
    expect(el.textContent).toContain('Store user');
  });
});
