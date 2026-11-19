import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { OWNER_CONTEXT, PHARMACIST_CONTEXT, SETTINGS } from '../pharmacy.fixtures';
import { PharmacyContext, UnitSettings } from '../pharmacy.model';
import { GSTIN_PATTERN, UnitSettingsCard, integrationChangeText } from './unit-settings.card';

const BASE = '/api/v1/pharmacy';

describe('UnitSettingsCard', () => {
  let fixture: ComponentFixture<UnitSettingsCard>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext, settings: UnitSettings = SETTINGS): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(UnitSettingsCard);
    fixture.detectChanges();
    http.expectOne(`${BASE}/unit-settings`).flush(settings);
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const input = (el: HTMLElement, id: string) => q(el, id) as HTMLInputElement;

  function type(el: HTMLElement, id: string, value: string): void {
    const field = input(el, id);
    field.value = value;
    field.dispatchEvent(new Event('input'));
  }

  function chooseMode(label: 'Integrated' | 'Standalone'): void {
    (fixture.componentInstance as unknown as { form: UnitSettingsCard['form'] }).form.controls.integrationMode.setValue(
      label === 'Integrated' ? 'INTEGRATED' : 'STANDALONE',
    );
  }

  afterEach(() => http.verify());

  it('shows FEFO On and Negative stock Blocked as facts with no switch', async () => {
    const el = await open(OWNER_CONTEXT);
    expect(q(el, 'fact-fefo')?.textContent).toContain('FEFO: On');
    expect(q(el, 'fact-negative')?.textContent).toContain('Negative stock: Blocked');
    expect(el.querySelectorAll('[role="switch"], mat-slide-toggle, input[type="checkbox"]').length).toBe(0);
  });

  it('shows the owner the unit name and the saved values', async () => {
    const el = await open(OWNER_CONTEXT);
    expect(q(el, 'unit-name')?.textContent).toBe('Test Clinic Baner');
    expect(input(el, 'f-gstin').value).toBe('27AAAAA0000A1Z5');
    expect(input(el, 'f-licence').value).toBe('TEST-DL-0001');
    expect(input(el, 'f-valid-till').value).toBe('31 Mar 2028');
  });

  it('shows the licence date as "31 Mar 2028", keeps 2028-03-31 in the form, and parses a typed date back to YYYY-MM-DD', async () => {
    const el = await open(OWNER_CONTEXT);
    const form = fixture.componentInstance['form'];
    expect(el.querySelector('input[type="date"]')).toBeNull();
    expect(form.controls.drugLicenceValidTill.value).toBe('2028-03-31');
    type(el, 'f-valid-till', '24 Oct 2026');
    expect(form.controls.drugLicenceValidTill.value).toBe('2026-10-24');
    q(el, 'save-settings')?.click();
    const put = http.expectOne((r) => r.method === 'PUT');
    expect((put.request.body as { drugLicenceValidTill: string }).drugLicenceValidTill).toBe('2026-10-24');
    put.flush({ ...SETTINGS, drugLicenceValidTill: '2026-10-24', version: 4 });
    http.expectOne(`${BASE}/context`).flush(OWNER_CONTEXT);
  });

  it('sends every contract-required field, null included, when GSTIN, licence and date are all cleared', async () => {
    const el = await open(OWNER_CONTEXT);
    type(el, 'f-gstin', '');
    type(el, 'f-licence', '');
    type(el, 'f-valid-till', '');
    q(el, 'save-settings')?.click();
    const put = http.expectOne((r) => r.method === 'PUT');
    expect(put.request.body).toEqual({
      version: 3,
      gstin: null,
      drugLicenceNo: null,
      drugLicenceValidTill: null,
      integrationMode: 'INTEGRATED',
    });
    put.flush({ ...SETTINGS, gstin: null, drugLicenceNo: null, drugLicenceValidTill: null, version: 4 });
    http.expectOne(`${BASE}/context`).flush(OWNER_CONTEXT);
  });

  it('shows a first visit as empty fields and Integrated', async () => {
    const el = await open(OWNER_CONTEXT, {
      ...SETTINGS,
      gstin: null,
      drugLicenceNo: null,
      drugLicenceValidTill: null,
      saved: false,
      version: 0,
    });
    expect(input(el, 'f-gstin').value).toBe('');
    expect(fixture.componentInstance['form'].getRawValue().integrationMode).toBe('INTEGRATED');
  });

  it('shows a pharmacist the values read-only, with "Not set" and "Expired"', async () => {
    const el = await open(PHARMACIST_CONTEXT, {
      ...SETTINGS,
      gstin: null,
      drugLicenceNo: null,
      drugLicenceValidTill: '2026-10-24',
      drugLicenceExpired: true,
    });
    expect(q(el, 'f-gstin')).toBeNull();
    expect(q(el, 'save-settings')).toBeNull();
    expect(q(el, 'v-gstin')?.textContent).toBe('Not set');
    expect(q(el, 'v-licence')?.textContent).toBe('Not set');
    expect(q(el, 'v-valid-till')?.textContent).toContain('24 Oct 2026');
    expect(q(el, 'v-valid-till')?.textContent).toContain('Expired');
    expect(q(el, 'v-mode')?.textContent).toBe('Integrated');
  });

  it('refuses a GSTIN that is not 15 characters of the pattern and sends nothing', async () => {
    const el = await open(OWNER_CONTEXT);
    type(el, 'f-gstin', '27AAAAA0000');
    q(el, 'save-settings')?.click();
    await settle();
    expect(el.textContent).toContain('Enter a 15-character GSTIN');
    http.expectNone(`${BASE}/unit-settings`);
  });

  it('accepts an empty GSTIN as "not set" and sends null', async () => {
    const el = await open(OWNER_CONTEXT);
    type(el, 'f-gstin', '');
    q(el, 'save-settings')?.click();
    const put = http.expectOne((r) => r.method === 'PUT');
    expect((put.request.body as { gstin: string | null }).gstin).toBeNull();
    put.flush({ ...SETTINGS, gstin: null, version: 4 });
    http.expectOne(`${BASE}/context`).flush(OWNER_CONTEXT);
  });

  it('matches the GSTIN pattern of the contract', () => {
    expect(GSTIN_PATTERN.test('27AAAAA0000A1Z5')).toBe(true);
    expect(GSTIN_PATTERN.test('27AAAAA0000A0Z5')).toBe(false);
    expect(GSTIN_PATTERN.test('abc')).toBe(false);
  });

  it('upper-cases the GSTIN, sends the version and only the contract fields, then shows Saved', async () => {
    const el = await open(OWNER_CONTEXT);
    type(el, 'f-gstin', '27aaaaa0000a1z5');
    type(el, 'f-licence', '  TEST-DL-0002 ');
    q(el, 'save-settings')?.click();
    const put = http.expectOne((r) => r.method === 'PUT');
    expect(put.request.body).toEqual({
      version: 3,
      gstin: '27AAAAA0000A1Z5',
      drugLicenceNo: 'TEST-DL-0002',
      drugLicenceValidTill: '2028-03-31',
      integrationMode: 'INTEGRATED',
    });
    expect(q(el, 'settings-saved')).toBeNull();
    put.flush({ ...SETTINGS, drugLicenceNo: 'TEST-DL-0002', version: 4 });
    http.expectOne(`${BASE}/context`).flush(OWNER_CONTEXT);
    await settle();
    expect(q(el, 'settings-saved')?.textContent).toBe('Saved');
  });

  it('disables the save button while the request runs', async () => {
    const el = await open(OWNER_CONTEXT);
    q(el, 'save-settings')?.click();
    await settle();
    expect((q(el, 'save-settings') as HTMLButtonElement).disabled).toBe(true);
    http.expectOne((r) => r.method === 'PUT').flush(SETTINGS);
    http.expectOne(`${BASE}/context`).flush(OWNER_CONTEXT);
  });

  it('asks before Integration mode changes, and sends nothing until the owner confirms', async () => {
    const el = await open(OWNER_CONTEXT);
    const dialog = vi
      .spyOn(TestBed.inject(MatDialog), 'open')
      .mockReturnValue({ afterClosed: () => of(false) } as never);
    chooseMode('Standalone');
    q(el, 'save-settings')?.click();
    const config = dialog.mock.calls[0][1] as { data: { body: string } };
    expect(config.data.body).toBe('Standalone hides OPD and IPD links. Existing records are kept.');
    http.expectNone((r) => r.method === 'PUT');
  });

  it('saves the new mode after the owner confirms, and reads the context again', async () => {
    const el = await open(OWNER_CONTEXT);
    vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(true) } as never);
    chooseMode('Standalone');
    q(el, 'save-settings')?.click();
    const put = http.expectOne((r) => r.method === 'PUT');
    expect((put.request.body as { integrationMode: string }).integrationMode).toBe('STANDALONE');
    put.flush({ ...SETTINGS, integrationMode: 'STANDALONE', version: 4 });
    http.expectOne(`${BASE}/context`).flush(OWNER_CONTEXT);
    await settle();
    expect(q(el, 'standalone-note')?.textContent).toContain('Standalone hides OPD and IPD links.');
  });

  it('has a sentence for each direction of the change', () => {
    expect(integrationChangeText('STANDALONE')).toBe(
      'Standalone hides OPD and IPD links. Existing records are kept.',
    );
    expect(integrationChangeText('INTEGRATED')).toContain('Integrated shows OPD and IPD links');
  });

  it('says IPD links stay hidden when Integrated but the clinic IPD module is off', async () => {
    const el = await open(OWNER_CONTEXT, { ...SETTINGS, ipdAvailable: false });
    expect(q(el, 'ipd-off-note')).toBeTruthy();
  });

  it('keeps what was typed and says the standard line when the save cannot reach the server', async () => {
    const el = await open(OWNER_CONTEXT);
    type(el, 'f-licence', 'TEST-DL-0009');
    q(el, 'save-settings')?.click();
    http.expectOne((r) => r.method === 'PUT').error(new ProgressEvent('error'));
    await settle();
    expect(q(el, 'settings-error')?.textContent).toContain('Could not save. Check your connection and try again.');
    expect(input(el, 'f-licence').value).toBe('TEST-DL-0009');
    expect(q(el, 'settings-saved')).toBeNull();
  });

  it('puts a server field message on the GSTIN field', async () => {
    const el = await open(OWNER_CONTEXT);
    q(el, 'save-settings')?.click();
    http.expectOne((r) => r.method === 'PUT').flush(
      {
        type: 'https://sahaj.in/problems/validation',
        title: 'Check the form',
        status: 400,
        fieldErrors: [{ field: 'gstin', message: 'Enter a 15-character GSTIN' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    expect(el.textContent).toContain('Enter a 15-character GSTIN');
  });

  it('on settings-changed shows the server sentence and the values that are now current', async () => {
    const el = await open(OWNER_CONTEXT);
    q(el, 'save-settings')?.click();
    http.expectOne((r) => r.method === 'PUT').flush(
      {
        type: 'https://sahaj.in/problems/settings-changed',
        title: 'Changed by someone else',
        status: 409,
        detail: 'Someone else saved this a moment ago. Check the current values and save again.',
        current: {},
      },
      { status: 409, statusText: 'Conflict' },
    );
    http.expectOne(`${BASE}/unit-settings`).flush({ ...SETTINGS, drugLicenceNo: 'TEST-DL-7777', version: 4 });
    await settle();
    expect(q(el, 'settings-error')?.textContent).toContain('Someone else saved this a moment ago.');
    expect(input(el, 'f-licence').value).toBe('TEST-DL-7777');
  });

  it('shows "Could not load. Try again." with a Try again button', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(OWNER_CONTEXT);
    fixture = TestBed.createComponent(UnitSettingsCard);
    fixture.detectChanges();
    http.expectOne(`${BASE}/unit-settings`).error(new ProgressEvent('error'));
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    expect(q(el, 'settings-load-error')?.textContent).toContain('Could not load. Try again.');
    Array.from(el.querySelectorAll('button'))
      .find((b) => b.textContent?.includes('Try again'))
      ?.click();
    http.expectOne(`${BASE}/unit-settings`).flush(SETTINGS);
  });
});
