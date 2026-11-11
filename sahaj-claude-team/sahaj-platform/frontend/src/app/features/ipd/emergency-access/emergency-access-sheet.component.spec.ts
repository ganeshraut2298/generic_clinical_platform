import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { EmergencyAccessSheetComponent } from './emergency-access-sheet.component';
import { EmergencyAccessPicker } from './emergency-access.model';

const PICKER_URL = '/api/v1/ipd/emergency-access/patients';
const OPEN_URL = '/api/v1/ipd/emergency-access';

const PICKER: EmergencyAccessPicker = {
  items: [
    { admissionId: 'a5', patientName: 'Ravi Shah', bedCode: '202-B', uhid: 'PT-000077' },
    { admissionId: 'a6', patientName: 'Sunita More', bedCode: '303-C', uhid: null },
  ],
  reasons: [
    { key: 'DETERIORATING', label: 'Patient deteriorating, rapid response' },
    { key: 'COVERING_BREAK', label: 'Covering for a colleague on break' },
    { key: 'DUTY_DOCTOR_ASKED', label: 'Asked to help by the duty doctor' },
  ],
};

const problem = (slug: string, status: number, detail: string, fieldErrors?: { field: string; message: string }[]) => ({
  type: `https://sahaj.in/problems/${slug}`,
  title: slug,
  status,
  detail,
  ...(fieldErrors ? { fieldErrors } : {}),
});

