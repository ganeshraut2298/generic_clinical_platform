import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { FeeSettings, Service } from '../opd/opd.model';
import { TeamMember } from '../team/team.model';
import { FeesPage, followUpAmount } from './fees.page';
import { DUPLICATE_SERVICE } from './service-list.component';

const OPD = '/api/v1/opd';
const PLATFORM = '/api/v1/platform';

const SETTINGS: FeeSettings = {
  feeTiming: 'after',
  followUpDays: 6,
  followUpFee: 'free',
  discountLimits: { FRONT_DESK: 10, NURSE: 0, OWNER: 100 },
  upiOnBill: true,
};
const DOCTOR: TeamMember = {
  id: 'm-doc',
  name: 'Dr. Asha Kulkarni',
  mobileMasked: '••••••1001',
  role: 'DOCTOR',
  status: 'ACTIVE',
  doctor: { feeRupees: 400, registrationNo: 'MMC 1' },
};
const DRESSING: Service = { id: 's1', name: 'Dressing', category: 'Procedure', priceRupees: 150, active: true };

describe('followUpAmount', () => {
  it('writes the follow-up amount in rupees with Indian grouping', () => {
    expect(followUpAmount('free', 400)).toBe('Free');
    expect(followUpAmount('half', 1250)).toBe('₹625');
    expect(followUpAmount('full', 125000)).toBe('₹1,25,000');
  });
});

describe('FeesPage', () => {
  let fixture: ComponentFixture<FeesPage>;
  let http: HttpTestingController;

  async function open(role: Role) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(FeesPage);
    fixture.detectChanges();
    http.expectOne(`${OPD}/fee-settings`).flush(SETTINGS);
    http.expectOne(`${PLATFORM}/team`).flush([DOCTOR]);
    fixture.detectChanges();
    http.expectOne(`${OPD}/services`).flush([DRESSING]);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  function type(el: HTMLElement, selector: string, value: string, eventName = 'input') {
    const input = el.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event(eventName));
    return input;
  }

  afterEach(() => http.verify());

  it('shows a live example that changes when the window changes', async () => {
    const el = await open('OWNER');
    expect(el.querySelector('.example')?.textContent).toContain('Dr. Asha Kulkarni');
    expect(el.querySelector('.example')?.textContent).toContain('Free');
    el.querySelector<HTMLButtonElement>('[aria-label="More days"]')!.click();
    const req = http.expectOne(`${OPD}/fee-settings`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.followUpDays).toBe(7);
    fixture.detectChanges();
    expect(el.textContent).toContain('7 days');
    req.flush({ ...SETTINGS, followUpDays: 7 });
  });

  it('saves a doctor new-visit fee through the team call', async () => {
    const el = await open('OWNER');
    type(el, '[aria-label="Dr. Asha Kulkarni new visit fee"]', '500', 'change');
    const req = http.expectOne(`${PLATFORM}/team/m-doc`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ doctor: { feeRupees: 500, registrationNo: 'MMC 1' } });
    req.flush({ ...DOCTOR, doctor: { ...DOCTOR.doctor, feeRupees: 500 } });
  });

  it('rejects a discount limit above 100 without calling the server', async () => {
    const el = await open('OWNER');
    const input = type(el, '#limit-FRONT_DESK', '150', 'change');
    http.expectNone(`${OPD}/fee-settings`);
    expect(input.value).toBe('10');
  });

  it('saves a discount limit with the whole settings object', async () => {
    const el = await open('OWNER');
    type(el, '#limit-NURSE', '5', 'change');
    const req = http.expectOne(`${OPD}/fee-settings`);
    expect(req.request.body.discountLimits).toEqual({ FRONT_DESK: 10, NURSE: 5, OWNER: 100 });
    req.flush({ ...SETTINGS, discountLimits: { FRONT_DESK: 10, NURSE: 5, OWNER: 100 } });
  });

  it('is read-only for the front desk', async () => {
    const el = await open('FRONT_DESK');
    expect(el.querySelector<HTMLButtonElement>('[aria-label="More days"]')!.disabled).toBe(true);
    expect(el.querySelector<HTMLInputElement>('#limit-NURSE')!.disabled).toBe(true);
    expect(el.querySelector('[role="switch"]')?.hasAttribute('disabled')).toBe(true);
    expect(el.querySelector('form.srvadd')).toBeNull();
  });

  it('lists services and refuses a duplicate name with the exact message', async () => {
    const el = await open('OWNER');
    expect(el.querySelector<HTMLInputElement>('[aria-label="Price of Dressing"]')!.value).toBe('150');
    type(el, 'input[aria-label="Service name"]', 'dressing');
    type(el, 'input[aria-label="Service price"]', '200');
    el.querySelector<HTMLButtonElement>('form.srvadd button[type="submit"]')!.click();
    http.expectOne(`${OPD}/services`).flush(
      { type: 'about:blank', title: 'Conflict', status: 409, detail: 'duplicate' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    expect(DUPLICATE_SERVICE).toBe('That service is already in the list');
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('That service is already in the list');
  });

  it('asks for a price above zero when adding a service', async () => {
    const el = await open('OWNER');
    type(el, 'input[aria-label="Service name"]', 'Suture removal');
    type(el, 'input[aria-label="Service price"]', '0');
    el.querySelector<HTMLButtonElement>('form.srvadd button[type="submit"]')!.click();
    fixture.detectChanges();
    http.expectNone(`${OPD}/services`);
    expect(el.textContent).toContain('Enter a price above ₹0');
  });

  it('adds a service with its category', async () => {
    const el = await open('OWNER');
    type(el, 'input[aria-label="Service name"]', 'Suture removal');
    type(el, 'input[aria-label="Service price"]', '250');
    el.querySelector<HTMLButtonElement>('form.srvadd button[type="submit"]')!.click();
    const req = http.expectOne(`${OPD}/services`);
    expect(req.request.body).toEqual({ name: 'Suture removal', category: 'Procedure', priceRupees: 250 });
    req.flush({ id: 's2', ...req.request.body, active: true });
    fixture.detectChanges();
    expect(el.querySelector<HTMLInputElement>('[aria-label="Price of Suture removal"]')).toBeTruthy();
  });

  it('removes a service by switching it off, and can show and restore it', async () => {
    const el = await open('OWNER');
    el.querySelector<HTMLButtonElement>('[aria-label="Remove Dressing"]')!.click();
    const req = http.expectOne(`${OPD}/services/s1`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ active: false });
    req.flush({ ...DRESSING, active: false });
    fixture.detectChanges();
    expect(el.querySelector('[aria-label="Price of Dressing"]')).toBeNull();
    Array.from(el.querySelectorAll('button'))
      .find((b) => b.textContent?.includes('Show removed services'))!
      .click();
    fixture.detectChanges();
    expect(el.querySelector('[aria-label="Add Dressing back"]')).toBeTruthy();
  });
});
