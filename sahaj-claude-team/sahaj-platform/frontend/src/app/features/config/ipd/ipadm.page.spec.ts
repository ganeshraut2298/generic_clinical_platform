import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { AdmissionRules, PackageList, TransferRules } from '../../ipd/ipd.model';
import { IpdState } from '../modules/modules.model';
import { IpAdmPage } from './ipadm.page';
import { PackagesStore } from './packages.store';

const IPD = '/api/v1/ipd';

const RULES: AdmissionRules = {
  depositPaise: { planned: 1000000, emergency: 500000, daycare: 300000, maternity: 1500000 },
  categoryAddPaise: { gen: 0, semi: 200000, pvt: 500000, icu: 1000000 },
  emergencyWithoutDeposit: true,
  alertPercent: 80,
  confirmed: false,
};

const TRANSFER: TransferRules = { upgradeNeedsOwner: true, newRateFrom: 'same' };
const PACKAGES: PackageList = {
  items: [
    { id: 'p1', name: 'Appendicectomy', pricePaise: 4500000, includedDays: 3 },
    { id: 'p2', name: 'Cataract (day-care)', pricePaise: 1800000, includedDays: 0 },
    { id: 'p3', name: 'Observation', pricePaise: 12500000, includedDays: 1 },
  ],
};
const NO_PACKAGES: PackageList = { items: [] };
const FAIL = { type: 'about:blank', title: 'Error', status: 500, detail: 'Try later.' };

