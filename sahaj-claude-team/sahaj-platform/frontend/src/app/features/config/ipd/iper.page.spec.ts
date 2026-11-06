import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { EmergencyLevel, EmergencySettings } from '../../ipd/emergency/emergency.model';
import { CONFIG_SECTIONS } from '../config-sections';
import { IpdState } from '../modules/modules.model';
import { IpErPage } from './iper.page';

const URL = '/api/v1/ipd/emergency-settings';

const LEVELS3: EmergencyLevel[] = [
  { level: 1, name: 'Red', description: 'Immediate', tone: 'crit', targetMinutes: 5 },
  { level: 2, name: 'Yellow', description: 'Urgent', tone: 'warn', targetMinutes: 30 },
  { level: 3, name: 'Green', description: 'Can wait', tone: 'good', targetMinutes: 120 },
];
const LEVELS5: EmergencyLevel[] = [
  { level: 1, name: 'Level 1', description: 'Resuscitation', tone: 'crit', targetMinutes: 0 },
  { level: 2, name: 'Level 2', description: 'Emergent', tone: 'crit', targetMinutes: 10 },
  { level: 3, name: 'Level 3', description: 'Urgent', tone: 'warn', targetMinutes: 30 },
  { level: 4, name: 'Level 4', description: 'Less urgent', tone: 'good', targetMinutes: 60 },
  { level: 5, name: 'Level 5', description: 'Non-urgent', tone: 'good', targetMinutes: 120 },
];

const SETTINGS: EmergencySettings = {
  scale: 3,
  targets3: [5, 30, 120],
  targets5: [0, 10, 30, 60, 120],
  bays: 2,
  unknownPatientRegistration: true,
  levels: LEVELS3,
  canEdit: true,
};

const problem = (slug: string, status: number, detail: string) => ({
  type: `https://sahaj.in/problems/${slug}`,
  title: slug,
  status,
  detail,
});

