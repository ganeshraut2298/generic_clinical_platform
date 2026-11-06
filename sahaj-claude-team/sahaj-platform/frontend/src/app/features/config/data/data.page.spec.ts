import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { PrivacySettings, RecordAccess } from '../../privacy/privacy.model';
import { TeamMember } from '../team/team.model';
import { DataPage } from './data.page';

const PLATFORM = '/api/v1/platform';
const ACCESS_URL = '/api/v1/patient/record-access';

const DEFAULTS: PrivacySettings = { consentRequired: true, promoConsent: true, recordCopy: true, hfrId: null };
const DOCTOR: TeamMember = {
  id: 'm-doc',
  name: 'Dr. Asha Kulkarni',
  mobileMasked: '••••••1001',
  role: 'DOCTOR',
  status: 'ACTIVE',
};
const ROWS: RecordAccess[] = [
  { at: '2026-10-24T05:28:00Z', userName: 'Pooja Gaikwad', patientId: 'p1', patientName: 'Sneha Kale' },
];

describe('DataPage (privacy, ABDM and backup)', () => {
  let fixture: ComponentFixture<DataPage>;
  let http: HttpTestingController;

  async function open(
    role: Role,
    options: { settings?: PrivacySettings; nameLocal?: string | null; profileFails?: boolean } = {},
  ) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role, clinic: { name: 'Sai Clinic' } }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DataPage);
    fixture.detectChanges();
    http.expectOne(`${PLATFORM}/privacy`).flush(options.settings ?? DEFAULTS);
    const profile = http.expectOne(`${PLATFORM}/clinic/profile`);
    if (options.profileFails) {
      profile.flush({ title: 'Forbidden', status: 403 }, { status: 403, statusText: 'Forbidden' });
    } else {
      profile.flush({ name: 'Sai Clinic', nameLocal: options.nameLocal ?? undefined });
    }
    http.expectOne(`${PLATFORM}/team`).flush([DOCTOR]);
    if (role === 'OWNER') {
      http.expectOne(`${ACCESS_URL}?limit=5`).flush(ROWS);
    }
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const sw = (label: string) => el().querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)!;

  afterEach(() => http.verify());

  it('shows the four panels for the owner with the clinic consent line', async () => {
    await open('OWNER');
    const text = el().textContent!;
    expect(text).toContain('Patient consent');
    expect(text).toContain('ABDM');
    expect(text).toContain('Records and backup');
    expect(text).toContain('Who opened which record');
    expect(text).toContain('"I agree that Sai Clinic may keep my health records to treat me and send me visit reminders on WhatsApp."');
    expect(text).not.toContain('Only the owner can change privacy settings');
  });

  it('shows the Marathi line only when the clinic has a Marathi name', async () => {
    await open('OWNER', { nameLocal: 'साई क्लिनिक' });
    expect(el().textContent).toContain('साई क्लिनिक यांनी माझ्या उपचारासाठी');
  });

  it('skips the Marathi line without a Marathi name, or when the profile cannot be read', async () => {
    await open('OWNER');
    expect(el().textContent).not.toContain('यांनी');
    TestBed.resetTestingModule();
    await open('FRONT_DESK', { profileFails: true });
    expect(el().textContent).not.toContain('यांनी');
  });

  it('shows a warning instead of the line when consent is off', async () => {
    await open('OWNER', { settings: { ...DEFAULTS, consentRequired: false } });
    expect(el().querySelector('blockquote')).toBeNull();
    expect(el().querySelector('.notice.warn')!.textContent).toContain('harder to defend');
  });

  it('saves a switch at once with the full settings', async () => {
    await open('OWNER');
    sw('Promotional consent').click();
    const req = http.expectOne(`${PLATFORM}/privacy`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ consentRequired: true, promoConsent: false, recordCopy: true, hfrId: null });
    req.flush({ ...DEFAULTS, promoConsent: false });
    fixture.detectChanges();
    expect(sw('Promotional consent').getAttribute('aria-checked')).toBe('false');
  });

  it('puts the switch back and says so when the save fails', async () => {
    await open('OWNER');
    const toast = vi.spyOn(TestBed.inject(MatSnackBar), 'open');
    sw('Record copy on request').click();
    fixture.detectChanges();
    expect(sw('Record copy on request').getAttribute('aria-checked')).toBe('false');
    http.expectOne(`${PLATFORM}/privacy`).flush({ title: 'Error', status: 500 }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(sw('Record copy on request').getAttribute('aria-checked')).toBe('true');
    expect(toast).toHaveBeenCalled();
  });

  it('is read-only for non-owners and does not call the access log', async () => {
    await open('FRONT_DESK');
    expect(el().textContent).toContain('Only the owner can change privacy settings');
    expect(sw('Consent at first visit').disabled).toBe(true);
    expect(el().querySelector<HTMLInputElement>('#hfr-id')!.disabled).toBe(true);
    expect(el().textContent).not.toContain('Who opened which record');
    http.expectNone(ACCESS_URL + '?limit=5');
  });

  it('saves the HFR ID with the other settings, and sends null when cleared', async () => {
    await open('OWNER', { settings: { ...DEFAULTS, hfrId: 'IN0410000123' } });
    const input = el().querySelector<HTMLInputElement>('#hfr-id')!;
    expect(input.value).toBe('IN0410000123');
    input.value = 'IN0410000999';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const save = Array.from(el().querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Save')!;
    save.click();
    const req = http.expectOne(`${PLATFORM}/privacy`);
    expect(req.request.body.hfrId).toBe('IN0410000999');
    req.flush({ ...DEFAULTS, hfrId: 'IN0410000999' });
    fixture.detectChanges();

    input.value = '';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    Array.from(el().querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Save')!.click();
    const clear = http.expectOne(`${PLATFORM}/privacy`);
    expect(clear.request.body.hfrId).toBeNull();
    clear.flush(DEFAULTS);
  });

  it('limits the HFR ID to 32 characters', async () => {
    await open('OWNER');
    expect(el().querySelector<HTMLInputElement>('#hfr-id')!.maxLength).toBe(32);
  });

  it('shows the ABDM placeholders and notice, with no ABDM call', async () => {
    await open('OWNER');
    const text = el().textContent!;
    expect(text).toContain('Dr. Asha Kulkarni');
    expect(text).toContain('Healthcare Professional Registry (HPR) ID');
    expect(text).toContain('Not added');
    expect(text).toContain('After linking');
    expect(text).toContain('ABDM linking is planned.');
  });

  it('shows the static facts, a disabled export and no backup-time line', async () => {
    await open('OWNER');
    const text = el().textContent!;
    expect(text).toContain('Until you delete them');
    expect(text).toContain('Encrypted, every night');
    expect(text).toContain('Servers in India (planned)');
    expect(text).not.toContain('Last: ');
    expect(text).toContain('Needs the documents service, not in this phase');
    const exportButton = Array.from(el().querySelectorAll('button')).find((b) => b.textContent?.includes('Export all data'))!;
    expect(exportButton.disabled).toBe(true);
  });

  it('lists who opened which record with the time in IST and a disabled full log', async () => {
    await open('OWNER');
    const row = el().querySelector('#f-access .li')!;
    expect(row.textContent).toContain('Pooja Gaikwad');
    expect(row.textContent).toContain('opened the file of Sneha Kale');
    expect(row.textContent).toContain('10:58 AM');
    const log = Array.from(el().querySelectorAll('#f-access button')).find((b) => b.textContent?.includes('See full log'))!;
    expect((log as HTMLButtonElement).disabled).toBe(true);
  });
});
