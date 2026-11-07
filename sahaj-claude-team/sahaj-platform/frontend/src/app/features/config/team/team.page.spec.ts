import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';
import { ResetPinSheetComponent } from '../../pin/reset-pin-sheet.component';
import { ColleagueTriesSheetComponent } from '../../pin/colleague-tries-sheet.component';
import { SetPinSheetComponent } from '../../pin/set-pin-sheet.component';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { RolePermission, TeamMember } from './team.model';
import { TeamPage, formatLastLogin, initials } from './team.page';

const BASE = '/api/v1/platform';

const OWNER: TeamMember = {
  id: 'm-owner',
  name: 'Dr. Asha Kulkarni',
  mobileMasked: '••••••1001',
  role: 'OWNER',
  status: 'ACTIVE',
  lastLoginAt: '2026-10-24T05:35:00Z',
  doctor: { registrationNo: '' },
};
const NURSE: TeamMember = {
  id: 'm-nurse',
  name: 'Meera Patil',
  mobileMasked: '••••••7410',
  role: 'NURSE',
  status: 'ACTIVE',
  lastLoginAt: null,
};
const MATRIX: RolePermission[] = [
  { role: 'FRONT_DESK', permission: 'token', allowed: true },
  { role: 'FRONT_DESK', permission: 'refund', allowed: false },
  { role: 'NURSE', permission: 'vitals', allowed: true },
];

