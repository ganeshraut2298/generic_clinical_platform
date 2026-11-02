import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService } from '../../core/auth/auth.service';
import { ShellStore } from '../../core/shell/shell.store';
import { Me } from '../../core/shell/shell.model';
import { ShellComponent } from './shell.component';

const ME: Me = {
  userId: 'u1',
  name: 'Sr. Anjali',
  role: 'NURSE',
  clinic: { name: 'Shree Sai Clinic', area: 'Ravet, Pune', brand: 'blue', plan: 'free' },
  menu: ['today', 'queue', 'patients'],
  preferences: { language: 'en', theme: 'light', menuMode: 'advanced', sidebarCollapsed: false },
};

describe('ShellComponent', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let authLogout: ReturnType<typeof vi.fn>;

  async function open() {
    authLogout = vi.fn().mockReturnValue({ subscribe: (fn?: () => void) => fn?.() });
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'shell', component: ShellComponent, children: [] },
          { path: 'login', children: [] },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ME, menu: () => ME.menu, sidebarCollapsed: () => false, menuMode: () => 'advanced', language: () => 'en', theme: () => 'light', updatePreferences: vi.fn(), clear: vi.fn() } },
        { provide: AuthService, useValue: { logout: authLogout } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/shell');
    http.expectOne('/i18n/en.json').flush({ 'nav.today': 'Today', 'nav.queue': 'Queue', 'nav.patients': 'Patients' });
    harness.detectChanges();
    await harness.fixture.whenStable();
    return harness.routeNativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  it('shows only the menu items in /me.menu (a nurse never sees Billing)', async () => {
    const el = await open();
    const labels = Array.from(el.querySelectorAll('.nav-item .label')).map((e) => e.textContent);
    expect(labels).toContain('Today');
    expect(labels).toContain('Queue');
    expect(labels).toContain('Patients');
    expect(labels).not.toContain('Billing');
  });

  it('logs out and clears the shell store', async () => {
    const el = await open();
    const logoutButton = Array.from(el.querySelectorAll('button')).find(
      (b) => b.getAttribute('aria-label') === 'Log out',
    ) as HTMLButtonElement;
    logoutButton.click();
    expect(authLogout).toHaveBeenCalled();
  });
});
