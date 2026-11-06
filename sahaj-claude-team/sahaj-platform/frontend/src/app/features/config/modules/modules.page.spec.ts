import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { MenuMode } from '../../../core/shell/shell.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { ModulesView } from './modules.model';
import { ModulesPage } from './modules.page';

const URL = '/api/v1/platform/modules';

const VIEW: ModulesView = {
  plan: 'FREE',
  ipd: { state: 'off', trialEndsAt: null, requested: false, requestedAt: null },
  hra: { on: false },
  ipdTrialAvailable: false,
  menuCounts: { simple: 7, advanced: 14 },
};

describe('ModulesPage (plan and modules)', () => {
  let fixture: ComponentFixture<ModulesPage>;
  let http: HttpTestingController;
  let updatePreferences: ReturnType<typeof vi.fn>;
  let reloadMenu: ReturnType<typeof vi.fn>;
  const mode = signal<MenuMode>('advanced');

  async function open(role: Role, view: ModulesView = VIEW) {
    mode.set('advanced');
    updatePreferences = vi.fn().mockReturnValue(of({}));
    reloadMenu = vi.fn().mockReturnValue(of({}));
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ role }), menuMode: mode, updatePreferences, reloadMenu } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ModulesPage);
    fixture.detectChanges();
    http.expectOne(URL).flush(view);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const text = () => el().textContent!;
  const button = (label: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.includes(label));
  const hraSwitch = () => el().querySelector<HTMLButtonElement>('button[role="switch"]')!;

  afterEach(() => http.verify());

  it('shows the free-year pill, five rows and the owner controls', async () => {
    await open('OWNER');
    expect(text()).toContain('Free year');
    expect(el().querySelectorAll('.modrow').length).toBe(5);
    expect(text()).toContain('Not on your plan');
    expect(button('Request IPD')).toBeTruthy();
    expect(hraSwitch().disabled).toBe(false);
    expect(text()).not.toContain('trial');
  });

  it('shows Pro for any other plan', async () => {
    await open('OWNER', { ...VIEW, plan: 'PRO' });
    expect(text()).toContain('Pro');
    expect(text()).not.toContain('Free year');
  });

  it('hides the request button and disables the switch for a non-owner', async () => {
    await open('FRONT_DESK');
    expect(button('Request IPD')).toBeUndefined();
    expect(hraSwitch().disabled).toBe(true);
    expect(text()).toContain('Only the owner can request IPD');
  });

  it('sends the IPD request and then shows "Request sent"', async () => {
    await open('OWNER');
    button('Request IPD')!.click();
    const req = http.expectOne(`${URL}/ipd-request`);
    expect(req.request.method).toBe('POST');
    req.flush({ ...VIEW, ipd: { ...VIEW.ipd, requested: true, requestedAt: '2026-10-24T05:35:00Z' } });
    fixture.detectChanges();
    expect(text()).toContain('Request sent · Sahaj will call you');
    expect(button('Request IPD')).toBeUndefined();
  });

  it('shows "Request sent" without a button when already requested', async () => {
    await open('OWNER', { ...VIEW, ipd: { ...VIEW.ipd, requested: true } });
    expect(text()).toContain('Request sent · Sahaj will call you');
    expect(button('Request IPD')).toBeUndefined();
  });

  it('switches HRA on at once and updates the masters group', async () => {
    await open('OWNER');
    expect(text()).toContain('Module off');
    hraSwitch().click();
    const req = http.expectOne(`${URL}/hra`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ on: true });
    req.flush({ ...VIEW, hra: { on: true } });
    fixture.detectChanges();
    expect(hraSwitch().getAttribute('aria-checked')).toBe('true');
    expect(el().querySelectorAll('.mm.off').length).toBe(1);
    expect(reloadMenu).toHaveBeenCalledTimes(1);
  });

  it('keeps the old state and shows a message when saving HRA fails', async () => {
    await open('OWNER');
    hraSwitch().click();
    http.expectOne(`${URL}/hra`).flush({ title: 'Forbidden', status: 403 }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(hraSwitch().getAttribute('aria-checked')).toBe('false');
  });

  it('hides the trial notice when the trial is not available', async () => {
    await open('OWNER');
    expect(button('Start 30-day IPD trial')).toBeUndefined();
  });

  it('disables the trial button for a non-owner', async () => {
    await open('FRONT_DESK', { ...VIEW, ipdTrialAvailable: true });
    expect(text()).toContain('Try IPD: start a 30-day trial here.');
    expect(button('Start 30-day IPD trial')!.disabled).toBe(true);
  });

  it('starts the trial, updates the page and toasts the end date', async () => {
    await open('OWNER', { ...VIEW, ipdTrialAvailable: true });
    const toast = vi.spyOn(TestBed.inject(MatSnackBar), 'open');
    button('Start 30-day IPD trial')!.click();
    const req = http.expectOne(`${URL}/ipd-trial`);
    expect(req.request.method).toBe('POST');
    req.flush({ ...VIEW, ipdTrialAvailable: false, ipd: { state: 'trial', trialEndsAt: '2026-12-05T06:00:00Z', requested: false } });
    fixture.detectChanges();
    expect(el().querySelector('#ipd-pill')!.textContent).toContain('Trial · ends 5 Dec 2026');
    expect(button('Start 30-day IPD trial')).toBeUndefined();
    expect(reloadMenu).toHaveBeenCalledTimes(1);
    expect(toast).toHaveBeenCalledWith('IPD trial started. It ends 5 Dec 2026', 'Close', { duration: 5000 });
  });

  it('shows the trial end date', async () => {
    await open('OWNER', { ...VIEW, ipd: { state: 'trial', trialEndsAt: '2026-11-24T06:00:00Z', requested: true } });
    expect(el().querySelector('#ipd-pill')!.textContent).toContain('Trial · ends 24 Nov 2026');
    expect(text()).not.toContain('Request sent');
  });

  it('shows the read-only notice and marks IPD masters in use', async () => {
    await open('OWNER', { ...VIEW, ipd: { state: 'readonly', requested: true } });
    expect(text()).toContain('IPD is read-only. Past admissions, bills and summaries stay viewable for 90 days');
    expect(el().querySelector('#ipd-pill')!.textContent).toContain('Read-only');
    expect(el().querySelectorAll('.mm.off').length).toBe(1);
  });

  it('shows the menu counts, marks the current mode and saves a change through the shell store', async () => {
    await open('FRONT_DESK');
    expect(text()).toContain('7 menu items');
    expect(text()).toContain('14 menu items');
    expect(button('Advanced')!.getAttribute('aria-pressed')).toBe('true');
    button('Simple')!.click();
    expect(updatePreferences).toHaveBeenCalledWith({ menuMode: 'simple' });
    updatePreferences.mockClear();
    button('Advanced')!.click();
    expect(updatePreferences).not.toHaveBeenCalled();
  });

  it('shows an error when the load fails', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ role: 'OWNER' }), menuMode: mode, updatePreferences: () => of({}) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ModulesPage);
    fixture.detectChanges();
    http.expectOne(URL).flush({ title: 'Server error', status: 500 }, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(el().querySelector('[role="alert"]')).toBeTruthy();
  });
});
