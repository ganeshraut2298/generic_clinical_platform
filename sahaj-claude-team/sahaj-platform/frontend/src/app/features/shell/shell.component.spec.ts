import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { SetPinSheetComponent } from '../pin/set-pin-sheet.component';
import { Subject } from 'rxjs';
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

  async function open(
    options: { collapsed?: boolean; name?: string; menuMode?: 'simple' | 'advanced'; menu?: string[] } = {},
  ) {
    const me: Me = { ...ME, name: options.name ?? ME.name };
    authLogout = vi.fn().mockReturnValue({ subscribe: (fn?: () => void) => fn?.() });
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'shell', component: ShellComponent, children: [] },
          { path: 'login', children: [] },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => me, menu: () => options.menu ?? ME.menu, sidebarCollapsed: () => options.collapsed ?? false, menuMode: () => options.menuMode ?? 'advanced', language: () => 'en', theme: () => 'light', updatePreferences: vi.fn(), clear: vi.fn() } },
        { provide: AuthService, useValue: { logout: authLogout } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/shell');
    http.expectOne('/i18n/en.json').flush({
      'nav.today': 'Today',
      'nav.queue': 'Queue',
      'nav.patients': 'Patients',
      'nav.emergency': 'Emergency',
      'nav.emergencyCount': 'in the queue',
      'nav.ipd': 'Beds and admissions',
    });
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

  describe('profile chip', () => {
    const chipText = (el: HTMLElement) =>
      Array.from(el.querySelectorAll('.user-menu > *')).map((e) => e.textContent?.trim());

    it('shows the name over the role', async () => {
      const el = await open();
      expect(chipText(el)).toEqual(['Sr. Anjali', 'Nurse']);
    });

    it('shows the role once when the name is only the role word', async () => {
      const el = await open({ name: 'Nurse' });
      expect(chipText(el)).toEqual(['Nurse']);
    });

    it('shows the role once when the name is missing', async () => {
      const el = await open({ name: '' });
      expect(chipText(el)).toEqual(['Nurse']);
    });
  });

  describe('sidebar', () => {
    const shellEl = (el: HTMLElement) => el.querySelector('.shell') as HTMLElement;

    it('gives every menu link a name and a tooltip on the icon rail', async () => {
      const el = await open({ collapsed: true });
      expect(shellEl(el).classList).toContain('collapsed');
      const links = Array.from(el.querySelectorAll('.nav-item')) as HTMLElement[];
      expect(links.map((a) => a.getAttribute('aria-label'))).toEqual(['Today', 'Queue', 'Patients']);
      expect(links.map((a) => a.getAttribute('title'))).toEqual(['Today', 'Queue', 'Patients']);
    });

    it('keeps names but no tooltips when the sidebar is wide', async () => {
      const el = await open();
      const link = el.querySelector('.nav-item') as HTMLElement;
      expect(shellEl(el).classList).not.toContain('collapsed');
      expect(link.getAttribute('aria-label')).toBe('Today');
      expect(link.getAttribute('title')).toBeNull();
    });

    it('closes a group when its heading is clicked on the wide sidebar', async () => {
      const el = await open();
      (el.querySelector('.group-head') as HTMLButtonElement).click();
      harness.detectChanges();
      expect(el.querySelectorAll('.nav-item').length).toBe(1);
    });

    it('keeps every group open on the icon rail, which has no headings to reopen them', async () => {
      const el = await open({ collapsed: true });
      (el.querySelector('.group-head') as HTMLButtonElement).click();
      harness.detectChanges();
      expect(el.querySelectorAll('.nav-item').length).toBe(3);
    });
  });

  describe('Emergency menu item (SAH-28)', () => {
    const SUMMARY = '/api/v1/ipd/emergency/summary';
    const MENU = ['today', 'queue', 'emergency', 'ipd'];
    const item = (el: HTMLElement) =>
      Array.from(el.querySelectorAll<HTMLElement>('.nav-item')).find((a) => a.querySelector('.label')!.textContent === 'Emergency')!;

    afterEach(() => vi.useRealTimers());

    it('shows the item with the siren icon before Beds and admissions, linking to /app/emergency', async () => {
      const el = await open({ menu: MENU });
      http.expectOne(SUMMARY).flush({ writable: true, queue: 0, overTarget: 0 });
      const labels = Array.from(el.querySelectorAll('.nav-item .label')).map((e) => e.textContent);
      expect(labels).toEqual(['Today', 'Queue', 'Emergency', 'Beds and admissions']);
      expect(item(el).getAttribute('href')).toBe('/app/emergency');
      expect(item(el).querySelector('app-icon svg path')!.getAttribute('d')).toContain('M7 18v-6a5 5 0 0 1 10 0v6');
    });

    it('shows the number of cases in the queue as a badge, and says it to screen readers', async () => {
      const el = await open({ menu: MENU });
      http.expectOne(SUMMARY).flush({ writable: true, queue: 3, overTarget: 1 });
      harness.detectChanges();
      expect(item(el).querySelector('.badge')!.textContent).toBe('3');
      expect(item(el).getAttribute('aria-label')).toBe('Emergency, 3 in the queue');
    });

    it('shows no badge when the queue is empty', async () => {
      const el = await open({ menu: MENU });
      http.expectOne(SUMMARY).flush({ writable: true, queue: 0, overTarget: 0 });
      harness.detectChanges();
      expect(item(el).querySelector('.badge')).toBeNull();
      expect(item(el).getAttribute('aria-label')).toBe('Emergency');
    });

    it('asks for the summary every 60 seconds and follows the count', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      const el = await open({ menu: MENU });
      http.expectOne(SUMMARY).flush({ writable: true, queue: 1, overTarget: 0 });
      vi.advanceTimersByTime(60_000);
      http.expectOne(SUMMARY).flush({ writable: true, queue: 6, overTarget: 2 });
      harness.detectChanges();
      expect(item(el).querySelector('.badge')!.textContent).toBe('6');
    });

    it('asks for nothing when the menu has no Emergency item', async () => {
      const el = await open();
      http.expectNone(SUMMARY);
      expect(Array.from(el.querySelectorAll('.nav-item .label')).map((e) => e.textContent)).not.toContain('Emergency');
    });
  });

  describe('My PIN (SAH-31, ADR-010 D12)', () => {
    async function openUserMenu(el: HTMLElement): Promise<HTMLButtonElement | null> {
      el.querySelector<HTMLButtonElement>('.user-menu')!.click();
      harness.detectChanges();
      await harness.fixture.whenStable();
      harness.detectChanges();
      return document.querySelector<HTMLButtonElement>('[data-act=my-pin]');
    }

    afterEach(() => document.querySelectorAll('.cdk-overlay-container').forEach((o) => (o.innerHTML = '')));

    it('is in the account menu for a role without the Team page (no config menu item)', async () => {
      const el = await open({ menu: ['today', 'queue'] });
      const item = await openUserMenu(el);
      expect(item).not.toBeNull();
      expect(item!.textContent!.trim()).toBe('My PIN');
    });

    it('opens the Set PIN sheet from the menu item', async () => {
      const el = await open({ menu: ['today'] });
      const dialog = vi
        .spyOn(TestBed.inject(MatDialog), 'open')
        .mockReturnValue({ afterClosed: () => new Subject<unknown>() } as never);
      const item = await openUserMenu(el);
      item!.click();
      expect(dialog).toHaveBeenCalledTimes(1);
      expect(dialog.mock.calls[0][0]).toBe(SetPinSheetComponent);
      expect((dialog.mock.calls[0][1] as { ariaLabelledBy: string }).ariaLabelledBy).toBe('sp-title');
    });

    it('gives the focus back to the account button when the sheet closes (the menu item is gone)', async () => {
      const el = await open({ menu: ['today'] });
      const closed = new Subject<unknown>();
      vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => closed } as never);
      const item = await openUserMenu(el);
      item!.click();
      harness.detectChanges();
      await harness.fixture.whenStable();
      (document.activeElement as HTMLElement | null)?.blur();
      closed.next(undefined);
      closed.complete();
      expect(document.activeElement).toBe(el.querySelector('.user-menu'));
    });

    it('is not in the menu for a role that has the Team page (it sets its PIN on its Team row)', async () => {
      const el = await open({ menu: ['today', 'config'] });
      expect(await openUserMenu(el)).toBeNull();
    });
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