describe('IpErPage', () => {
  let fixture: ComponentFixture<IpErPage>;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.fn>;

  async function open(
    role: Role,
    state: IpdState = 'on',
    settings: EmergencySettings | 'fail' = SETTINGS,
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
    fixture = TestBed.createComponent(IpErPage);
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
  const text = () => (el().textContent ?? '').replace(/\s+/g, ' ');
  const squash = (node: Element | null) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const sw = () => el().querySelector<HTMLButtonElement>('button[role=switch]')!;
  const step = (label: string) => el().querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)!;
  const scaleBtn = (label: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('.seg button')).find((b) => b.textContent!.trim() === label)!;
  const rows = () => Array.from(el().querySelectorAll('.rulerow[data-level]'));
  const stepperValue = (row: Element) => squash(row.querySelector('.stepper span'));
  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  afterEach(() => http?.verify());

  it('has Emergency and triage in the IPD group of Clinic setup, between Admission and Discharge', () => {
    const keys = CONFIG_SECTIONS.map((s) => s.key);
    const section = CONFIG_SECTIONS.find((s) => s.key === 'iper')!;
    expect(section.label).toBe('Emergency and triage');
    expect(section.group).toBe('In-patients (IPD)');
    expect(section.icon).toBe('siren');
    expect(section.requires).toBe('ipd');
    expect(section.built).toBe(true);
    expect(section.affects).toEqual(['Nurse', 'Doctor']);
    expect(keys.indexOf('iper')).toBe(keys.indexOf('ipadm') + 1);
    expect(keys.indexOf('ipdis')).toBe(keys.indexOf('iper') + 1);
  });

  describe('panel', () => {
    it('shows the Levels choice, a row for each level, the bays stepper and the switch', async () => {
      await open('OWNER');
      expect(el().querySelector('h2')!.textContent).toBe('Triage scale');
      expect(text()).toContain('Levels');
      expect(text()).toContain('3 colours suit a small nursing home; 5 levels suit a hospital emergency');
      expect(scaleBtn('3 colours').getAttribute('aria-pressed')).toBe('true');
      expect(scaleBtn('5 levels').getAttribute('aria-pressed')).toBe('false');
      expect(rows().map((r) => squash(r.querySelector('b')))).toEqual([
        'Red · Immediate',
        'Yellow · Urgent',
        'Green · Can wait',
      ]);
      expect(rows().every((r) => squash(r.querySelector('small')) === 'Target time to see a doctor')).toBe(true);
      expect(rows().map(stepperValue)).toEqual(['5 min', '30 min', '120 min']);
      expect(text()).toContain('Emergency bays');
      expect(text()).toContain('Shown on the emergency queue');
      expect(sw().getAttribute('aria-checked')).toBe('true');
      expect(text()).toContain('Unknown patient registration');
      expect(text()).toContain('Register as "Unknown male, about 40" and add details later');
    });

    it('names each target stepper "<level> target" and shows "Now" for a target of 0', async () => {
      await open('OWNER', 'on', { ...SETTINGS, scale: 5, levels: LEVELS5 });
      expect(rows().length).toBe(5);
      expect(rows().map((r) => squash(r.querySelector('b')))).toEqual([
        'Level 1 · Resuscitation',
        'Level 2 · Emergent',
        'Level 3 · Urgent',
        'Level 4 · Less urgent',
        'Level 5 · Non-urgent',
      ]);
      expect(rows().map(stepperValue)).toEqual(['Now', '10 min', '30 min', '60 min', '120 min']);
      expect(el().querySelector('[role=group][aria-label="Level 3 target"]')).not.toBeNull();
      expect(step('Less: Level 1 target').disabled).toBe(true);
      expect(step('More: Level 1 target').disabled).toBe(false);
    });

    it('shows the level dot with the tone and the bays value', async () => {
      await open('OWNER');
      const dots = rows().map((r) => r.querySelector('.tridot')!);
      expect(dots[0].classList).toContain('tri-crit');
      expect(dots[1].classList).toContain('tri-warn');
      expect(dots[2].classList).toContain('tri-good');
      expect(squash(el().querySelector('[aria-labelledby=er-bays] span'))).toBe('2');
    });
  });

  describe('owner saves each change at once with the whole setting', () => {
    it('changes the scale to 5 levels and shows the five rows from the answer', async () => {
      await open('OWNER');
      scaleBtn('5 levels').click();
      const req = http.expectOne(URL);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({
        scale: 5,
        targets3: [5, 30, 120],
        targets5: [0, 10, 30, 60, 120],
        bays: 2,
        unknownPatientRegistration: true,
      });
      req.flush({ ...SETTINGS, scale: 5, levels: LEVELS5 });
      await settle();
      expect(scaleBtn('5 levels').getAttribute('aria-pressed')).toBe('true');
      expect(rows().length).toBe(5);
    });

    it('does nothing when the chosen scale is already in use', async () => {
      await open('OWNER');
      scaleBtn('3 colours').click();
      http.expectNone(URL);
    });

    it('steps a target by 5 minutes in the set of the scale in use', async () => {
      await open('OWNER');
      step('More: Red target').click();
      let req = http.expectOne(URL);
      expect(req.request.body).toMatchObject({ targets3: [10, 30, 120], targets5: [0, 10, 30, 60, 120] });
      req.flush({ ...SETTINGS, targets3: [10, 30, 120], levels: [{ ...LEVELS3[0], targetMinutes: 10 }, LEVELS3[1], LEVELS3[2]] });
      await settle();
      expect(stepperValue(rows()[0])).toBe('10 min');

      step('Less: Yellow target').click();
      req = http.expectOne(URL);
      expect(req.request.body).toMatchObject({ targets3: [10, 25, 120] });
      req.flush({ ...SETTINGS, targets3: [10, 25, 120] });
      await settle();
    });

    it('steps the targets of the 5-level set and keeps the 3-colour set', async () => {
      await open('OWNER', 'on', { ...SETTINGS, scale: 5, levels: LEVELS5 });
      step('Less: Level 3 target').click();
      const req = http.expectOne(URL);
      expect(req.request.body).toMatchObject({ scale: 5, targets3: [5, 30, 120], targets5: [0, 10, 25, 60, 120] });
      req.flush({ ...SETTINGS, scale: 5, targets5: [0, 10, 25, 60, 120], levels: LEVELS5 });
      await settle();
    });

    it('stops the target stepper at Now and at 240 minutes', async () => {
      await open('OWNER', 'on', {
        ...SETTINGS,
        scale: 5,
        targets5: [0, 240, 30, 60, 120],
        levels: [
          { ...LEVELS5[0], targetMinutes: 0 },
          { ...LEVELS5[1], targetMinutes: 240 },
          ...LEVELS5.slice(2),
        ],
      });
      expect(step('Less: Level 1 target').disabled).toBe(true);
      expect(step('More: Level 2 target').disabled).toBe(true);
      expect(step('Less: Level 2 target').disabled).toBe(false);
    });

    it('steps the bays between 0 and 10', async () => {
      await open('OWNER');
      step('More: emergency bays').click();
      const req = http.expectOne(URL);
      expect(req.request.body).toMatchObject({ bays: 3 });
      req.flush({ ...SETTINGS, bays: 3 });
      await settle();
      expect(squash(el().querySelector('[aria-labelledby=er-bays] span'))).toBe('3');
    });

    it('disables the bays stepper at its ends', async () => {
      await open('OWNER', 'on', { ...SETTINGS, bays: 0 });
      expect(step('Less: emergency bays').disabled).toBe(true);
      TestBed.resetTestingModule();
      await open('OWNER', 'on', { ...SETTINGS, bays: 10 });
      expect(step('More: emergency bays').disabled).toBe(true);
    });

    it('switches Unknown patient registration off and on', async () => {
      await open('OWNER');
      sw().click();
      const req = http.expectOne(URL);
      expect(req.request.body).toMatchObject({ unknownPatientRegistration: false });
      req.flush({ ...SETTINGS, unknownPatientRegistration: false });
      await settle();
      expect(sw().getAttribute('aria-checked')).toBe('false');
    });

    it('sends one request while a save runs', async () => {
      await open('OWNER');
      step('More: Red target').click();
      step('More: Red target').click();
      sw().click();
      const req = http.expectOne(URL);
      req.flush({ ...SETTINGS, targets3: [10, 30, 120] });
      await settle();
    });

    it('turns the scale, target, bays and switch controls off while a save runs, and on again after', async () => {
      await open('OWNER');
      const controls = () => [
        ...Array.from(el().querySelectorAll<HTMLButtonElement>('.seg button')),
        step('Less: Red target'),
        step('More: Red target'),
        step('Less: emergency bays'),
        step('More: emergency bays'),
        sw(),
      ];
      expect(controls().every((b) => !b.disabled)).toBe(true);

      step('More: Red target').click();
      await settle();
      expect(controls().every((b) => b.disabled)).toBe(true);

      http.expectOne(URL).flush({ ...SETTINGS, targets3: [10, 30, 120] });
      await settle();
      expect(controls().every((b) => !b.disabled)).toBe(true);
    });

    it('turns the controls on again when a save fails', async () => {
      await open('OWNER');
      scaleBtn('5 levels').click();
      await settle();
      expect(scaleBtn('3 colours').disabled).toBe(true);
      http.expectOne(URL).flush(problem('validation', 400, 'No.'), { status: 400, statusText: 'Bad Request' });
      await settle();
      expect(scaleBtn('3 colours').disabled).toBe(false);
      expect(step('More: emergency bays').disabled).toBe(false);
    });

    it('shows the server message and keeps the old value when a save fails', async () => {
      await open('OWNER');
      step('More: emergency bays').click();
      http.expectOne(URL).flush(problem('validation', 400, 'Choose 0 to 10 bays'), {
        status: 400,
        statusText: 'Bad Request',
      });
      await settle();
      expect(snack).toHaveBeenCalledWith('Choose 0 to 10 bays', 'Close', expect.anything());
      expect(squash(el().querySelector('[aria-labelledby=er-bays] span'))).toBe('2');
      expect(sw().disabled).toBe(false);
    });

    it('turns every control off with a read-only notice when a save answers 409 ipd-readonly', async () => {
      await open('OWNER');
      sw().click();
      http.expectOne(URL).flush(problem('ipd-readonly', 409, 'IPD is read-only. Nothing new can be added.'), {
        status: 409,
        statusText: 'Conflict',
      });
      await settle();
      expect(snack).toHaveBeenCalledWith('IPD is read-only. Nothing new can be added.', 'Close', expect.anything());
      expect(text()).toContain('IPD is read-only. Nothing can be changed.');
      expect(sw().disabled).toBe(true);
      expect(step('More: Red target').disabled).toBe(true);
      expect(scaleBtn('5 levels').disabled).toBe(true);
    });
  });

  describe('roles and states', () => {
    it.each<Role>(['FRONT_DESK', 'NURSE', 'DOCTOR'])('shows the setup read-only for %s', async (role) => {
      await open(role);
      expect(text()).toContain('Only the owner can change the emergency setup. You can view it here.');
      expect(rows().map(stepperValue)).toEqual(['5 min', '30 min', '120 min']);
      expect(sw().disabled).toBe(true);
      expect(scaleBtn('5 levels').disabled).toBe(true);
      expect(step('More: Red target').disabled).toBe(true);
      expect(step('Less: emergency bays').disabled).toBe(true);
      expect(step('More: emergency bays').disabled).toBe(true);
      sw().click();
      step('More: Red target').click();
      http.expectNone(URL);
    });

    it('keeps the controls off for the owner when the server says canEdit is false', async () => {
      await open('OWNER', 'on', { ...SETTINGS, canEdit: false });
      expect(sw().disabled).toBe(true);
      expect(step('More: Red target').disabled).toBe(true);
    });

    it('shows the setup read-only for the owner while IPD is read-only', async () => {
      await open('OWNER', 'readonly');
      expect(text()).toContain('IPD is read-only. Nothing can be changed.');
      expect(sw().disabled).toBe(true);
      expect(scaleBtn('5 levels').disabled).toBe(true);
    });

    it('shows the page while IPD is on trial', async () => {
      await open('OWNER', 'trial');
      expect(el().querySelector('h2')!.textContent).toBe('Triage scale');
      expect(sw().disabled).toBe(false);
    });

    it('shows the lock notice and loads nothing when IPD is off', async () => {
      await open('OWNER', 'off');
      expect(text()).toContain('In-patient care (IPD) is not on for your clinic.');
      expect(el().querySelector('h2')).toBeNull();
    });

    it('shows the load error with Try again', async () => {
      await open('OWNER', 'on', 'fail');
      expect(text()).toContain('Try later.');
      el().querySelector<HTMLButtonElement>('.notice button')!.click();
      http.expectOne(URL).flush(SETTINGS);
      await settle();
      expect(el().querySelector('h2')!.textContent).toBe('Triage scale');
    });
  });
});