describe('IpAdmPage', () => {
  let fixture: ComponentFixture<IpAdmPage>;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.fn>;

  async function open(
    role: Role,
    state: IpdState = 'on',
    rules: AdmissionRules = RULES,
    transfer: TransferRules | 'fail' = TRANSFER,
    packages: PackageList | 'fail' | 'forbidden' | 'module-off' = NO_PACKAGES,
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
    fixture = TestBed.createComponent(IpAdmPage);
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
      http.expectOne(`${IPD}/admission-rules`).flush(rules);
      const call = http.expectOne(`${IPD}/transfer-rules`);
      if (transfer === 'fail') {
        call.flush(FAIL, { status: 500, statusText: 'Server Error' });
      } else {
        call.flush(transfer);
      }
      const pk = http.expectOne(`${IPD}/packages`);
      if (packages === 'fail') {
        pk.flush(FAIL, { status: 500, statusText: 'Server Error' });
      } else if (packages === 'forbidden' || packages === 'module-off') {
        pk.flush(
          {
            type: `https://sahaj.example/problems/${packages}`,
            title: 'Refused',
            status: 403,
            detail: 'Not allowed.',
          },
          { status: 403, statusText: 'Forbidden' },
        );
      } else {
        pk.flush(packages);
      }
      await fixture.whenStable();
      fixture.detectChanges();
    }
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const text = () => el().textContent ?? '';
  const input = (label: string) =>
    el().querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!;
  const change = (i: HTMLInputElement, value: string) => {
    i.value = value;
    i.dispatchEvent(new Event('change'));
  };
  const button = (label: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find(
      (b) => b.textContent!.trim() === label,
    );
  const stepper = (label: string) =>
    el().querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)!;
  const sw = () => el().querySelector<HTMLButtonElement>('button[role=switch]')!;

  afterEach(() => http.verify());

  it('shows each type, the category add-ons, the switch and the alert level', async () => {
    await open('OWNER');
    expect(text()).toContain('Minimum deposit');
    expect(text()).toContain('Planned admission');
    expect(text()).toContain('Base deposit');
    expect(input('Planned minimum deposit, in rupees').value).toBe('10000');
    expect(input('Emergency minimum deposit, in rupees').value).toBe('5000');
    expect(input('Day-care minimum deposit, in rupees').value).toBe('3000');
    expect(input('Maternity minimum deposit, in rupees').value).toBe('15000');
    expect(text()).toContain('Added for the bed category');
    expect(input('General added to the minimum deposit, in rupees').value).toBe('0');
    expect(input('Semi-private added to the minimum deposit, in rupees').value).toBe('2000');
    expect(input('Private added to the minimum deposit, in rupees').value).toBe('5000');
    expect(input('ICU added to the minimum deposit, in rupees').value).toBe('10000');
    expect(text()).toContain('Admit first, take the deposit the same day');
    expect(sw().getAttribute('aria-checked')).toBe('true');
    expect(text()).toContain('80%');
    expect(text()).toContain('Bed transfer rules');
    expect(text()).toContain('Packages');
  });

  it('saves a base deposit in paise at once', async () => {
    await open('OWNER');
    change(input('Planned minimum deposit, in rupees'), '12000');
    const req = http.expectOne(`${IPD}/admission-rules`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({
      depositPaise: { planned: 1200000, emergency: 500000, daycare: 300000, maternity: 1500000 },
      categoryAddPaise: RULES.categoryAddPaise,
      emergencyWithoutDeposit: true,
      alertPercent: 80,
    });
    expect(req.request.body.confirmed).toBeUndefined();
    req.flush({ ...RULES, depositPaise: { ...RULES.depositPaise, planned: 1200000 } });
  });

  it('saves a category add-on in paise', async () => {
    await open('OWNER');
    change(input('ICU added to the minimum deposit, in rupees'), '15000');
    const req = http.expectOne(`${IPD}/admission-rules`);
    expect(req.request.body.categoryAddPaise).toEqual({
      gen: 0,
      semi: 200000,
      pvt: 500000,
      icu: 1500000,
    });
    req.flush(RULES);
  });

  it('puts the old value back and sends nothing for an invalid entry', async () => {
    await open('OWNER');
    const field = input('Planned minimum deposit, in rupees');
    change(field, '1000001');
    expect(field.value).toBe('10000');
    change(field, '12.5');
    expect(field.value).toBe('10000');
    expect(snack).toHaveBeenCalledTimes(2);
    change(field, '10000');
    expect(snack).toHaveBeenCalledTimes(2);
  });

  it('toggles Emergency without deposit', async () => {
    await open('OWNER');
    sw().click();
    const req = http.expectOne(`${IPD}/admission-rules`);
    expect(req.request.body.emergencyWithoutDeposit).toBe(false);
    req.flush({ ...RULES, emergencyWithoutDeposit: false });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(sw().getAttribute('aria-checked')).toBe('false');
  });

  it('steps the alert level by 5 between 50 and 100', async () => {
    await open('OWNER');
    stepper('Raise the alert level').click();
    const up = http.expectOne(`${IPD}/admission-rules`);
    expect(up.request.body.alertPercent).toBe(85);
    up.flush({ ...RULES, alertPercent: 85 });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(text()).toContain('85%');
    stepper('Lower the alert level').click();
    const down = http.expectOne(`${IPD}/admission-rules`);
    expect(down.request.body.alertPercent).toBe(80);
    down.flush(RULES);
  });

  it('disables the stepper at its ends', async () => {
    await open('OWNER', 'on', { ...RULES, alertPercent: 100 });
    expect(stepper('Raise the alert level').disabled).toBe(true);
    expect(stepper('Lower the alert level').disabled).toBe(false);
  });

  it('confirms the rules and then shows the Confirmed pill', async () => {
    await open('OWNER');
    expect(el().querySelector('.pill')).toBeNull();
    button('Confirm these rules')!.click();
    const req = http.expectOne(`${IPD}/admission-rules/confirm`);
    expect(req.request.method).toBe('POST');
    req.flush({ ...RULES, confirmed: true });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(snack).toHaveBeenCalledWith('Deposit rules saved', 'Close', expect.anything());
    expect(el().querySelector('.pill')!.textContent).toContain('Confirmed');
    expect(button('Confirm these rules')).toBeUndefined();
  });

  it('shows the Confirmed pill at once when the rules are confirmed, and stays confirmed after a change', async () => {
    await open('OWNER', 'on', { ...RULES, confirmed: true });
    expect(el().querySelector('.pill')!.textContent).toContain('Confirmed');
    change(input('Planned minimum deposit, in rupees'), '9000');
    http.expectOne(`${IPD}/admission-rules`).flush({ ...RULES, confirmed: true });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('.pill')!.textContent).toContain('Confirmed');
  });

  it('shows the API message and restores the value when saving fails', async () => {
    await open('OWNER');
    const field = input('Emergency minimum deposit, in rupees');
    change(field, '6000');
    http
      .expectOne(`${IPD}/admission-rules`)
      .flush(
        { type: 'validation', title: 'Invalid', status: 400, detail: 'Enter whole rupees.' },
        { status: 400, statusText: 'Bad Request' },
      );
    await fixture.whenStable();
    expect(snack).toHaveBeenCalledWith('Enter whole rupees.', 'Close', expect.anything());
    expect(field.value).toBe('5000');
  });

  it('is read-only for a non-owner', async () => {
    await open('FRONT_DESK');
    expect(text()).toContain('Only the owner can change the deposit rules');
    expect(input('Planned minimum deposit, in rupees').disabled).toBe(true);
    expect(sw().disabled).toBe(true);
    expect(stepper('Raise the alert level').disabled).toBe(true);
    expect(button('Confirm these rules')).toBeUndefined();
  });

  it('is read-only when IPD is read-only', async () => {
    await open('OWNER', 'readonly');
    expect(text()).toContain('IPD is read-only. Nothing can be changed.');
    expect(input('Planned minimum deposit, in rupees').disabled).toBe(true);
    expect(button('Confirm these rules')!.disabled).toBe(true);
  });

  it('shows the off notice instead of the page', async () => {
    await open('OWNER', 'off');
    expect(text()).toContain('not on for your clinic');
    expect(el().querySelector('#f-ipadm')).toBeNull();
  });

  describe('Bed transfer rules (SAH-26)', () => {
    const choice = (label: string) =>
      Array.from(el().querySelectorAll<HTMLButtonElement>('mat-button-toggle button')).find((b) =>
        b.textContent?.includes(label),
      )!;
    const pressed = (label: string) =>
      choice(label).closest('mat-button-toggle')!.classList.contains('mat-button-toggle-checked');
    const settle = async () => {
      await fixture.whenStable();
      fixture.detectChanges();
    };

    it('shows the panel under Minimum deposit with both rules and their hints, defaults chosen', async () => {
      await open('OWNER');
      const headings = Array.from(el().querySelectorAll('h2')).map((h) => h.textContent!.trim());
      expect(headings).toEqual(['Minimum deposit', 'Bed transfer rules', 'Packages']);
      expect(text()).toContain('Moving to a higher category');
      expect(text()).toContain('For example General to Private');
      expect(text()).toContain('New bed rate starts');
      expect(text()).toContain('On the day of the transfer');
      expect(pressed('Owner approves')).toBe(true);
      expect(pressed('Front desk can do it')).toBe(false);
      expect(pressed('Same day')).toBe(true);
      expect(pressed('Next day')).toBe(false);
    });

    it('reads the saved values', async () => {
      await open('OWNER', 'on', RULES, { upgradeNeedsOwner: false, newRateFrom: 'next' });
      expect(pressed('Front desk can do it')).toBe(true);
      expect(pressed('Next day')).toBe(true);
    });

    it('saves the upgrade rule at once with both values', async () => {
      await open('OWNER');
      choice('Front desk can do it').click();
      const req = http.expectOne(`${IPD}/transfer-rules`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({ upgradeNeedsOwner: false, newRateFrom: 'same' });
      req.flush({ upgradeNeedsOwner: false, newRateFrom: 'same' });
      await settle();
      expect(pressed('Front desk can do it')).toBe(true);
    });

    it('saves the new rate rule at once with both values', async () => {
      await open('OWNER');
      choice('Next day').click();
      const req = http.expectOne(`${IPD}/transfer-rules`);
      expect(req.request.body).toEqual({ upgradeNeedsOwner: true, newRateFrom: 'next' });
      req.flush({ upgradeNeedsOwner: true, newRateFrom: 'next' });
      await settle();
      expect(pressed('Next day')).toBe(true);
    });

    it('sends nothing when the chosen value is already saved', async () => {
      await open('OWNER');
      choice('Same day').click();
      choice('Owner approves').click();
      await settle();
      expect(pressed('Same day')).toBe(true);
    });

    it('shows the server message and puts the choice back when saving fails (409 ipd-readonly)', async () => {
      await open('OWNER');
      choice('Next day').click();
      http
        .expectOne(`${IPD}/transfer-rules`)
        .flush(
          {
            type: 'ipd-readonly',
            title: 'Read-only',
            status: 409,
            detail: 'IPD is read-only. Nothing can be changed.',
          },
          { status: 409, statusText: 'Conflict' },
        );
      await settle();
      expect(snack).toHaveBeenCalledWith(
        'IPD is read-only. Nothing can be changed.',
        'Close',
        expect.anything(),
      );
      expect(pressed('Same day')).toBe(true);
      expect(pressed('Next day')).toBe(false);
    });

    it('is read-only for a nurse, who can still read the values', async () => {
      await open('NURSE', 'on', RULES, { upgradeNeedsOwner: false, newRateFrom: 'next' });
      expect(text()).toContain('Only the owner can change the bed transfer rules');
      expect(pressed('Front desk can do it')).toBe(true);
      expect(pressed('Next day')).toBe(true);
      expect(choice('Same day').disabled).toBe(true);
      expect(choice('Owner approves').disabled).toBe(true);
      choice('Same day').click();
    });

    it('is read-only for the front desk', async () => {
      await open('FRONT_DESK');
      expect(choice('Next day').disabled).toBe(true);
      choice('Next day').click();
    });

    it('is read-only for a doctor', async () => {
      await open('DOCTOR');
      expect(choice('Front desk can do it').disabled).toBe(true);
    });

    it('is read-only for the owner when IPD is read-only', async () => {
      await open('OWNER', 'readonly');
      expect(choice('Next day').disabled).toBe(true);
      expect(choice('Owner approves').disabled).toBe(true);
    });

    it('shows an error with Try again when the rules cannot be loaded, and the deposit panel stays', async () => {
      await open('OWNER', 'on', RULES, 'fail');
      expect(text()).toContain('Try later.');
      expect(text()).toContain('Minimum deposit');
      expect(el().querySelector('mat-button-toggle')).toBeNull();
      button('Try again')!.click();
      http.expectOne(`${IPD}/transfer-rules`).flush(TRANSFER);
      await settle();
      expect(pressed('Same day')).toBe(true);
    });
  });

  describe('Packages panel (SAH-29)', () => {
    const settle = async () => {
      await fixture.whenStable();
      fixture.detectChanges();
    };
    const panel = () => el().querySelector<HTMLElement>('#f-ippackages')!;
    const rows = () => Array.from(panel().querySelectorAll<HTMLElement>('.li'));
    const heads = () => Array.from(el().querySelectorAll('h2')).map((h) => h.textContent!.trim());

    it('lists each package under Bed transfer rules: chip, bold name, fixed text, price', async () => {
      await open('OWNER', 'on', RULES, TRANSFER, PACKAGES);
      expect(heads()).toEqual(['Minimum deposit', 'Bed transfer rules', 'Packages']);
      const list = rows();
      expect(list.length).toBe(3);
      expect(list[0].querySelector('.tokchip')!.textContent).toBe('3 days');
      expect(list[0].querySelector('.main b')!.textContent).toBe('Appendicectomy');
      expect(list[0].querySelector('.main small')!.textContent).toBe(
        'Bed, nursing and surgeon included · extra days and exclusions at normal rates',
      );
      expect(list[0].querySelector('b.num')!.textContent).toBe('₹45,000');
      expect(list[1].querySelector('.tokchip')!.textContent).toBe('Day-care');
      expect(list[1].querySelector('b.num')!.textContent).toBe('₹18,000');
      expect(list[2].querySelector('.tokchip')!.textContent).toBe('1 days');
      expect(list[2].querySelector('b.num')!.textContent).toBe('₹1,25,000');
    });

    it('has no add, edit or remove control', async () => {
      await open('OWNER', 'on', RULES, TRANSFER, PACKAGES);
      expect(panel().querySelector('button, input, a, [role=button], [role=switch]')).toBeNull();
    });

    it('shows the heading with an empty list and no new text when there are no packages (OQ-29-3)', async () => {
      await open('OWNER', 'on', RULES, TRANSFER, NO_PACKAGES);
      expect(panel().querySelector('h2')!.textContent).toBe('Packages');
      expect(rows().length).toBe(0);
      expect(panel().textContent!.trim()).toBe('Packages');
    });

    it.each(['FRONT_DESK', 'NURSE', 'DOCTOR'] as const)('is the same for %s', async (role) => {
      await open(role, 'on', RULES, TRANSFER, PACKAGES);
      expect(rows().length).toBe(3);
    });

    it('is listed when IPD is read-only', async () => {
      await open('OWNER', 'readonly', RULES, TRANSFER, PACKAGES);
      expect(rows().length).toBe(3);
    });

    it('asks for nothing and shows no panel when IPD is off', async () => {
      await open('OWNER', 'off');
      expect(el().querySelector('#f-ippackages')).toBeNull();
    });

    it('shows Try again when the packages cannot be read, and the rest of the page stays', async () => {
      await open('OWNER', 'on', RULES, TRANSFER, 'fail');
      expect(panel().textContent).toContain('We could not load the packages. Try again.');
      expect(text()).toContain('Minimum deposit');
      expect(rows().length).toBe(0);
      const retry = Array.from(panel().querySelectorAll('button')).find(
        (b) => b.textContent!.trim() === 'Try again',
      )!;
      retry.click();
      http.expectOne(`${IPD}/packages`).flush(PACKAGES);
      await settle();
      expect(rows().length).toBe(3);
      expect(panel().querySelector('[role=alert]')).toBeNull();
    });

    it('keeps the last list when a later read fails with a server fault (stale data stays)', async () => {
      await open('OWNER', 'on', RULES, TRANSFER, PACKAGES);
      const store = fixture.debugElement.injector.get(PackagesStore);
      store.load();
      http.expectOne(`${IPD}/packages`).flush(FAIL, { status: 500, statusText: 'Server Error' });
      await settle();
      expect(rows().length).toBe(3);
      expect(panel().textContent).toContain('We could not load the packages. Try again.');
    });

    it('keeps the last list when the network fails', async () => {
      await open('OWNER', 'on', RULES, TRANSFER, PACKAGES);
      fixture.debugElement.injector.get(PackagesStore).load();
      http.expectOne(`${IPD}/packages`).error(new ProgressEvent('error'));
      await settle();
      expect(rows().length).toBe(3);
      expect(panel().textContent).toContain('We could not load the packages. Try again.');
    });

    it('clears the list on a 403 forbidden and shows the server words', async () => {
      await open('OWNER', 'on', RULES, TRANSFER, PACKAGES);
      fixture.debugElement.injector.get(PackagesStore).load();
      http
        .expectOne(`${IPD}/packages`)
        .flush(
          {
            type: 'https://sahaj.example/problems/forbidden',
            title: 'Forbidden',
            status: 403,
            detail: 'Not allowed.',
          },
          { status: 403, statusText: 'Forbidden' },
        );
      await settle();
      expect(rows().length).toBe(0);
      expect(panel().textContent).not.toContain('Appendicectomy');
      expect(panel().textContent).toContain('Not allowed.');
      // A 403 forbidden may pass (the role can change), so Try again is still offered.
      expect(tryAgainButtons().length).toBe(1);
    });

    it('clears the list on a 403 module-off and shows the fixed text, not the server words', async () => {
      await open('OWNER', 'on', RULES, TRANSFER, PACKAGES);
      fixture.debugElement.injector.get(PackagesStore).load();
      http
        .expectOne(`${IPD}/packages`)
        .flush(
          {
            type: 'https://sahaj.example/problems/module-off',
            title: 'Off',
            status: 403,
            detail: 'IPD is off.',
          },
          { status: 403, statusText: 'Forbidden' },
        );
      await settle();
      expect(rows().length).toBe(0);
      expect(panel().textContent).toContain(
        'Packages are not available because the module is off.',
      );
      expect(panel().textContent).not.toContain('IPD is off.');
      // IPD is off: Try again cannot help, so the button is not shown and the text does not offer it.
      expect(tryAgainButtons().length).toBe(0);
      expect(panel().querySelector('[role=alert]')!.textContent).not.toContain('Try again');
    });

    it('opens with a refusal: no list at all', async () => {
      await open('FRONT_DESK', 'on', RULES, TRANSFER, 'module-off');
      expect(rows().length).toBe(0);
      expect(panel().querySelector('[role=alert]')!.textContent).toContain(
        'Packages are not available because the module is off.',
      );
      expect(tryAgainButtons().length).toBe(0);
    });

    function tryAgainButtons(): HTMLButtonElement[] {
      return Array.from(panel().querySelectorAll('button')).filter(
        (b) => b.textContent!.trim() === 'Try again',
      );
    }
  });
});
