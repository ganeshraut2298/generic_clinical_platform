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
import { ColleagueTriesNoticeStore } from '../pin/colleague-tries-notice.store';
import { ColleagueTriesNotice } from '../pin/pin.model';
import { ShellComponent } from './shell.component';

const NOTICE_URL = '/api/v1/platform/me/pin/colleague-tries';
const NO_TRIES: ColleagueTriesNotice = {
  unseenCount: 0,
  latestAt: null,
  state: 'SET',
  lockedUntil: null,
  notSetReason: null,
};
const THREE_TRIES: ColleagueTriesNotice = { ...NO_TRIES, unseenCount: 3, latestAt: '2026-11-07T05:35:12.123456Z' };

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
    options: {
      collapsed?: boolean;
      name?: string;
      menuMode?: 'simple' | 'advanced';
      menu?: string[];
      notice?: ColleagueTriesNotice | 'fail' | 'hold';
    } = {},
  ) {
    const me: Me = { ...ME, name: options.name ?? ME.name };
    authLogout = vi.fn().mockReturnValue({ subscribe: (fn?: () => void) => fn?.() });
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'shell', component: ShellComponent, children: [{ path: 'next', children: [] }] },
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
      'nav.secondchecks': 'Second checks',
      'nav.secondchecksCount': 'waiting',
      'nav.ipd': 'Beds and admissions',
    });
    harness.detectChanges();
    await harness.fixture.whenStable();
    // SAH-38: the notice is asked for after the first screen is shown (one separate GET).
    if (options.notice !== 'hold') {
      const notice = http.expectOne(NOTICE_URL);
      if (options.notice === 'fail') {
        notice.flush({}, { status: 500, statusText: 'Server Error' });
      } else {
        notice.flush(options.notice ?? NO_TRIES);
      }
      harness.detectChanges();
    }
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

  describe('Second checks menu item (ADR-023 D13.5)', () => {
    const CHECKS = '/api/v1/opd/emr/chemo-checks';
    const MENU = ['today', 'queue', 'secondchecks', 'patients'];
    const item = (el: HTMLElement) =>
      Array.from(el.querySelectorAll<HTMLElement>('.nav-item')).find((a) => a.querySelector('.label')!.textContent === 'Second checks')!;

    it('shows "Second checks" right after Queue, linking to /app/emr/second-checks, in the simple menu too', async () => {
      const el = await open({ menu: MENU });
      http.expectOne(CHECKS).flush({ items: [], total: 0, page: 0, pageSize: 20 });
      expect(Array.from(el.querySelectorAll('.nav-item .label')).map((n) => n.textContent)).toEqual(['Today', 'Queue', 'Second checks', 'Patients']);
      expect(item(el).getAttribute('href')).toBe('/app/emr/second-checks');
      TestBed.resetTestingModule();
      const simple = await open({ menu: MENU, menuMode: 'simple' });
      http.expectOne(CHECKS).flush({ items: [], total: 0, page: 0, pageSize: 20 });
      expect(Array.from(simple.querySelectorAll('.nav-item .label')).map((n) => n.textContent)).toContain('Second checks');
    });

    it('shows the number waiting as a badge from the total of the checker’s list, and says it to screen readers', async () => {
      const el = await open({ menu: MENU });
      http.expectOne(CHECKS).flush({ items: [], total: 2, page: 0, pageSize: 20 });
      harness.detectChanges();
      expect(item(el).querySelector('.badge')!.textContent).toBe('2');
      expect(item(el).getAttribute('aria-label')).toBe('Second checks, 2 waiting');
    });

    it('shows no badge at 0 and asks for nothing when the menu has no such item', async () => {
      const el = await open({ menu: MENU });
      http.expectOne(CHECKS).flush({ items: [], total: 0, page: 0, pageSize: 20 });
      harness.detectChanges();
      expect(item(el).querySelector('.badge')).toBeNull();
      TestBed.resetTestingModule();
      const none = await open();
      http.expectNone(CHECKS);
      expect(Array.from(none.querySelectorAll('.nav-item .label')).map((n) => n.textContent)).not.toContain('Second checks');
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

  describe('colleague tries notice (SAH-38, ADR-014 D8)', () => {
    const banner = (el: HTMLElement) => el.querySelector<HTMLElement>('[data-testid=colleague-tries-banner]');
    const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();

    afterEach(() => vi.restoreAllMocks());

    it('is asked for after the first screen is shown, with one separate GET, and zero tries show no banner', async () => {
      const el = await open();
      expect(banner(el)).toBeNull();
      expect(el.querySelector('[role=status]')).not.toBeNull();
    });

    it('shows the count in a status region with one button, and names nobody', async () => {
      const el = await open({ notice: THREE_TRIES });
      expect(squash(banner(el)!.querySelector('[data-testid=ct-count]'))).toBe(
        '3 wrong tries of your PIN by colleagues since your last use.',
      );
      expect(banner(el)!.closest('[role=status]')).not.toBeNull();
      expect(banner(el)!.closest('main')).not.toBeNull();
      expect(Array.from(banner(el)!.querySelectorAll('button')).map((b) => squash(b))).toEqual(['Got it']);
      expect(banner(el)!.querySelector('[data-testid=ct-state]')).toBeNull();
    });

    it('"Got it" posts the latest time it showed, closes the banner at once and moves the focus to the main area', async () => {
      const el = await open({ notice: THREE_TRIES });
      el.querySelector<HTMLButtonElement>('[data-act=ct-got-it]')!.click();
      harness.detectChanges();
      const post = http.expectOne(`${NOTICE_URL}/seen`);
      expect(post.request.method).toBe('POST');
      expect(post.request.body).toEqual({ seenThrough: '2026-11-07T05:35:12.123456Z' });
      post.flush(null, { status: 204, statusText: 'No Content' });
      expect(banner(el)).toBeNull();
      expect(document.activeElement).toBe(el.querySelector('main'));
    });

    it('a failed "Got it" brings the banner back so it can be tapped again (R38-4)', async () => {
      const el = await open({ notice: THREE_TRIES });
      el.querySelector<HTMLButtonElement>('[data-act=ct-got-it]')!.click();
      http.expectOne(`${NOTICE_URL}/seen`).flush({}, { status: 500, statusText: 'Server Error' });
      harness.detectChanges();
      expect(banner(el)).not.toBeNull();
    });

    it('shows the lock line and no "Set my PIN" while the PIN is locked', async () => {
      const el = await open({ notice: { ...THREE_TRIES, state: 'LOCKED', lockedUntil: '2026-11-07T05:50:00Z' } });
      expect(squash(el.querySelector('[data-testid=ct-state]'))).toBe('Your PIN is locked until 11:20 AM IST.');
      expect(el.querySelector('[data-act=ct-set-pin]')).toBeNull();
    });

    it('shows the switched-off line with "Set my PIN" that opens the existing Set PIN sheet', async () => {
      const el = await open({ notice: { ...THREE_TRIES, state: 'NOT_SET', notSetReason: 'TOO_MANY_LOCKS' } });
      expect(squash(el.querySelector('[data-testid=ct-state]'))).toBe('Your PIN was switched off. Set a new one.');
      const dialog = vi
        .spyOn(TestBed.inject(MatDialog), 'open')
        .mockReturnValue({ afterClosed: () => new Subject<unknown>() } as never);
      el.querySelector<HTMLButtonElement>('[data-act=ct-set-pin]')!.click();
      expect(dialog.mock.calls[0][0]).toBe(SetPinSheetComponent);
      expect((dialog.mock.calls[0][1] as { ariaLabelledBy: string }).ariaLabelledBy).toBe('sp-title');
    });

    it('a failed load shows no banner, is not retried at once, and is retried at the next route change after a minute', async () => {
      const el = await open({ notice: 'fail' });
      expect(banner(el)).toBeNull();
      await harness.navigateByUrl('/shell/next');
      http.expectNone(NOTICE_URL);
      const later = Date.now() + 61_000;
      vi.spyOn(Date, 'now').mockReturnValue(later);
      await harness.navigateByUrl('/shell');
      await harness.navigateByUrl('/shell/next');
      http.expectOne(NOTICE_URL).flush(THREE_TRIES);
      harness.detectChanges();
      expect(banner(el)).not.toBeNull();
    });

    it('a good load is not asked for again at a route change', async () => {
      await open();
      vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 120_000);
      await harness.navigateByUrl('/shell/next');
      http.expectNone(NOTICE_URL);
    });

    it('forgets the notice when the shell goes away', async () => {
      await open({ notice: THREE_TRIES });
      const store = TestBed.inject(ColleagueTriesNoticeStore);
      harness.fixture.destroy();
      expect(store.notice()).toBeNull();
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