describe('TeamPage', () => {
  let fixture: ComponentFixture<TeamPage>;
  let http: HttpTestingController;

  async function open(role: Role, members: TeamMember[] = [OWNER, NURSE], membershipId?: string) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', membershipId, role }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TeamPage);
    fixture.detectChanges();
    http.expectOne(`${BASE}/team`).flush(members);
    http.expectOne(`${BASE}/role-permissions`).flush(MATRIX);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const buttonByText = (el: HTMLElement, text: string) =>
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes(text)) as
      | HTMLButtonElement
      | undefined;

  afterEach(() => http.verify());

  it('shows each person with the mobile masked and the role', async () => {
    const el = await open('OWNER');
    expect(el.textContent).toContain('••••••7410');
    expect(el.textContent).toContain('Meera Patil');
    expect(el.textContent).toContain('Nurse');
    expect(el.textContent).not.toMatch(/\d{10}/);
    expect(buttonByText(el, 'Add staff')).toBeTruthy();
  });

  it('is read-only for a front desk user: no add, no switch, view-only permission table', async () => {
    const el = await open('FRONT_DESK');
    expect(buttonByText(el, 'Add staff')).toBeUndefined();
    expect(el.querySelector('[role="switch"]')).toBeNull();
    const tick = el.querySelector<HTMLButtonElement>('[aria-label="Give refunds · Front desk"]')!;
    expect(tick.disabled).toBe(true);
    expect(el.textContent).toContain('Only the owner can change these.');
  });

  it('nests the permission table and all its screen-reader text inside the .tbl-wrap element', async () => {
    const el = await open('OWNER');
    const wrap = el.querySelector('.tbl-wrap');
    expect(wrap).not.toBeNull();
    expect(wrap!.querySelector(':scope > table.pmx')).not.toBeNull();
    // DOM nesting is all a unit test can check: the global stylesheet is not loaded in this test, so
    // this does not prove the page cannot scroll sideways. That is covered by the e2e check at 360 px,
    // which relies on `.tbl-wrap { position: relative; overflow-x: auto }` in styles/_primitives.scss.
    expect(wrap!.querySelectorAll('.sr-only').length).toBeGreaterThan(0);
    expect(el.querySelectorAll('.sr-only').length).toBe(wrap!.querySelectorAll('.sr-only').length);
  });

  it('does not let the signed-in owner switch off or remove themselves', async () => {
    const second: TeamMember = { ...OWNER, id: 'm-owner-2', name: 'Dr. Ravi Rao' };
    const el = await open('OWNER', [OWNER, second, NURSE], 'm-owner');
    const own = el.querySelector<HTMLButtonElement>('[aria-label="Dr. Asha Kulkarni can sign in"]')!;
    expect(own.disabled).toBe(true);
    expect(el.querySelector('[aria-label="Remove access for Dr. Asha Kulkarni"]')).toBeNull();
    const other = el.querySelector<HTMLButtonElement>('[aria-label="Dr. Ravi Rao can sign in"]')!;
    expect(other.disabled).toBe(false);
  });

  it('asks for the name and a 10-digit mobile before calling the server', async () => {
    const el = await open('OWNER');
    buttonByText(el, 'Add staff')!.click();
    fixture.detectChanges();
    buttonByText(el, 'Add and send login')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el.textContent).toContain('Enter the name');
    expect(el.textContent).toContain('Enter a 10-digit mobile number');
    http.expectNone(`${BASE}/team`);
  });

  it('adds a person and lists them', async () => {
    const el = await open('OWNER');
    buttonByText(el, 'Add staff')!.click();
    fixture.detectChanges();
    const name = el.querySelector<HTMLInputElement>('input[formcontrolname="name"]')!;
    const mobile = el.querySelector<HTMLInputElement>('input[formcontrolname="mobile"]')!;
    name.value = 'Ravi Shah';
    name.dispatchEvent(new Event('input'));
    mobile.value = '9876543210';
    mobile.dispatchEvent(new Event('input'));
    buttonByText(el, 'Add and send login')!.click();
    const req = http.expectOne(`${BASE}/team`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'Ravi Shah', mobile: '9876543210', role: 'FRONT_DESK' });
    req.flush({
      id: 'm-new',
      name: 'Ravi Shah',
      mobileMasked: '••••••3210',
      role: 'FRONT_DESK',
      status: 'ACTIVE',
    });
    fixture.detectChanges();
    expect(el.textContent).toContain('Ravi Shah');
  });

  it('shows the plan message when the clinic is at its user limit', async () => {
    const el = await open('OWNER');
    buttonByText(el, 'Add staff')!.click();
    fixture.detectChanges();
    const name = el.querySelector<HTMLInputElement>('input[formcontrolname="name"]')!;
    const mobile = el.querySelector<HTMLInputElement>('input[formcontrolname="mobile"]')!;
    name.value = 'Ravi Shah';
    name.dispatchEvent(new Event('input'));
    mobile.value = '9876543210';
    mobile.dispatchEvent(new Event('input'));
    buttonByText(el, 'Add and send login')!.click();
    http.expectOne(`${BASE}/team`).flush(
      {
        type: 'https://sahaj.example/problems/plan-limit',
        title: 'Plan limit reached',
        status: 409,
        detail: 'You are on the Free plan: up to 5 users.',
      },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('up to 5 users');
  });

  it('switches a person off and shows "cannot sign in"', async () => {
    const el = await open('OWNER');
    el.querySelector<HTMLButtonElement>('[aria-label="Meera Patil can sign in"]')!.click();
    const req = http.expectOne(`${BASE}/team/m-nurse/deactivate`);
    expect(req.request.method).toBe('POST');
    req.flush({ ...NURSE, status: 'INACTIVE' });
    fixture.detectChanges();
    expect(el.textContent).toContain('cannot sign in');
  });

  it('does not let the only active owner be switched off', async () => {
    const el = await open('OWNER');
    const sw = el.querySelector<HTMLButtonElement>('[aria-label="Dr. Asha Kulkarni can sign in"]')!;
    expect(sw.disabled).toBe(true);
  });

  it('highlights a doctor with no registration number', async () => {
    const el = await open('OWNER');
    expect(el.querySelector('.reg.miss')).toBeTruthy();
  });

  it('saves a permission change straight away with the whole matrix', async () => {
    const el = await open('OWNER');
    el.querySelector<HTMLButtonElement>('[aria-label="Give refunds · Front desk"]')!.click();
    const req = http.expectOne(`${BASE}/role-permissions`);
    expect(req.request.method).toBe('PUT');
    const body = req.request.body as RolePermission[];
    expect(body.find((p) => p.role === 'FRONT_DESK' && p.permission === 'refund')?.allowed).toBe(true);
    req.flush(body);
  });
});

