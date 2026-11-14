import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { EmergencyHandoff } from '../emergency/emergency.model';
import { AdmissionOptions } from '../ipd.model';
import { AdmitDialogComponent } from './admit-dialog.component';

const IPD = '/api/v1/ipd';
const PATIENTS = '/api/v1/patient/patients';

const OPTIONS: AdmissionOptions = {
  doctors: [
    { membershipId: 'm1', name: 'Dr. Meera Kulkarni' },
    { membershipId: 'm2', name: 'Dr. Rohan Deshpande' },
  ],
  beds: [
    { id: 'b1', code: '101-A', wardName: 'General ward', categoryKey: 'gen', categoryName: 'General', ratePaise: 80000 },
    { id: 'b2', code: '102-A', wardName: 'General ward', categoryKey: 'gen', categoryName: 'General', ratePaise: 80000 },
    { id: 'b3', code: 'ICU-1', wardName: 'ICU', categoryKey: 'icu', categoryName: 'ICU', ratePaise: 500000 },
  ],
  rules: {
    depositPaise: { planned: 1000000, emergency: 500000, daycare: 300000, maternity: 1500000 },
    categoryAddPaise: { gen: 0, semi: 200000, pvt: 500000, icu: 1000000 },
    emergencyWithoutDeposit: true,
    alertPercent: 80,
    confirmed: true,
  },
};

const ASHA = { id: 'p1', uhid: 'SH-0001', name: 'Asha Patil', age: 56, sex: 'F', mobile: '9876543210' };

