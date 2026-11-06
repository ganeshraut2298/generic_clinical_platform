import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AdminSessionStore } from '../auth/admin-session.store';
import { OrganizationsPlaceholderPage } from '../organizations/organizations-placeholder.page';
import { AdminShellComponent, initialsOf } from './admin-shell.component';

describe('initialsOf', () => {
  it('takes the first letters of the first two words', () => {
    expect(initialsOf('Neha Rao')).toBe('NR');
    expect(initialsOf('  dev   admin  kumar ')).toBe('DA');
    expect(initialsOf('Dev')).toBe('D');
    expect(initialsOf('')).toBe('?');
  });
});

describe('AdminShellComponent', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          {
            path: 'admin',
            component: AdminShellComponent,
            children: [
              {
                path: 'clinics',
                component: OrganizationsPlaceholderPage,
                data: { crumb: 'Organizations' },
              },
            ],
          },
          { path: 'admin/sign-in', children: [] },
        ]),
      ],
    });
    TestBed.inject(AdminSessionStore).set({
      accessToken: 'jwt',
      expiresInSeconds: 600,
      admin: { id: 'id-1', name: 'Neha Rao', role: 'PLATFORM_ADMIN', roleLabel: 'Platform admin' },
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/admin/clinics');
    harness.detectChanges();
  });

  afterEach(() => http.verify());

  const root = () => harness.routeNativeElement as HTMLElement;

  it('shows the admin chrome of the mockup (criterion 12)', () => {
    const text = root().textContent ?? '';
    expect(text).toContain('Sahaj Admin');
    expect(text).toContain('Platform Management');
    expect(text).toContain('Sahaj platform');
    expect(text).toContain('All organizations');
    expect(text).toContain('Platform view: counts only. Patient records stay inside each organization.');
    expect(text).toContain('Collapse menu');
    expect(root().querySelector('.av')!.textContent).toBe('NR');
    expect(root().querySelector('button[aria-label="Your profile"]')).not.toBeNull();
  });

  it('has Organizations as the first sidebar item, pointing to /admin/clinics', () => {
    const items = root().querySelectorAll<HTMLAnchorElement>('.adm-nav a');
    expect(items.length).toBe(1);
    expect(items[0].textContent).toContain('Organizations');
    expect(items[0].getAttribute('href')).toBe('/admin/clinics');
    expect(items[0].getAttribute('aria-current')).toBe('page');
  });

  it('shows the breadcrumb and the placeholder page (criterion 13)', () => {
    const crumb = root().querySelector('nav[aria-label="Breadcrumb"]')!;
    expect(Array.from(crumb.querySelectorAll('span')).map((s) => s.textContent)).toEqual([
      'Platform Management',
      '›',
      'Organizations',
    ]);
    expect(root().textContent).toContain('Organizations coming in the next release');
  });

  it('collapses and expands the menu', () => {
    const toggle = root().querySelector<HTMLButtonElement>('.adm-collapse')!;
    toggle.click();
    harness.detectChanges();
    expect(root().querySelector('.adm-shell')!.classList.contains('collapsed')).toBe(true);
    expect(toggle.getAttribute('aria-label')).toBe('Expand menu');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click();
    harness.detectChanges();
    expect(root().querySelector('.adm-shell')!.classList.contains('collapsed')).toBe(false);
  });

  it('signs out through the profile menu and opens /admin/sign-in (criterion 15)', async () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    root().querySelector<HTMLButtonElement>('button[aria-label="Your profile"]')!.click();
    harness.detectChanges();
    await harness.fixture.whenStable();

    const panel = document.querySelector('.mat-mdc-menu-panel')!;
    expect(panel.textContent).toContain('Neha Rao');
    expect(panel.textContent).toContain('Platform admin · Sahaj team');
    const signOut = Array.from(panel.querySelectorAll<HTMLButtonElement>('button')).find((b) =>
      b.textContent?.includes('Sign out'),
    )!;
    signOut.click();

    const req = http.expectOne('/api/v1/admin/auth/sign-out');
    expect(req.request.method).toBe('POST');
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(TestBed.inject(AdminSessionStore).isAuthenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/admin/sign-in']);
  });
});
