import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { CasePaperSettings, FeeSettings } from '../opd/opd.model';
import { TeamMember } from '../team/team.model';
import { PaperPage, capitalise } from './paper.page';

const OPD = '/api/v1/opd';
const PLATFORM = '/api/v1/platform';

const SETTINGS: CasePaperSettings = {
  size: 'A5',
  secondLanguage: null,
  preprinted: false,
  caps: false,
  sections: { complaints: true, vitals: true, history: false, exam: true, diagnosis: true, inv: true, rx: true, advice: true, next: true },
};
const FEES: FeeSettings = {
  feeTiming: 'after',
  followUpDays: 6,
  followUpFee: 'free',
  discountLimits: {},
  upiOnBill: true,
};
const DOCTOR: TeamMember = {
  id: 'm-doc',
  name: 'Dr. Asha Kulkarni',
  mobileMasked: '••••••1001',
  role: 'DOCTOR',
  status: 'ACTIVE',
  doctor: { degree: 'MBBS', registrationNo: 'MMC 2012/03/4567' },
};

describe('capitalise', () => {
  it('capitalises medicine names only when asked', () => {
    expect(capitalise('Tab. Paracetamol', true)).toBe('TAB. PARACETAMOL');
    expect(capitalise('Tab. Paracetamol', false)).toBe('Tab. Paracetamol');
  });
});

describe('PaperPage', () => {
  let fixture: ComponentFixture<PaperPage>;
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
    fixture = TestBed.createComponent(PaperPage);
    fixture.detectChanges();
    http.expectOne(`${OPD}/case-paper-settings`).flush(SETTINGS);
    http.expectOne(`${PLATFORM}/clinic/profile`).flush({ name: 'Shree Sai Clinic', nameLocal: 'श्री साई क्लिनिक', address: 'Ravet, Pune', phone: '02012345678' });
    http.expectOne(`${PLATFORM}/team`).flush([DOCTOR]);
    http.expectOne(`${OPD}/fee-settings`).flush(FEES);
    fixture.detectChanges();
    http.expectOne((r) => r.url === `${OPD}/medicines`).flush({ items: [], total: 0 });
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  it('previews the paper with the clinic name, the doctor and the registration number', async () => {
    const el = await open('OWNER');
    const paper = el.querySelector('.paper')!.textContent!;
    expect(paper).toContain('SHREE SAI CLINIC');
    expect(paper).toContain('Dr. Asha Kulkarni');
    expect(paper).toContain('Reg. MMC 2012/03/4567');
    expect(paper).toContain('Free follow-up till');
    expect(paper).not.toContain('Past history');
  });

  it('cannot switch off the medicines section or the registration number', async () => {
    const el = await open('OWNER');
    const rx = el.querySelector<HTMLButtonElement>('[aria-label="Medicines (Rx)"]')!;
    expect(rx.disabled).toBe(true);
    rx.click();
    http.expectNone(`${OPD}/case-paper-settings`);
    expect(el.textContent).toContain('Always printed, as medical council rules require');
  });

  it('shows a section on the preview as soon as it is switched on, and saves it with rx on', async () => {
    const el = await open('OWNER');
    el.querySelector<HTMLButtonElement>('[aria-label="Past history"]')!.click();
    fixture.detectChanges();
    expect(el.querySelector('.paper')!.textContent).toContain('Past history');
    const req = http.expectOne(`${OPD}/case-paper-settings`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.sections.history).toBe(true);
    expect(req.request.body.sections.rx).toBe(true);
    req.flush({ ...SETTINGS, sections: { ...SETTINGS.sections, history: true } });
  });

  it('prints medicine names in capitals and switches to A4', async () => {
    const el = await open('OWNER');
    el.querySelector<HTMLButtonElement>('[aria-label="Capital letters for medicines"]')!.click();
    http.expectOne(`${OPD}/case-paper-settings`).flush({ ...SETTINGS, caps: true });
    fixture.detectChanges();
    expect(el.querySelector('.paper')!.textContent).toContain('TAB. PARACETAMOL 650 MG');

    const a4 = Array.from(el.querySelectorAll('mat-button-toggle button')).find((b) => b.textContent?.trim() === 'A4') as HTMLButtonElement;
    a4.click();
    http.expectOne(`${OPD}/case-paper-settings`).flush({ ...SETTINGS, caps: true, size: 'A4' });
    fixture.detectChanges();
    expect(el.querySelector('.paper')!.classList).toContain('a4');
  });

  it('adds the Marathi labels and clinic name when + मराठी is chosen', async () => {
    const el = await open('OWNER');
    const mr = Array.from(el.querySelectorAll('mat-button-toggle button')).find((b) => b.textContent?.includes('मराठी')) as HTMLButtonElement;
    mr.click();
    const req = http.expectOne(`${OPD}/case-paper-settings`);
    expect(req.request.body.secondLanguage).toBe('mr');
    req.flush({ ...SETTINGS, secondLanguage: 'mr' });
    fixture.detectChanges();
    expect(el.querySelector('.paper')!.textContent).toContain('तक्रार');
    expect(el.querySelector('.paper')!.textContent).toContain('श्री साई क्लिनिक');
  });

  it('is read-only for a nurse: every switch is disabled', async () => {
    const el = await open('NURSE');
    const switches = Array.from(el.querySelectorAll<HTMLButtonElement>('[role="switch"]'));
    expect(switches.length).toBeGreaterThan(5);
    expect(switches.every((s) => s.disabled)).toBe(true);
  });
});