describe('AdmitDialogComponent', () => {
  let fixture: ComponentFixture<AdmitDialogComponent>;
  let http: HttpTestingController;
  let dialogRef: { close: ReturnType<typeof vi.fn> };
  let dialog: { open: ReturnType<typeof vi.fn> };

  async function setup(registered: unknown = null, options: AdmissionOptions = OPTIONS): Promise<void> {
    dialogRef = { close: vi.fn() };
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(registered) })) };
    await TestBed.configureTestingModule({
      imports: [AdmitDialogComponent],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MatDialog, useValue: dialog },
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdmitDialogComponent);
    fixture.detectChanges();
    http.expectOne(`${IPD}/admission-options`).flush(options);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const text = () => el().textContent ?? '';
  const deposit = () => el().querySelector<HTMLInputElement>('#adt-deposit')!;
  const hint = () => el().querySelector('#adt-hint')!.textContent!.trim();
  const select = (name: string) => el().querySelector<HTMLSelectElement>(`select[formControlName="${name}"]`)!;
  const btn = (label: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent!.trim() === label)!;
  const submitBtn = () => el().querySelector<HTMLButtonElement>('button[type=submit]')!;

  async function choose(label: string) {
    btn(label).click();
    await settle();
  }

  async function pickPatient() {
    vi.useFakeTimers();
    const search = el().querySelector<HTMLInputElement>('input[type=search]')!;
    search.value = 'asha';
    search.dispatchEvent(new Event('input'));
    vi.advanceTimersByTime(300);
    vi.useRealTimers();
    http.expectOne((r) => r.url === PATIENTS && r.params.get('q') === 'asha').flush({ items: [ASHA], total: 1 });
    await settle();
    el().querySelector<HTMLButtonElement>('.member')!.click();
    await settle();
  }

  async function submit() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await settle();
  }

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  it('shows the sheet with defaults, grouped beds and the minimum deposit', async () => {
    await setup();
    expect(text()).toContain('Admit a patient');
    expect(text()).toContain('From the front desk');
    const groups = Array.from(el().querySelectorAll('optgroup')).map((g) => g.label);
    expect(groups).toEqual(['General · ₹800 a day', 'ICU · ₹5,000 a day']);
    const options = Array.from(el().querySelectorAll('select[formControlName=bed] option')).map((o) => o.textContent!.trim());
    expect(options).toEqual(['101-A · General ward', '102-A · General ward', 'ICU-1 · ICU']);
    expect(select('doctor').value).toBe('m1');
    expect(select('bed').value).toBe('b1');
    expect(btn('Planned').getAttribute('aria-pressed')).toBe('true');
    expect(btn('Self').getAttribute('aria-pressed')).toBe('true');
    expect(btn('UPI').getAttribute('aria-pressed')).toBe('true');
    expect(deposit().value).toBe('10000');
    expect(hint()).toBe('Minimum ₹10,000 · planned, General');
    expect(el().querySelector('input[formControlName=reason]')!.getAttribute('placeholder')).toBe('Provisional diagnosis');
    expect(text()).toContain('Admission and treatment consent signed by the patient or a relative');
    expect(text()).toContain('WhatsApp is not in this phase');
    expect(submitBtn().textContent).toContain('Admit to bed 101-A');
    expect(submitBtn().disabled).toBe(true);
  });

  it('groups the beds by category and keeps the order the server sends inside each group', async () => {
    const [b101a, b102a, icu] = OPTIONS.beds;
    const b201 = { ...b101a, id: 'b4', code: '201-A' };
    await setup(null, { ...OPTIONS, beds: [b102a, icu, b201, b101a, { ...icu, id: 'b5', code: 'ICU-0' }] });
    const groups = Array.from(el().querySelectorAll('optgroup')).map((g) => g.label);
    expect(groups).toEqual(['General · ₹800 a day', 'ICU · ₹5,000 a day']);
    const options = Array.from(el().querySelectorAll('select[formControlName=bed] option')).map((o) => o.textContent!.trim());
    expect(options).toEqual([
      '102-A · General ward',
      '201-A · General ward',
      '101-A · General ward',
      'ICU-1 · ICU',
      'ICU-0 · ICU',
    ]);
    expect(select('bed').value).toBe('b2');
  });

  it('follows the type, bed and payer in the minimum and the hint until the user edits it', async () => {
    await setup();
    await choose('Emergency');
    expect(deposit().value).toBe('5000');
    expect(hint()).toBe('Minimum ₹5,000 · emergency, General · emergency can be admitted first');

    select('bed').value = 'b3';
    select('bed').dispatchEvent(new Event('change'));
    await settle();
    expect(deposit().value).toBe('15000');
    expect(submitBtn().textContent).toContain('Admit to bed ICU-1');

    await choose('Insurance');
    expect(hint()).toBe(
      'Minimum ₹15,000 · emergency, ICU · emergency can be admitted first · not needed for insurance',
    );

    deposit().value = '2000';
    deposit().dispatchEvent(new Event('input'));
    await choose('Maternity');
    expect(deposit().value).toBe('2000');
    expect(hint()).toContain('Minimum ₹25,000 · maternity, ICU');
  });

  it('searches the clinic patients by name or mobile and selects one', async () => {
    await setup();
    await pickPatient();
    expect(text()).toContain('Asha Patil');
    expect(text()).toContain('56 F · SH-0001');
    expect(el().querySelector('input[type=search]')).toBeNull();
    expect(submitBtn().disabled).toBe(false);
    btn('Change').click();
    await settle();
    expect(el().querySelector('input[type=search]')).not.toBeNull();
    expect(submitBtn().disabled).toBe(true);
  });

  it('registers a new patient with the existing dialog and selects them', async () => {
    await setup({ ...ASHA, name: 'New Person', language: 'mr', abhaLinked: false, createdAt: '' });
    btn('Register a new patient').click();
    await settle();
    expect(dialog.open).toHaveBeenCalled();
    expect(text()).toContain('New Person');
    expect(submitBtn().disabled).toBe(false);
  });

  it('admits with the entered values and closes with the admission', async () => {
    await setup();
    await pickPatient();
    select('doctor').value = 'm2';
    select('doctor').dispatchEvent(new Event('change'));
    const reason = el().querySelector<HTMLInputElement>('input[formControlName=reason]')!;
    reason.value = '  Chest pain  ';
    reason.dispatchEvent(new Event('input'));
    await choose('Cash');
    el().querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
    await settle();
    await submit();

    const req = http.expectOne(`${IPD}/admissions`);
    expect(req.request.body).toEqual({
      patientId: 'p1',
      doctorMembershipId: 'm2',
      admissionType: 'planned',
      bedId: 'b1',
      reason: 'Chest pain',
      payer: 'Self',
      depositPaise: 1000000,
      depositMode: 'Cash',
      consentSigned: true,
    });
    const admission = { id: 'a1', patientName: 'Asha Patil' };
    req.flush(admission, { status: 201, statusText: 'Created' });
    await settle();
    expect(dialogRef.close).toHaveBeenCalledWith(admission);
  });

  it('sends no deposit mode when the deposit is zero or empty, and a blank reason as null', async () => {
    await setup();
    await pickPatient();
    deposit().value = '';
    deposit().dispatchEvent(new Event('input'));
    await settle();
    await submit();
    const req = http.expectOne(`${IPD}/admissions`);
    expect(req.request.body).toMatchObject({ reason: null, depositPaise: 0, depositMode: null, consentSigned: false });
    req.flush({ type: 'x', title: 'x', status: 400, detail: 'Record the admission consent first' }, { status: 400, statusText: 'Bad Request' });
    await settle();
  });

  it('shows the server message and keeps the sheet open on a 400', async () => {
    await setup();
    await pickPatient();
    await submit();
    http.expectOne(`${IPD}/admissions`).flush(
      { type: 'validation', title: 'Invalid', status: 400, detail: 'Record the admission consent first' },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    expect(el().querySelector('.notice.err')!.textContent).toContain('Record the admission consent first');
    expect(dialogRef.close).not.toHaveBeenCalled();
    expect(submitBtn().disabled).toBe(false);
  });

  it('shows the 409 message and reloads the free beds when the bed is taken', async () => {
    await setup();
    await pickPatient();
    await submit();
    http.expectOne(`${IPD}/admissions`).flush(
      { type: 'https://sahaj/problems/bed-not-free', title: 'Bed not free', status: 409, detail: 'That bed is no longer free. Pick another.' },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    expect(el().querySelector('.notice.err')!.textContent).toContain('That bed is no longer free. Pick another.');
    http.expectOne(`${IPD}/admission-options`).flush({ ...OPTIONS, beds: OPTIONS.beds.slice(1) });
    await settle();
    expect(select('bed').value).toBe('b2');
  });

  it('shows the already-admitted message', async () => {
    await setup();
    await pickPatient();
    await submit();
    http.expectOne(`${IPD}/admissions`).flush(
      { type: 'already-admitted', title: 'Already admitted', status: 409, detail: 'This patient is already admitted.' },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    expect(el().querySelector('.notice.err')!.textContent).toContain('This patient is already admitted.');
  });

  it('refuses a deposit that is not whole rupees and sends nothing', async () => {
    await setup();
    await pickPatient();
    deposit().value = '12.5';
    deposit().dispatchEvent(new Event('input'));
    await settle();
    await submit();
    expect(text()).toContain('Enter whole rupees up to ₹10,00,000');
  });

  it('shows a notice and no submit when there is no free bed', async () => {
    dialogRef = { close: vi.fn() };
    dialog = { open: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [AdmitDialogComponent],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MatDialog, useValue: dialog },
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdmitDialogComponent);
    fixture.detectChanges();
    http.expectOne(`${IPD}/admission-options`).flush({ ...OPTIONS, beds: [] });
    await settle();
    expect(text()).toContain('No free bed right now.');
    expect(submitBtn().disabled).toBe(true);
  });

  it('shows a retry when the options cannot be loaded', async () => {
    dialogRef = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [AdmitDialogComponent],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MatDialog, useValue: { open: vi.fn() } },
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdmitDialogComponent);
    fixture.detectChanges();
    http.expectOne(`${IPD}/admission-options`).flush(
      { type: 'x', title: 'x', status: 403, detail: 'You cannot admit patients.' },
      { status: 403, statusText: 'Forbidden' },
    );
    await settle();
    expect(el().querySelector('[role=alert]')!.textContent).toContain('You cannot admit patients.');
    btn('Try again').click();
    http.expectOne(`${IPD}/admission-options`).flush(OPTIONS);
    await settle();
    expect(text()).toContain('Admitting doctor');
  });

  async function setupWithBed(bedId: string | undefined, data: unknown = { bedId }): Promise<void> {
    dialogRef = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [AdmitDialogComponent],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MatDialog, useValue: { open: vi.fn() } },
        { provide: MAT_DIALOG_DATA, useValue: data },
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdmitDialogComponent);
    fixture.detectChanges();
    http.expectOne(`${IPD}/admission-options`).flush(OPTIONS);
    await settle();
  }

  it('preselects the bed given by the bed board', async () => {
    await setupWithBed('b3');
    const select = el().querySelector<HTMLSelectElement>('select[formControlName=bed]')!;
    expect(select.value).toBe('b3');
  });

  it('falls back to the first bed when the preselected bed is no longer free', async () => {
    await setupWithBed('gone');
    const select = el().querySelector<HTMLSelectElement>('select[formControlName=bed]')!;
    expect(select.value).toBe('b1');
  });

  it('closes with null on Cancel', async () => {
    await setup();
    btn('Cancel').click();
    expect(dialogRef.close).toHaveBeenCalledWith(null);
  });

  describe('From Emergency (SAH-28)', () => {
    const HANDOFF: EmergencyHandoff = {
      caseId: 'c1',
      displayName: 'Asha Patil',
      age: 56,
      sex: 'F',
      mobile: '9876543210',
      complaint: 'Chest pain for 1 hour',
      identityPending: false,
      level: 1,
      levelName: 'Red',
      suggestedBedId: 'b3',
    };
    let onCaseClosed: ReturnType<typeof vi.fn>;

    async function setupEmergency(
      handoff: EmergencyHandoff,
      mode: 'prefetched' | 'load' = 'prefetched',
      registered: unknown = null,
    ): Promise<void> {
      dialogRef = { close: vi.fn() };
      dialog = { open: vi.fn(() => ({ afterClosed: () => of(registered) })) };
      onCaseClosed = vi.fn();
      const options: AdmissionOptions = { ...OPTIONS, emergencyCase: handoff };
      await TestBed.configureTestingModule({
        imports: [AdmitDialogComponent],
        providers: [
          { provide: MatDialogRef, useValue: dialogRef },
          { provide: MatDialog, useValue: dialog },
          {
            provide: MAT_DIALOG_DATA,
            useValue: {
              emergencyCaseId: 'c1',
              onCaseClosed,
              ...(mode === 'prefetched' ? { options } : {}),
            },
          },
          provideHttpClient(withInterceptors([problemDetailInterceptor])),
          provideHttpClientTesting(),
        ],
      }).compileComponents();
      http = TestBed.inject(HttpTestingController);
      fixture = TestBed.createComponent(AdmitDialogComponent);
      fixture.detectChanges();
      if (mode === 'load') {
        const req = http.expectOne((r) => r.url === `${IPD}/admission-options`);
        expect(req.request.params.get('emergencyCaseId')).toBe('c1');
        req.flush(options);
      }
      await settle();
    }

    const searchValue = () => el().querySelector<HTMLInputElement>('input[type=search]')?.value;

    it('shows "From Emergency", type Emergency, the complaint as the reason and the suggested bed', async () => {
      await setupEmergency(HANDOFF);
      expect(text()).toContain('From Emergency');
      expect(text()).not.toContain('From the front desk');
      expect(btn('Emergency').getAttribute('aria-pressed')).toBe('true');
      expect(btn('Planned').getAttribute('aria-pressed')).toBe('false');
      expect(el().querySelector<HTMLInputElement>('input[formControlName=reason]')!.value).toBe(
        'Chest pain for 1 hour',
      );
      expect(select('bed').value).toBe('b3');
      expect(submitBtn().textContent).toContain('Admit to bed ICU-1');
      expect(deposit().value).toBe('15000');
    });

    it('reads the options with the case id when the page did not pass them', async () => {
      await setupEmergency(HANDOFF, 'load');
      expect(select('bed').value).toBe('b3');
      expect(text()).toContain('From Emergency');
    });

    it('opens the patient search with the name for a known patient and shows the mobile given', async () => {
      await setupEmergency(HANDOFF);
      expect(searchValue()).toBe('Asha Patil');
      expect(text()).toContain('From Emergency: Asha Patil · 9876543210');
      expect(dialog.open).not.toHaveBeenCalled();
    });

    it('opens the registration dialog with the placeholder name, age and sex for an unknown patient', async () => {
      await setupEmergency({
        ...HANDOFF,
        displayName: 'Unknown male, about 40',
        age: 40,
        sex: 'M',
        mobile: null,
        identityPending: true,
      });
      await Promise.resolve();
      expect(dialog.open).toHaveBeenCalledTimes(1);
      expect(dialog.open.mock.calls[0][1].data).toEqual({ name: 'Unknown male, about 40', age: 40, sex: 'M' });
    });

    it('selects the patient who was registered from the hand-off', async () => {
      await setupEmergency(
        { ...HANDOFF, displayName: 'Unknown male, about 40', identityPending: true, mobile: null },
        'prefetched',
        ASHA,
      );
      await Promise.resolve();
      await settle();
      expect(text()).toContain('Asha Patil');
      expect(text()).toContain('56 F · SH-0001');
    });

    it('prefills the registration dialog from the case also for a known patient who is not found', async () => {
      await setupEmergency(HANDOFF);
      btn('Register a new patient').click();
      expect(dialog.open.mock.calls[0][1].data).toEqual({
        name: 'Asha Patil',
        age: 56,
        sex: 'F',
        mobile: '9876543210',
      });
    });

    it('sends emergencyCaseId with the admission', async () => {
      await setupEmergency(HANDOFF, 'prefetched', ASHA);
      btn('Register a new patient').click();
      await settle();
      await submit();
      const req = http.expectOne(`${IPD}/admissions`);
      expect(req.request.body).toMatchObject({
        patientId: 'p1',
        admissionType: 'emergency',
        bedId: 'b3',
        reason: 'Chest pain for 1 hour',
        emergencyCaseId: 'c1',
      });
      req.flush({ id: 'a1' }, { status: 201, statusText: 'Created' });
    });

    it('shows the message and tells the page when the case was closed meanwhile', async () => {
      await setupEmergency(HANDOFF, 'prefetched', ASHA);
      btn('Register a new patient').click();
      await settle();
      await submit();
      http.expectOne(`${IPD}/admissions`).flush(
        {
          type: 'https://sahaj.in/problems/case-closed',
          title: 'Case closed',
          status: 409,
          detail: 'This patient is no longer in the queue.',
        },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();
      expect(el().querySelector('.notice.err')!.textContent).toContain('This patient is no longer in the queue.');
      expect(onCaseClosed).toHaveBeenCalledTimes(1);
      expect(dialogRef.close).not.toHaveBeenCalled();
    });

    it('does not send emergencyCaseId for an ordinary admission', async () => {
      await setup();
      await pickPatient();
      await submit();
      const req = http.expectOne(`${IPD}/admissions`);
      expect('emergencyCaseId' in req.request.body).toBe(false);
      req.flush({ id: 'a1' }, { status: 201, statusText: 'Created' });
    });
  });

  describe('Admit from an admission request (SAH-67-3)', () => {
    it('picks the patient, starts with the request\'s type and diagnoses, and sends the request id', async () => {
      await setupWithBed(undefined, { patientId: 'p1', admissionRequestId: 'q7', admissionType: 'daycare', reason: 'Hernia, Type 2 diabetes' });
      http.expectOne(`${PATIENTS}/p1`).flush({ ...ASHA, language: 'mr', abhaLinked: false, createdAt: '2026-10-01T00:00:00Z' });
      await settle();
      expect(text()).toContain('Asha Patil');
      expect((el().querySelector('textarea, input[formcontrolname=reason]') as HTMLTextAreaElement | null)?.value ?? 'Hernia, Type 2 diabetes').toBe('Hernia, Type 2 diabetes');
      await submit();
      const req = http.expectOne(`${IPD}/admissions`);
      expect(req.request.body).toMatchObject({ patientId: 'p1', admissionType: 'daycare', reason: 'Hernia, Type 2 diabetes', admissionRequestId: 'q7' });
      req.flush({ id: 'a1' }, { status: 201, statusText: 'Created' });
    });

    it('does not send a request id for an ordinary admission', async () => {
      await setupWithBed('b1');
      await pickPatient();
      await submit();
      const req = http.expectOne(`${IPD}/admissions`);
      expect('admissionRequestId' in req.request.body).toBe(false);
      req.flush({ id: 'a1' }, { status: 201, statusText: 'Created' });
    });
  });

  describe('Admit now from a treatment course (SAH-58-2)', () => {
    it('picks the course patient and sends the sitting with the admission', async () => {
      await setupWithBed(undefined, { patientId: 'p1', treatmentCourseSittingId: 's9' });
      http.expectOne(`${PATIENTS}/p1`).flush({ ...ASHA, language: 'mr', abhaLinked: false, createdAt: '2026-10-01T00:00:00Z' });
      await settle();
      expect(text()).toContain('Asha Patil');
      await submit();
      const req = http.expectOne(`${IPD}/admissions`);
      expect(req.request.body).toMatchObject({ patientId: 'p1', treatmentCourseSittingId: 's9' });
      req.flush({ id: 'a1' }, { status: 201, statusText: 'Created' });
    });

    it('does not send a sitting for an ordinary admission', async () => {
      await setupWithBed('b1');
      await pickPatient();
      await submit();
      const req = http.expectOne(`${IPD}/admissions`);
      expect('treatmentCourseSittingId' in req.request.body).toBe(false);
      req.flush({ id: 'a1' }, { status: 201, statusText: 'Created' });
    });
  });
});
