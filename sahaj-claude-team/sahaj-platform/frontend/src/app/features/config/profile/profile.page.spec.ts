import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { ClinicProfile, Holiday } from '../platform/clinic.model';
import { ProfilePage } from './profile.page';

const BASE = '/api/v1/platform';

const PROFILE: ClinicProfile = {
  name: 'Shree Sai Clinic',
  nameLocal: 'श्री साई क्लिनिक',
  address: 'Ravet, Pune',
  phone: '02012345678',
  slug: 'shreesai',
  city: 'Pune',
  brand: 'blue',
};
const DIWALI: Holiday = { id: 'h1', date: '2026-11-09', name: 'Diwali', type: 'CLOSED' };

describe('ProfilePage', () => {
  let fixture: ComponentFixture<ProfilePage>;
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
    fixture = TestBed.createComponent(ProfilePage);
    fixture.detectChanges();
    http.expectOne(`${BASE}/clinic/profile`).flush(PROFILE);
    http.expectOne(`${BASE}/clinic/holidays`).flush([DIWALI]);
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

  it('shows the details, the read-only web address and the holidays', async () => {
    const el = await open('OWNER');
    expect(el.querySelector<HTMLInputElement>('input[formcontrolname="name"]')!.value).toBe('Shree Sai Clinic');
    expect(el.textContent).toContain('shreesai.sahaj.in');
    expect(el.textContent).toContain('Mon 9 Nov');
    expect(el.textContent).toContain('Diwali');
  });

  it('saves a changed clinic name when the field is left, keeping the other fields', async () => {
    const el = await open('OWNER');
    const input = type(el, 'input[formcontrolname="name"]', 'Shree Sai Family Clinic');
    input.dispatchEvent(new Event('change'));
    const req = http.expectOne(`${BASE}/clinic/profile`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.name).toBe('Shree Sai Family Clinic');
    expect(req.request.body.city).toBe('Pune');
    expect(req.request.body.slug).toBeUndefined();
    req.flush({ ...PROFILE, name: 'Shree Sai Family Clinic' });
  });

  it('does not call the server when the clinic name is empty', async () => {
    const el = await open('OWNER');
    const input = type(el, 'input[formcontrolname="name"]', '   ');
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    http.expectNone(`${BASE}/clinic/profile`);
    expect(el.textContent).toContain('Enter the clinic name');
  });

  it('adds a holiday and keeps the list in date order', async () => {
    const el = await open('OWNER');
    type(el, 'input[aria-label="Holiday date"]', '2026-11-02');
    type(el, 'input[aria-label="Holiday name"]', 'Bhau beej');
    el.querySelector<HTMLButtonElement>('form.holadd button[type="submit"]')!.click();
    const req = http.expectOne(`${BASE}/clinic/holidays`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ date: '2026-11-02', name: 'Bhau beej', type: 'CLOSED' });
    req.flush({ id: 'h2', date: '2026-11-02', name: 'Bhau beej', type: 'CLOSED' });
    fixture.detectChanges();
    const names = Array.from(el.querySelectorAll('.li .main b')).map((b) => b.textContent);
    expect(names).toEqual(['Bhau beej', 'Diwali']);
  });

  it('refuses a repeated holiday date with a clear message', async () => {
    const el = await open('OWNER');
    type(el, 'input[aria-label="Holiday date"]', '2026-11-09');
    type(el, 'input[aria-label="Holiday name"]', 'Lakshmi puja');
    el.querySelector<HTMLButtonElement>('form.holadd button[type="submit"]')!.click();
    http.expectOne(`${BASE}/clinic/holidays`).flush(
      { type: 'about:blank', title: 'Conflict', status: 409 },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('already set for that date');
  });

  it('removes a holiday', async () => {
    const el = await open('OWNER');
    el.querySelector<HTMLButtonElement>('[aria-label="Remove Diwali"]')!.click();
    const req = http.expectOne(`${BASE}/clinic/holidays/h1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
    fixture.detectChanges();
    expect(el.textContent).toContain('No holidays added.');
  });

  it('is read-only for a nurse: fields disabled, no add or remove', async () => {
    const el = await open('NURSE');
    expect(el.querySelector<HTMLInputElement>('input[formcontrolname="name"]')!.disabled).toBe(true);
    expect(el.querySelector('form.holadd')).toBeNull();
    expect(el.querySelector('[aria-label="Remove Diwali"]')).toBeNull();
    expect(el.querySelector<HTMLButtonElement>('.swatch')!.disabled).toBe(true);
  });
});