describe('TeamPage personal PIN (SAH-31)', () => {
  let fixture: ComponentFixture<TeamPage>;
  let http: HttpTestingController;

  const withPin = (m: TeamMember, state: 'NOT_SET' | 'SET' | 'LOCKED'): TeamMember => ({
    ...m,
    pin: {
      state,
      setAt: state === 'NOT_SET' ? null : '2026-11-06T05:35:00Z',
      lockedUntil: state === 'LOCKED' ? '2026-11-06T05:50:00Z' : null,
    },
  });
  const PHARMACIST: TeamMember = {
    id: 'm-ph',
    name: 'Rahul Deshmukh',
    mobileMasked: '••••••3301',
    role: 'PHARMACIST',
    status: 'ACTIVE',
    lastLoginAt: null,
  };
  const DOCTOR: TeamMember = {
    id: 'm-doc',
    name: 'Dr. Meera Kulkarni',
    mobileMasked: '••••••2002',
    role: 'DOCTOR',
    status: 'ACTIVE',
    lastLoginAt: null,
    doctor: { registrationNo: 'MMC 1' },
  };

  async function open(role: Role, members: TeamMember[], membershipId?: string) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', membershipId, role }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TeamPage);
    fixture.detectChanges();
    http.expectOne(`${BASE}/team`).flush(members);
    http.expectOne(`${BASE}/role-permissions`).flush(MATRIX);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const squash = (n: Element | null | undefined) => (n?.textContent ?? '').replace(/\s+/g, ' ').trim();
  /** The pills of the Staff panel (the owner is also listed under Doctors when they have a registration number). */
  const pills = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('#f-staff [data-testid=pin-pill]')).map((p) => squash(p));
  const tick = () => new Promise<void>((r) => setTimeout(r, 0));

  afterEach(() => http.verify());

  it('shows the owner a pill on every staff row: "PIN set", "PIN locked", "No PIN yet"', async () => {
    const el = await open('OWNER', [
      withPin(OWNER, 'SET'),
      withPin(NURSE, 'NOT_SET'),
      withPin(PHARMACIST, 'LOCKED'),
    ]);
    expect(pills(el)).toEqual(['PIN set', 'No PIN yet', 'PIN locked']);
    expect(el.querySelector('#f-staff [data-testid=pin-pill].p-good')).not.toBeNull();
  });

  it('shows a pill on a doctor row too', async () => {
    const el = await open('OWNER', [withPin(OWNER, 'SET'), withPin(DOCTOR, 'SET')]);
    const docs = Array.from(el.querySelectorAll('#f-docs [data-testid=pin-pill]')).map((p) => squash(p));
    expect(docs).toEqual(['PIN set', 'PIN set']);
    expect(el.querySelectorAll('#f-staff [data-testid=pin-pill]').length).toBe(1);
  });

  it('offers Reset PIN to the owner only on rows with a PIN set or locked', async () => {
    const el = await open('OWNER', [withPin(OWNER, 'NOT_SET'), withPin(NURSE, 'SET'), withPin(PHARMACIST, 'LOCKED')]);
    const labels = Array.from(el.querySelectorAll('[data-act=pin-reset]')).map((b) => b.getAttribute('aria-label'));
    expect(labels).toEqual(['Reset PIN for Meera Patil', 'Reset PIN for Rahul Deshmukh']);
  });

  it('does not offer Reset PIN, or other people\'s pills, to a role that is not the owner', async () => {
    const el = await open('FRONT_DESK', [OWNER, NURSE], 'm-nurse');
    expect(el.querySelector('[data-act=pin-reset]')).toBeNull();
    expect(pills(el)).toEqual([]);
  });

  it('opens the confirm sheet for the member, then shows "No PIN yet", toasts, and focuses the pill', async () => {
    const el = await open('OWNER', [withPin(OWNER, 'SET'), withPin(PHARMACIST, 'SET')], 'm-owner');
    const cleared = withPin(PHARMACIST, 'NOT_SET');
    const dialog = vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(cleared) } as never);
    const snack = vi.spyOn(TestBed.inject(MatSnackBar), 'open').mockReturnValue({} as never);
    el.querySelector<HTMLButtonElement>('[aria-label="Reset PIN for Rahul Deshmukh"]')!.click();
    fixture.detectChanges();
    await tick();
    const [component, config] = dialog.mock.calls[0];
    expect(component).toBe(ResetPinSheetComponent);
    expect((config as { data: unknown }).data).toEqual({ id: 'm-ph', name: 'Rahul Deshmukh' });
    expect((config as { ariaLabelledBy: string }).ariaLabelledBy).toBe('rp-title');
    expect(snack.mock.calls[0][0]).toBe('PIN reset for Rahul Deshmukh.');
    expect(pills(el)).toEqual(['PIN set', 'No PIN yet']);
    expect(el.querySelector('[aria-label="Reset PIN for Rahul Deshmukh"]')).toBeNull();
    expect(document.activeElement?.id).toBe('pin-pill-m-ph');
  });

  it('changes nothing when the confirm sheet is cancelled', async () => {
    const el = await open('OWNER', [withPin(OWNER, 'SET'), withPin(PHARMACIST, 'SET')], 'm-owner');
    vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(undefined) } as never);
    const snack = vi.spyOn(TestBed.inject(MatSnackBar), 'open').mockReturnValue({} as never);
    el.querySelector<HTMLButtonElement>('[aria-label="Reset PIN for Rahul Deshmukh"]')!.click();
    fixture.detectChanges();
    expect(snack).not.toHaveBeenCalled();
    expect(pills(el)).toEqual(['PIN set', 'PIN set']);
  });

  it('gives the own row "Set my PIN" or "Change my PIN" and no one else\'s', async () => {
    const el = await open('NURSE', [OWNER, withPin(NURSE, 'NOT_SET')], 'm-nurse');
    const own = Array.from(el.querySelectorAll('[data-act=pin-own]')).map((b) => squash(b));
    expect(own).toEqual(['Set my PIN']);
    expect(pills(el)).toEqual(['No PIN yet']);
  });

  it('says "Change my PIN" on the own row when a PIN is set', async () => {
    const el = await open('OWNER', [withPin(OWNER, 'SET'), NURSE], 'm-owner');
    expect(squash(el.querySelector('[data-act=pin-own]'))).toBe('Change my PIN');
  });

  it('opens the Set PIN sheet from the own row and shows the new state after a save', async () => {
    const el = await open('NURSE', [OWNER, withPin(NURSE, 'NOT_SET')], 'm-nurse');
    const saved = { state: 'SET', setAt: '2026-11-06T06:00:00Z', lockedUntil: null };
    const dialog = vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(saved) } as never);
    el.querySelector<HTMLButtonElement>('[data-act=pin-own]')!.click();
    fixture.detectChanges();
    expect(dialog.mock.calls[0][0]).toBe(SetPinSheetComponent);
    expect((dialog.mock.calls[0][1] as { ariaLabelledBy: string }).ariaLabelledBy).toBe('sp-title');
    expect(pills(el)).toEqual(['PIN set']);
    expect(squash(el.querySelector('[data-act=pin-own]'))).toBe('Change my PIN');
  });

  it('keeps the row as it was when the Set PIN sheet is cancelled', async () => {
    const el = await open('NURSE', [OWNER, withPin(NURSE, 'NOT_SET')], 'm-nurse');
    vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(undefined) } as never);
    el.querySelector<HTMLButtonElement>('[data-act=pin-own]')!.click();
    fixture.detectChanges();
    expect(pills(el)).toEqual(['No PIN yet']);
  });
});

