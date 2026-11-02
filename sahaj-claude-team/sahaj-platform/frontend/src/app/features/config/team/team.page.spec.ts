import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
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
