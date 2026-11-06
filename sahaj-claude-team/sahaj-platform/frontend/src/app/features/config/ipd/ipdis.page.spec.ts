import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { DischargeSettings } from '../../ipd/discharge.model';
import { IpdState } from '../modules/modules.model';
import { CONFIG_SECTIONS } from '../config-sections';
import { IpDisPage } from './ipdis.page';

const URL = '/api/v1/ipd/discharge-settings';

const SETTINGS: DischargeSettings = {
  templateKey: null,
  approvalChain: true,
  followUpDays: 7,
  types: [
    { key: 'normal', label: 'Normal' },
    { key: 'lama', label: 'On request (LAMA)' },
    { key: 'referred', label: 'Referred' },
    { key: 'death', label: 'Death' },
  ],
  templates: [
    { key: 'general', label: 'General medicine' },
    { key: 'surgical', label: 'Surgical' },
    { key: 'maternity', label: 'Maternity' },
    { key: 'daycare', label: 'Day-care' },
  ],
  opdFollowUpRule: { days: 6, fee: 'free' },
  canEdit: true,
};

const problem = (slug: string, status: number, detail: string) => ({
  type: `https://sahaj.in/problems/${slug}`,
  title: slug,
  status,
  detail,
});

describe('IpDisPage', () => {
  let fixture: ComponentFixture<IpDisPage>;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.fn>;

  async function open(
    role: Role,
    state: IpdState = 'on',
    settings: DischargeSettings | 'fail' = SETTINGS,
  ) {
    snack = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ role }) } },
        { provide: MatSnackBar, useValue: { open: snack } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(IpDisPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/platform/modules').flush({
      plan: 'PRO',
      ipd: { state, requested: false },
      hra: { on: false },
      ipdTrialAvailable: false,
      menuCounts: { simple: 7, advanced: 14 },
    });
    fixture.detectChanges();
    await fixture.whenStable();
    if (state !== 'off') {
      const call = http.expectOne(URL);
      if (settings === 'fail') {
        call.flush(problem('x', 500, 'Try later.'), { status: 500, statusText: 'Server Error' });
      } else {
        call.flush(settings);
      }
      await fixture.whenStable();
      fixture.detectChanges();
    }
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const text = () => el().textContent ?? '';
  const squash = (node: Element | null) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const select = () => el().querySelector<HTMLSelectElement>('#dis-template')!;
  const sw = () => el().querySelector<HTMLButtonElement>('button[role=switch]')!;
  const step = (label: string) => el().querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)!;
  const choose = (value: string) => {
    select().value = value;
    select().dispatchEvent(new Event('change'));
  };

  afterEach(() => http?.verify());

  it('has a Discharge section in the IPD group of Clinic setup, after Emergency and triage', () => {
    const keys = CONFIG_SECTIONS.map((s) => s.key);
    const section = CONFIG_SECTIONS.find((s) => s.key === 'ipdis')!;
    expect(section.label).toBe('Discharge');
    expect(section.group).toBe('In-patients (IPD)');
    expect(section.requires).toBe('ipd');
    expect(section.built).toBe(true);
    expect(keys.indexOf('ipdis')).toBe(keys.indexOf('iper') + 1);
  });

  it('shows the panel with the template, the switch, the stepper and the type chips', async () => {
    await open('OWNER');
    expect(el().querySelector('h2')!.textContent).toBe('Discharge');
    expect(text()).toContain('Summary template');
    expect(text()).toContain('The doctor fills and signs it before the final bill');
    expect(Array.from(select().options).map((o) => o.textContent!.trim())).toEqual([
      'Choose a template',
      'General medicine',
      'Surgical',
      'Maternity',
      'Day-care',
    ]);
    expect(select().value).toBe('');
    expect(text()).toContain('Approval chain');
    expect(text()).toContain('Doctor signs → billing clearance → nursing handover');
    expect(sw().getAttribute('aria-checked')).toBe('true');
    expect(text()).toContain('Follow-up after discharge');
    expect(squash(el().querySelector('.stepper span'))).toBe('7 days');
    expect(text()).toContain('Adds an OPD follow-up. The fee follows your follow-up rule (free within 6 days).');
    expect(text()).toContain('Discharge types');
    expect(Array.from(el().querySelectorAll('.chip')).map(squash)).toEqual([
      'Normal',
      'On request (LAMA)',
      'Referred',
      'Death',
    ]);
  });

  it('shows the saved template and says charged when the follow-up rule charges a fee', async () => {
    await open('OWNER', 'on', {
      ...SETTINGS,
      templateKey: 'surgical',
      approvalChain: false,
      opdFollowUpRule: { days: 10, fee: 'half' },
    });
    expect(select().value).toBe('surgical');
    expect(sw().getAttribute('aria-checked')).toBe('false');
    expect(text()).toContain('(charged within 10 days)');
  });

  it('saves the whole setting at once when the template changes', async () => {
    await open('OWNER');
    choose('maternity');
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ templateKey: 'maternity', approvalChain: true, followUpDays: 7 });
    req.flush({ ...SETTINGS, templateKey: 'maternity' });
  });

  it('sends a null template when Choose a template is picked again', async () => {
    await open('OWNER', 'on', { ...SETTINGS, templateKey: 'general' });
    choose('');
    const req = http.expectOne(URL);
    expect(req.request.body).toEqual({ templateKey: null, approvalChain: true, followUpDays: 7 });
    req.flush(SETTINGS);
  });

  it('saves the switch at once', async () => {
    await open('OWNER');
    sw().click();
    const req = http.expectOne(URL);
    expect(req.request.body).toEqual({ templateKey: null, approvalChain: false, followUpDays: 7 });
    req.flush({ ...SETTINGS, approvalChain: false });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(sw().getAttribute('aria-checked')).toBe('false');
  });

  it('steps the follow-up days by one and saves at once', async () => {
    await open('OWNER');
    step('More follow-up days').click();
    let req = http.expectOne(URL);
    expect(req.request.body).toEqual({ templateKey: null, approvalChain: true, followUpDays: 8 });
    req.flush({ ...SETTINGS, followUpDays: 8 });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(squash(el().querySelector('.stepper span'))).toBe('8 days');
    step('Fewer follow-up days').click();
    req = http.expectOne(URL);
    expect(req.request.body.followUpDays).toBe(7);
    req.flush(SETTINGS);
  });

  it('keeps the stepper buttons enabled while a save runs and ignores a second click', async () => {
    await open('OWNER');
    const more = step('More follow-up days');
    more.click();
    fixture.detectChanges();
    const req = http.expectOne(URL);
    expect(more.disabled).toBe(false);
    expect(step('Fewer follow-up days').disabled).toBe(false);
    more.click();
    step('Fewer follow-up days').click();
    http.expectNone(URL);
    req.flush({ ...SETTINGS, followUpDays: 8 });
  });

  it('stops the stepper at 1 and 30 days and writes 1 day in the singular', async () => {
    await open('OWNER', 'on', { ...SETTINGS, followUpDays: 1 });
    expect(squash(el().querySelector('.stepper span'))).toBe('1 day');
    expect(step('Fewer follow-up days').disabled).toBe(true);
    expect(step('More follow-up days').disabled).toBe(false);
    TestBed.resetTestingModule();
    await open('OWNER', 'on', { ...SETTINGS, followUpDays: 30 });
    expect(step('More follow-up days').disabled).toBe(true);
    expect(step('Fewer follow-up days').disabled).toBe(false);
  });

  it('puts the template back and tells the user when a save fails', async () => {
    await open('OWNER');
    choose('daycare');
    http.expectOne(URL).flush(problem('x', 500, 'Try later.'), { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    expect(snack).toHaveBeenCalledWith('Try later.', 'Close', { duration: 5000 });
    expect(select().value).toBe('');
  });

  it('shows the 409 ipd-readonly message and keeps the saved value', async () => {
    await open('OWNER');
    sw().click();
    http
      .expectOne(URL)
      .flush(problem('ipd-readonly', 409, 'IPD is read-only. Nothing can be changed.'), {
        status: 409,
        statusText: 'Conflict',
      });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(snack).toHaveBeenCalledWith('IPD is read-only. Nothing can be changed.', 'Close', { duration: 5000 });
    expect(sw().getAttribute('aria-checked')).toBe('true');
  });

  for (const role of ['FRONT_DESK', 'NURSE', 'DOCTOR'] as Role[]) {
    it(`shows ${role} the setup read-only with a note`, async () => {
      await open(role, 'on', { ...SETTINGS, canEdit: false });
      expect(select().disabled).toBe(true);
      expect(sw().disabled).toBe(true);
      expect(step('More follow-up days').disabled).toBe(true);
      expect(step('Fewer follow-up days').disabled).toBe(true);
      expect(text()).toContain('Only the owner can change the discharge setup. You can view it here.');
    });
  }

  it('is read-only for the owner when IPD is read-only', async () => {
    await open('OWNER', 'readonly', { ...SETTINGS, canEdit: false });
    expect(select().disabled).toBe(true);
    expect(sw().disabled).toBe(true);
    expect(text()).toContain('IPD is read-only. Nothing can be changed.');
  });

  it('works while IPD is on trial', async () => {
    await open('OWNER', 'trial');
    expect(select().disabled).toBe(false);
  });

  it('shows the off notice and reads nothing when IPD is off', async () => {
    await open('OWNER', 'off');
    expect(text()).toContain('In-patient care (IPD) is not on for your clinic.');
    expect(el().querySelector('#dis-template')).toBeNull();
  });

  it('shows the load error with Try again', async () => {
    await open('OWNER', 'on', 'fail');
    expect(text()).toContain('Try later.');
    const retry = Array.from(el().querySelectorAll('button')).find((b) => b.textContent!.trim() === 'Try again')!;
    retry.click();
    http.expectOne(URL).flush(SETTINGS);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(select()).not.toBeNull();
  });
});
