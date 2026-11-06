import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { IpdState } from '../modules/modules.model';
import { IpdLayout } from './ipd.model';
import { IpRatesPage } from './iprates.page';

const IPD = '/api/v1/ipd';

const LAYOUT: IpdLayout = {
  state: 'on',
  roomLevel: true,
  bedLimit: 25,
  bedCount: 3,
  wards: [],
  categories: [
    { key: 'gen', name: 'General', ratePaise: 80000, multiplier: 1, beds: 2 },
    { key: 'semi', name: 'Semi-private', ratePaise: 150000, multiplier: 1.25, beds: 0 },
    { key: 'pvt', name: 'Private', ratePaise: 250000, multiplier: 1.5, beds: 1 },
    { key: 'icu', name: 'ICU', ratePaise: 500000, multiplier: 2, beds: 0 },
  ],
  charges: { bedDayCut: 'midnight', nursingPaise: 30000, visitPaise: 50000 },
};

describe('IpRatesPage', () => {
  let fixture: ComponentFixture<IpRatesPage>;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.fn>;

  async function open(role: Role, state: IpdState = 'on') {
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
    fixture = TestBed.createComponent(IpRatesPage);
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
      http.expectOne(`${IPD}/layout`).flush({ ...LAYOUT, state });
      await fixture.whenStable();
      fixture.detectChanges();
    }
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const text = () => el().textContent ?? '';
  const input = (label: string) => el().querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!;
  const change = (i: HTMLInputElement, value: string) => {
    i.value = value;
    i.dispatchEvent(new Event('change'));
  };

  afterEach(() => http.verify());

  it('lists the four categories with rupees, multipliers and bed counts', async () => {
    await open('OWNER');
    expect(el().querySelectorAll('.cattr:not(.cath)').length).toBe(4);
    expect(input('Semi-private bed charge a day, in rupees').value).toBe('1500');
    expect(input('Semi-private rate on services').value).toBe('1.25');
    expect(text()).toContain('Bed day counted');
    expect(text()).toContain('"Rate on services" multiplies doctor visits');
    expect(input('Nursing charge per day, in rupees').value).toBe('300');
    expect(input('Doctor visit per day, in rupees').value).toBe('500');
  });

  it('saves a bed charge in paise at once', async () => {
    await open('OWNER');
    change(input('General bed charge a day, in rupees'), '900');
    const req = http.expectOne(`${IPD}/categories/gen`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ ratePaise: 90000, multiplier: 1 });
    req.flush(LAYOUT);
  });

  it('saves a multiplier and keeps the charge', async () => {
    await open('OWNER');
    change(input('ICU rate on services'), '2.5');
    const req = http.expectOne(`${IPD}/categories/icu`);
    expect(req.request.body).toEqual({ ratePaise: 500000, multiplier: 2.5 });
    req.flush(LAYOUT);
  });

  it('puts the old value back and sends nothing for an invalid entry', async () => {
    await open('OWNER');
    const rate = input('General bed charge a day, in rupees');
    change(rate, '1,00,001');
    expect(rate.value).toBe('800');
    const mult = input('Private rate on services');
    change(mult, '9');
    expect(mult.value).toBe('1.5');
    expect(snack).toHaveBeenCalledTimes(2);
  });

  it('sends the charges together when the bed day or nursing charge changes', async () => {
    await open('OWNER');
    const toggle = Array.from(el().querySelectorAll<HTMLButtonElement>('mat-button-toggle button')).find((b) =>
      b.textContent?.includes('Every 24 hours'),
    )!;
    toggle.click();
    const first = http.expectOne(`${IPD}/charges`);
    expect(first.request.body).toEqual({ bedDayCut: '24h', nursingPaise: 30000, visitPaise: 50000 });
    first.flush(LAYOUT);
    await fixture.whenStable();

    change(input('Nursing charge per day, in rupees'), '350');
    const second = http.expectOne(`${IPD}/charges`);
    expect(second.request.body).toEqual({ bedDayCut: 'midnight', nursingPaise: 35000, visitPaise: 50000 });
    second.flush(LAYOUT);
  });

  it('shows the API message and restores the value when saving fails', async () => {
    await open('OWNER');
    const rate = input('General bed charge a day, in rupees');
    change(rate, '900');
    http.expectOne(`${IPD}/categories/gen`).flush(
      { type: 'ipd-readonly', title: 'Read-only', status: 409, detail: 'IPD is read-only. Nothing can be changed.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await fixture.whenStable();
    expect(snack).toHaveBeenCalledWith('IPD is read-only. Nothing can be changed.', 'Close', expect.anything());
    expect(rate.value).toBe('800');
  });

  it('is read-only for a non-owner', async () => {
    await open('NURSE');
    expect(text()).toContain('Only the owner can change bed charges');
    expect(input('General bed charge a day, in rupees').disabled).toBe(true);
    expect(input('Nursing charge per day, in rupees').disabled).toBe(true);
  });

  it('is read-only when IPD is read-only', async () => {
    await open('OWNER', 'readonly');
    expect(text()).toContain('IPD is read-only. Nothing can be changed.');
    expect(input('ICU rate on services').disabled).toBe(true);
  });

  it('shows the off notice instead of the page', async () => {
    await open('OWNER', 'off');
    expect(text()).toContain('not on for your clinic');
    expect(el().querySelector('.cattbl')).toBeNull();
  });
});