describe('TeamPage colleague tries (SAH-38, ADR-014 D9)', () => {
  let fixture: ComponentFixture<TeamPage>;
  let http: HttpTestingController;

  const row = (m: TeamMember, tries: number | null | undefined): TeamMember => ({
    ...m,
    pin: { state: 'SET', setAt: '2026-11-06T05:35:00Z', lockedUntil: null, colleagueTries7d: tries },
  });

  async function open(role: Role, members: TeamMember[], membershipId?: string) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', membershipId, role }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TeamPage);
    fixture.detectChanges();
    http.expectOne(`${BASE}/team`).flush(members);
    http.expectOne(`${BASE}/role-permissions`).flush(MATRIX);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const squash = (n: Element | null | undefined) => (n?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const lines = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('#f-staff [data-act=pin-tries]')).map((b) => squash(b));

  afterEach(() => http.verify());

  it('shows the owner the count on each row that has tries, and nothing on a row with none', async () => {
    const el = await open('OWNER', [row(OWNER, 0), row(NURSE, 4), row({ ...NURSE, id: 'm-n2', name: 'Anita Rao' }, 1)], 'm-owner');
    expect(lines(el)).toEqual(['4 wrong tries by colleagues in 7 days', '1 wrong try by a colleague in 7 days']);
  });

  it('shows no count when the server sent none (no zero is made up)', async () => {
    const el = await open('OWNER', [row(OWNER, 0), row(NURSE, null)], 'm-owner');
    expect(lines(el)).toEqual([]);
  });

  it('shows no count to a role that is not the owner', async () => {
    const el = await open('NURSE', [OWNER, row(NURSE, null)], 'm-nurse');
    expect(el.querySelector('[data-act=pin-tries]')).toBeNull();
  });

  it('opens the history sheet for that member with the name', async () => {
    const el = await open('OWNER', [row(OWNER, 0), row(NURSE, 4)], 'm-owner');
    const dialog = vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(undefined) } as never);
    el.querySelector<HTMLButtonElement>('#f-staff [data-act=pin-tries]')!.click();
    const [component, config] = dialog.mock.calls[0];
    expect(component).toBe(ColleagueTriesSheetComponent);
    expect((config as { data: unknown }).data).toEqual({ id: 'm-nurse', name: 'Meera Patil' });
    expect((config as { ariaLabelledBy: string }).ariaLabelledBy).toBe('ct-title');
  });

  it('keeps the count after an owner reset, because it is evidence', async () => {
    const el = await open('OWNER', [row(OWNER, 0), row(NURSE, 4)], 'm-owner');
    const cleared: TeamMember = { ...NURSE, pin: { state: 'NOT_SET', setAt: null, lockedUntil: null } };
    vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(cleared) } as never);
    vi.spyOn(TestBed.inject(MatSnackBar), 'open').mockReturnValue({} as never);
    el.querySelector<HTMLButtonElement>('[aria-label="Reset PIN for Meera Patil"]')!.click();
    fixture.detectChanges();
    expect(squash(el.querySelector('#f-staff [data-act=pin-tries]'))).toBe('4 wrong tries by colleagues in 7 days');
  });

  it('shows the existing error state, with no counts, when the team cannot be loaded', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', membershipId: 'm-owner', role: 'OWNER' }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TeamPage);
    fixture.detectChanges();
    http.expectOne(`${BASE}/team`).flush({}, { status: 500, statusText: 'Server Error' });
    http.expectOne(`${BASE}/role-permissions`).flush(MATRIX);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.notice.err')).not.toBeNull();
    expect(el.querySelector('[data-act=pin-tries]')).toBeNull();
  });
});

describe('team helpers', () => {
  it('formats the last login in IST, or Never', () => {
    expect(formatLastLogin('2026-10-24T05:35:00Z')).toBe('24 Oct 2026, 11:05 AM');
    expect(formatLastLogin(null)).toBe('Never');
  });

  it('makes initials, skipping Dr.', () => {
    expect(initials('Dr. Asha Kulkarni')).toBe('AK');
    expect(initials('Meera')).toBe('M');
  });
});