describe('EmergencyAccessSheetComponent (SAH-34 story 34E)', () => {
  let fixture: ComponentFixture<EmergencyAccessSheetComponent>;
  let http: HttpTestingController;
  let ref: { close: ReturnType<typeof vi.fn>; disableClose?: boolean };

  async function open(picker: EmergencyAccessPicker | 'fail' | 'forbidden' = PICKER): Promise<void> {
    ref = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [EmergencyAccessSheetComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: ref },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EmergencyAccessSheetComponent);
    document.body.appendChild(fixture.nativeElement as HTMLElement);
    fixture.detectChanges();
    const req = http.expectOne(PICKER_URL);
    if (picker === 'fail') {
      req.flush(problem('server-error', 500, 'Try later.'), { status: 500, statusText: 'x' });
    } else if (picker === 'forbidden') {
      req.flush(problem('forbidden', 403, 'Emergency access is only for staff who see some wards.'), {
        status: 403,
        statusText: 'Forbidden',
      });
    } else {
      req.flush(picker);
    }
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => {
    http.verify();
    (fixture.nativeElement as HTMLElement).remove();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const patient = () => q<HTMLSelectElement>('#eg-patient')!;
  const reason = () => q<HTMLSelectElement>('#eg-reason')!;
  const confirm = () => q<HTMLInputElement>('#eg-confirm')!;
  const openButton = () => q<HTMLButtonElement>('[data-testid=eg-open]');

  async function choose(select: HTMLSelectElement, value: string): Promise<void> {
    select.value = value;
    select.dispatchEvent(new Event('change'));
    await settle();
  }
  async function tick(on = true): Promise<void> {
    confirm().checked = on;
    confirm().dispatchEvent(new Event('change'));
    await settle();
  }
  async function press(): Promise<void> {
    openButton()!.click();
    await settle();
  }
  const grantReq = (): TestRequest => http.expectOne({ method: 'POST', url: OPEN_URL });
  const GRANT = {
    id: 'g1',
    admissionId: 'a5',
    patientName: 'Ravi Shah',
    bedCode: '202-B',
    reasonKey: 'DETERIORATING',
    reasonLabel: 'Patient deteriorating, rapid response',
    openedAt: '2026-10-24T05:35:00Z',
    expiresAt: '2026-10-24T06:05:00Z',
  };

  describe('layout (mockup sfbg; AC 45)', () => {
    it('has the title, the subtitle, Patient, Reason, the confirmation sentence and the two buttons', async () => {
      await open();
      expect(q('h2')!.textContent).toBe('Emergency access');
      expect(q('h2')!.id).toBe('eg-title');
      expect(squash(q('.sub'))).toBe(
        'For a patient outside your wards. Access lasts 30 minutes, is logged, and the owner reviews it.',
      );
      expect(squash(q('label[for=eg-patient]'))).toBe('Patient');
      expect(squash(q('label[for=eg-reason]'))).toBe('Reason');
      expect(squash(q('label[for=eg-confirm]'))).toBe(
        'I understand this access is recorded with my name and reviewed by the owner',
      );
      expect(Array.from(el().querySelectorAll('.sheet-f button')).map((b) => squash(b))).toEqual([
        'Cancel',
        'Open for 30 minutes',
      ]);
      expect(openButton()!.querySelector('app-icon')).not.toBeNull();
    });

    it('lists every patient outside her wards as "<name> · bed <code> · <UHID>", the first one chosen', async () => {
      await open();
      expect(Array.from(patient().options).map((o) => squash(o))).toEqual([
        'Ravi Shah · bed 202-B · PT-000077',
        'Sunita More · bed 303-C · —',
      ]);
      expect(patient().value).toBe('a5');
    });

    it('lists the placeholder and the three reasons in the order of the server', async () => {
      await open();
      expect(Array.from(reason().options).map((o) => squash(o))).toEqual([
        'Choose a reason',
        'Patient deteriorating, rapid response',
        'Covering for a colleague on break',
        'Asked to help by the duty doctor',
      ]);
      expect(reason().value).toBe('');
      expect(confirm().checked).toBe(false);
    });

    it('says "You can already see every admitted patient." with only Cancel when nobody is outside her wards', async () => {
      await open({ ...PICKER, items: [] });
      expect(squash(q('[data-testid=eg-nobody]'))).toBe('You can already see every admitted patient.');
      expect(q('[data-testid=eg-nobody]')!.getAttribute('role')).toBe('status');
      expect(Array.from(el().querySelectorAll('.sheet-f button')).map((b) => squash(b))).toEqual(['Cancel']);
      expect(patient()).toBeNull();
    });
  });

  describe('checks before sending (AC 47)', () => {
    it('keeps the sheet open and sends nothing without a reason; the message is under the field and gets the focus', async () => {
      await open();
      await tick();
      await press();
      http.expectNone({ method: 'POST', url: OPEN_URL });
      expect(squash(q('#eg-reason-err'))).toBe('Choose a reason for emergency access.');
      expect(q('#eg-reason-err')!.getAttribute('role')).toBe('alert');
      expect(reason().getAttribute('aria-invalid')).toBe('true');
      expect(reason().getAttribute('aria-describedby')).toBe('eg-reason-err');
      expect(document.activeElement).toBe(reason());
      expect(ref.close).not.toHaveBeenCalled();
    });

    it('keeps the sheet open and sends nothing when the box is not ticked', async () => {
      await open();
      await choose(reason(), 'DETERIORATING');
      await press();
      http.expectNone({ method: 'POST', url: OPEN_URL });
      expect(squash(q('#eg-confirm-err'))).toBe('Tick the box to confirm this access is recorded.');
      expect(document.activeElement).toBe(confirm());
    });

    it('clears an error as soon as the field is changed', async () => {
      await open();
      await press();
      expect(q('#eg-reason-err')).not.toBeNull();
      await choose(reason(), 'COVERING_BREAK');
      expect(q('#eg-reason-err')).toBeNull();
    });
  });

  describe('opening the access (AC 50, 51, 54, 61)', () => {
    it('sends only the patient, the reason key and confirmed: true, then closes with the grant', async () => {
      await open();
      await choose(patient(), 'a6');
      await choose(reason(), 'COVERING_BREAK');
      await tick();
      await press();
      const req = grantReq();
      expect(req.request.body).toEqual({ admissionId: 'a6', reasonKey: 'COVERING_BREAK', confirmed: true });
      expect(ref.close).not.toHaveBeenCalled();
      req.flush({ ...GRANT, admissionId: 'a6', patientName: 'Sunita More' }, { status: 201, statusText: 'Created' });
      await settle();
      expect(ref.close).toHaveBeenCalledWith({
        kind: 'granted',
        grant: expect.objectContaining({ id: 'g1', patientName: 'Sunita More' }),
      });
    });

    it('turns Open off while the request runs: a double tap makes one request, and Esc is off', async () => {
      await open();
      await choose(reason(), 'DETERIORATING');
      await tick();
      await press();
      expect(openButton()!.disabled).toBe(true);
      expect(openButton()!.getAttribute('aria-busy')).toBe('true');
      expect(ref.disableClose).toBe(true);
      openButton()!.click();
      fixture.componentInstance['submit']();
      const req = grantReq();
      req.flush(GRANT, { status: 201, statusText: 'Created' });
      await settle();
      expect(ref.close).toHaveBeenCalledTimes(1);
    });

    it('shows the server message on a 400 under the field it names, and sends nothing more', async () => {
      await open();
      await choose(reason(), 'DETERIORATING');
      await tick();
      await press();
      grantReq().flush(
        problem('validation', 400, 'Check the form.', [
          { field: 'reasonKey', message: 'Choose a reason for emergency access.' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
      await settle();
      expect(squash(q('#eg-reason-err'))).toBe('Choose a reason for emergency access.');
      expect(ref.close).not.toHaveBeenCalled();
    });

    it('stays open with the choices kept on a network or server failure, and does not claim access was granted', async () => {
      await open();
      await choose(patient(), 'a6');
      await choose(reason(), 'DUTY_DOCTOR_ASKED');
      await tick();
      await press();
      grantReq().flush(problem('server-error', 500, 'Boom.'), { status: 500, statusText: 'x' });
      await settle();
      expect(squash(q('[data-testid=eg-failure]'))).toBe('We could not open emergency access. Try again.');
      expect(ref.close).not.toHaveBeenCalled();
      expect(patient().value).toBe('a6');
      expect(reason().value).toBe('DUTY_DOCTOR_ASKED');
      expect(confirm().checked).toBe(true);
      expect(openButton()!.disabled).toBe(false);
    });

    it('shows the detail of a 404 (the patient went home) and reads the list again, keeping the other choices', async () => {
      await open();
      await choose(reason(), 'DETERIORATING');
      await tick();
      await press();
      grantReq().flush(problem('not-found', 404, 'This patient is not admitted any more.'), {
        status: 404,
        statusText: 'Not Found',
      });
      await settle();
      expect(squash(q('[data-testid=eg-failure]'))).toBe('This patient is not admitted any more.');
      http.expectOne(PICKER_URL).flush({ ...PICKER, items: [PICKER.items[1]] });
      await settle();
      expect(Array.from(patient().options).length).toBe(1);
      expect(patient().value).toBe('a6');
      expect(reason().value).toBe('DETERIORATING');
    });

    it('closes with the message on 409 already-in-scope', async () => {
      await open();
      await choose(reason(), 'DETERIORATING');
      await tick();
      await press();
      grantReq().flush(problem('already-in-scope', 409, 'This patient is already in your list.'), {
        status: 409,
        statusText: 'Conflict',
      });
      await settle();
      expect(ref.close).toHaveBeenCalledWith({ kind: 'in-scope', message: 'This patient is already in your list.' });
    });
  });

  describe('refusals and failures to read', () => {
    it('clears the names and shows the server message on a 403, with only Cancel; Cancel asks the page to read again', async () => {
      await open('forbidden');
      expect(squash(q('[data-testid=eg-refused]'))).toBe('Emergency access is only for staff who see some wards.');
      expect(patient()).toBeNull();
      expect(openButton()).toBeNull();
      q<HTMLButtonElement>('[data-act=eg-cancel]')!.click();
      expect(ref.close).toHaveBeenCalledWith({ kind: 'reload' });
    });

    it('clears the names when the grant itself is refused with 403', async () => {
      await open();
      await choose(reason(), 'DETERIORATING');
      await tick();
      await press();
      grantReq().flush(problem('forbidden', 403, 'Emergency access is only for staff who see some wards.'), {
        status: 403,
        statusText: 'Forbidden',
      });
      await settle();
      expect(patient()).toBeNull();
      expect(el().textContent).not.toContain('Ravi Shah');
      expect(squash(q('[data-testid=eg-refused]'))).toContain('only for staff who see some wards');
    });

    it('offers Try again when the list cannot be read, and never lists a guess', async () => {
      await open('fail');
      expect(squash(q('[data-testid=eg-load-error]'))).toBe('We could not load the patients. Try again.Try again');
      expect(patient()).toBeNull();
      q<HTMLButtonElement>('[data-testid=eg-load-error] button')!.click();
      http.expectOne(PICKER_URL).flush(PICKER);
      await settle();
      expect(patient()).not.toBeNull();
    });

    it('plain Cancel closes with no result', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=eg-cancel]')!.click();
      expect(ref.close).toHaveBeenCalledWith(undefined);
    });
  });

  describe('privacy', () => {
    it('writes nothing to the browser storage', async () => {
      const setItem = vi.spyOn(Storage.prototype, 'setItem');
      await open();
      await choose(reason(), 'DETERIORATING');
      expect(setItem).not.toHaveBeenCalled();
      setItem.mockRestore();
    });
  });
});
