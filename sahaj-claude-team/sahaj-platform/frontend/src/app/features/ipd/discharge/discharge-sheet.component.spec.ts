import type { Mock } from 'vitest';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { DischargeSheet } from '../discharge.model';
import { AdmissionDetail } from '../ipd.model';
import { DischargeSheetComponent } from './discharge-sheet.component';

const SHEET_URL = '/api/v1/ipd/admissions/a1/discharge';
const SIGN_URL = '/api/v1/ipd/admissions/a1/discharge/signoff';

const SHEET: DischargeSheet = {
  admissionId: 'a1',
  ipNumber: 'IP/26-27/0042',
  patientName: 'Asha Patil',
  bedCode: '101-A',
  dayNumber: 3,
  approvalChain: true,
  types: [
    { key: 'normal', label: 'Normal' },
    { key: 'lama', label: 'On request (LAMA)' },
    { key: 'referred', label: 'Referred' },
    { key: 'death', label: 'Death' },
  ],
  templates: [
    { key: 'general', label: 'General medicine' },
    { key: 'surgical', label: 'Surgical' },
    { key: 'maternity', label: 'Maternity' },
    { key: 'daycare', label: 'Day-care' },
  ],
  defaultType: 'normal',
  defaultTemplateKey: 'general',
  signature: null,
  canSign: true,
  canDischarge: true,
  canRefund: true,
  preview: {
    diagnosis: 'Chest pain',
    stayDays: 3,
    bedCode: '101-A',
    doctorName: 'Dr. Meera Kulkarni',
    doctorShortName: 'Dr. Meera Kulkarni',
    doctorSpecialty: 'General medicine',
  },
  bill: { totalPaise: 500000, depositsPaise: 350000, balancePaise: 150000 },
  followUpDays: 7,
  followUpDate: '2026-10-31',
  openEscalation: false,
  signoffBeforeEscalation: false,
};

const SIGNED: DischargeSheet = {
  ...SHEET,
  signature: {
    type: 'normal',
    templateKey: 'general',
    signedByName: 'Dr. Meera Kulkarni',
    signedAt: '2026-10-24T06:00:00Z',
  },
};

const DISCHARGED = {
  id: 'a1',
  patientName: 'Asha Patil',
  bedCode: '101-A',
  status: 'DISCHARGED',
} as AdmissionDetail;

const problem = (slug: string, status: number, detail: string) => ({
  type: `https://sahaj.in/problems/${slug}`,
  title: slug,
  status,
  detail,
});

describe('DischargeSheetComponent', () => {
  let fixture: ComponentFixture<DischargeSheetComponent>;
  let http: HttpTestingController;
  let back: Mock<() => void>;
  let closed: Mock<() => void>;
  let discharged: Mock<(a: AdmissionDetail) => void>;
  let changed: Mock<() => void>;
  let outdated: Mock<() => void>;

  async function open(sheet: DischargeSheet | null = SHEET): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [DischargeSheetComponent],
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DischargeSheetComponent);
    fixture.componentRef.setInput('admissionId', 'a1');
    back = vi.fn<() => void>();
    closed = vi.fn<() => void>();
    discharged = vi.fn<(a: AdmissionDetail) => void>();
    changed = vi.fn<() => void>();
    outdated = vi.fn<() => void>();
    fixture.componentInstance.back.subscribe(back);
    fixture.componentInstance.closed.subscribe(closed);
    fixture.componentInstance.discharged.subscribe(discharged);
    fixture.componentInstance.changed.subscribe(changed);
    fixture.componentInstance.outdated.subscribe(outdated);
    fixture.detectChanges();
    if (sheet) {
      http.expectOne(SHEET_URL).flush(sheet);
      await settle();
    }
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const text = () => el().textContent ?? '';
  const squash = (node: Element | null) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const btn = (label: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => squash(b) === label)!;
  const primary = () => el().querySelector<HTMLButtonElement>('.sheet-f button.mat-mdc-unelevated-button')!;
  const box = () => el().querySelector<HTMLInputElement>('input[type=checkbox]')!;
  const tpl = () => el().querySelector<HTMLSelectElement>('#dis-tpl')!;
  const cells = (node: Element | null) =>
    Array.from(node?.children ?? []).map((c) => (c.textContent ?? '').replace(/\s+/g, ' ').trim()).filter((c) => c !== '').join(' ');
  const steps = () => Array.from(el().querySelectorAll('.disteps li')).map((li) => [squash(li), li.className]);
  const rows = () => Array.from(el().querySelectorAll('.ipbl')).map(cells);
  const pressed = (group: string) =>
    Array.from(el().querySelectorAll(`[aria-labelledby=${group}] button`))
      .filter((b) => b.getAttribute('aria-pressed') === 'true')
      .map(squash);

  async function tick(): Promise<void> {
    box().checked = true;
    box().dispatchEvent(new Event('change'));
    await settle();
  }

  async function choose(label: string): Promise<void> {
    btn(label).click();
    await settle();
  }

  afterEach(() => http.verify());

  it('shows a loading line, then the title and subtitle', async () => {
    await open(null);
    expect(text()).toContain('Loading the discharge sheet');
    http.expectOne(SHEET_URL).flush(SHEET);
    await settle();
    expect(el().querySelector('h2')!.textContent).toBe('Discharge Asha Patil');
    expect(squash(el().querySelector('.sheet-h p'))).toBe('IP/26-27/0042 · bed 101-A · day 3');
  });

  it('shows the load error with Try again, and Back', async () => {
    await open(null);
    http.expectOne(SHEET_URL).flush(problem('not-admitted', 409, 'This patient is not admitted any more.'), {
      status: 409,
      statusText: 'Conflict',
    });
    await settle();
    expect(text()).toContain('This patient is not admitted any more.');
    btn('Back').click();
    expect(back).toHaveBeenCalled();
    btn('Try again').click();
    http.expectOne(SHEET_URL).flush(SHEET);
    await settle();
    expect(el().querySelector('h2')!.textContent).toContain('Discharge Asha Patil');
  });

  it('lists the three steps with the approval chain on, the first one current', async () => {
    await open();
    expect(steps()).toEqual([
      ['1 Doctor signs the summary', 'cur'],
      ['2 Billing clearance', ''],
      ['3 Nursing handover', ''],
    ]);
  });

  it('lists two steps with the approval chain off', async () => {
    await open({ ...SHEET, approvalChain: false });
    expect(steps()).toEqual([
      ['1 Summary signed', 'cur'],
      ['2 Final bill', ''],
    ]);
  });

  it('offers the four types and the four templates with the server defaults', async () => {
    await open({ ...SHEET, defaultType: 'referred', defaultTemplateKey: 'maternity' });
    const types = Array.from(el().querySelectorAll('[aria-labelledby=dis-type-l] button')).map(squash);
    expect(types).toEqual(['Normal', 'On request (LAMA)', 'Referred', 'Death']);
    expect(pressed('dis-type-l')).toEqual(['Referred']);
    const options = Array.from(tpl().options).map((o) => o.textContent!.trim());
    expect(options).toEqual(['General medicine', 'Surgical', 'Maternity', 'Day-care']);
    expect(tpl().value).toBe('maternity');
  });

  it('shows the summary preview rows and the sign box text', async () => {
    await open();
    const preview = Array.from(el().querySelectorAll('.dpr')).map(cells);
    expect(preview).toEqual([
      'Diagnosis Chest pain',
      'Stay 3 days · bed 101-A · Dr. Meera Kulkarni · General medicine',
      'Treatment As recorded in the case sheet and nursing notes',
      'Follow-up OPD review on 31 Oct 2026 with Dr. Meera Kulkarni',
    ]);
    expect(squash(el().querySelector('.chkrow'))).toBe('Summary checked and signed by Dr. Meera Kulkarni');
  });

  it('writes 1 day and leaves out a missing specialty', async () => {
    await open({ ...SHEET, preview: { ...SHEET.preview, stayDays: 1, doctorSpecialty: null } });
    expect(cells(el().querySelectorAll('.dpr')[1])).toBe('Stay 1 day · bed 101-A · Dr. Meera Kulkarni');
  });

  describe('final bill', () => {
    it('collects when the balance is above 0', async () => {
      await open();
      expect(rows()).toEqual([
        'Total charges ₹5,000',
        'Deposits received − ₹3,500',
        'To collect now ₹1,500',
      ]);
      expect(squash(el().querySelector('#dis-mode-l'))).toBe('Collect by');
      expect(pressed('dis-mode-l')).toEqual(['UPI']);
      expect(Array.from(el().querySelectorAll('[aria-labelledby=dis-mode-l] button')).map(squash)).toEqual([
        'UPI',
        'Cash',
        'Card',
      ]);
      expect(squash(primary())).toBe('Collect ₹1,500 and discharge');
    });

    it('refunds when the balance is below 0', async () => {
      await open({ ...SHEET, bill: { totalPaise: 300000, depositsPaise: 350000, balancePaise: -50000 } });
      expect(rows()[2]).toBe('To refund ₹500');
      expect(squash(el().querySelector('#dis-mode-l'))).toBe('Refund by');
      expect(squash(primary())).toBe('Refund ₹500 and discharge');
    });

    it('is settled when the balance is 0, with no pay mode', async () => {
      await open({ ...SHEET, bill: { totalPaise: 350000, depositsPaise: 350000, balancePaise: 0 } });
      expect(rows()[2]).toBe('Settled ₹0');
      expect(el().querySelector('#dis-mode-l')).toBeNull();
      expect(squash(primary())).toBe('Discharge');
    });

    it('shows the WhatsApp not-yet notice with the follow-up date', async () => {
      await open();
      expect(squash(el().querySelector('.notice.info'))).toBe(
        'The discharge summary and final bill are not sent yet (WhatsApp is not in this phase). An OPD follow-up is added for 31 Oct 2026.',
      );
    });
  });

  describe('Death', () => {
    it('shows a dash for the follow-up and the death notice', async () => {
      await open();
      await choose('Death');
      expect(pressed('dis-type-l')).toEqual(['Death']);
      expect(cells(el().querySelectorAll('.dpr')[3])).toBe('Follow-up —');
      expect(squash(el().querySelector('.notice.info'))).toBe(
        'The death summary cannot be printed in this phase. No WhatsApp messages are sent. No follow-up is added.',
      );
    });
  });

  describe('signer line', () => {
    const OTHER_DOCTOR: DischargeSheet = {
      ...SHEET,
      signature: {
        type: 'normal',
        templateKey: 'general',
        signedByName: 'Dr. Anil Rao',
        signedAt: '2026-10-24T06:00:00Z',
      },
    };
    const line = () => squash(el().querySelector('.signed-by'));

    it('shows the real signer, not the admitting doctor, with the IST time', async () => {
      await open(OTHER_DOCTOR);
      expect(line()).toBe('Signed by Dr. Anil Rao · 24 Oct 2026, 11:30 AM IST');
      expect(squash(el().querySelector('.chkrow'))).toBe('Summary checked and signed');
      expect(text()).not.toContain('signed by Dr. Meera Kulkarni');
      expect(box().getAttribute('aria-describedby')).toBe('dis-signed');
    });

    it('shows nothing before signing and keeps the mockup label', async () => {
      await open();
      expect(el().querySelector('.signed-by')).toBeNull();
      expect(squash(el().querySelector('.chkrow'))).toBe('Summary checked and signed by Dr. Meera Kulkarni');
      expect(box().getAttribute('aria-describedby')).toBeNull();
    });

    it('shows nothing when the signature is for another type', async () => {
      await open({ ...OTHER_DOCTOR, signature: { ...OTHER_DOCTOR.signature!, type: 'lama' } });
      expect(el().querySelector('.signed-by')).toBeNull();
    });

    it('still shows the date of a signature from another day', async () => {
      await open({ ...OTHER_DOCTOR, signature: { ...OTHER_DOCTOR.signature!, signedAt: '2026-10-20T18:45:00Z' } });
      expect(line()).toBe('Signed by Dr. Anil Rao · 21 Oct 2026, 12:15 AM IST');
    });

    it('appears after signing, with the name the server returns', async () => {
      await open();
      await tick();
      http.expectOne(SIGN_URL).flush(OTHER_DOCTOR);
      await settle();
      expect(line()).toBe('Signed by Dr. Anil Rao · 24 Oct 2026, 11:30 AM IST');
    });
  });

  describe('signing', () => {
    it('is disabled for a role that cannot sign', async () => {
      await open({ ...SHEET, canSign: false });
      expect(box().disabled).toBe(true);
      box().checked = true;
      box().dispatchEvent(new Event('change'));
      await settle();
      expect(box().checked).toBe(false);
    });

    it('starts unticked with the discharge button disabled and the hint shown', async () => {
      await open();
      expect(box().disabled).toBe(false);
      expect(box().checked).toBe(false);
      expect(primary().disabled).toBe(true);
      expect(text()).toContain('The doctor signs the summary first');
      expect(primary().getAttribute('aria-describedby')).toBe('dis-hint');
    });

    it('records the signature for the chosen type and template and takes the fresh sheet', async () => {
      await open();
      await tick();
      const req = http.expectOne(SIGN_URL);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({ type: 'normal', templateKey: 'general' });
      req.flush(SIGNED);
      await settle();
      expect(changed).toHaveBeenCalledTimes(1);
      expect(box().checked).toBe(true);
      expect(box().disabled).toBe(true);
      expect(steps()).toEqual([
        ['Doctor signs the summary (done)', 'done'],
        ['2 Billing clearance', 'cur'],
        ['3 Nursing handover', ''],
      ]);
      expect(el().querySelector('.disteps li.done app-icon')).not.toBeNull();
      expect(primary().disabled).toBe(false);
      expect(text()).not.toContain('The doctor signs the summary first');
    });

    it('signs the type and template that are chosen', async () => {
      await open();
      await choose('On request (LAMA)');
      tpl().value = 'surgical';
      tpl().dispatchEvent(new Event('change'));
      await settle();
      await tick();
      expect(http.expectOne(SIGN_URL).request.body).toEqual({ type: 'lama', templateKey: 'surgical' });
    });

    it('shows the box unticked again when the type changes after signing', async () => {
      await open(SIGNED);
      expect(box().checked).toBe(true);
      expect(primary().disabled).toBe(false);
      await choose('Referred');
      expect(box().checked).toBe(false);
      expect(box().disabled).toBe(false);
      expect(primary().disabled).toBe(true);
      expect(steps()[0]).toEqual(['1 Doctor signs the summary', 'cur']);
      await choose('Normal');
      expect(box().checked).toBe(true);
      expect(primary().disabled).toBe(false);
    });

    it('shows the box unticked when the template changes after signing', async () => {
      await open(SIGNED);
      tpl().value = 'daycare';
      tpl().dispatchEvent(new Event('change'));
      await settle();
      expect(box().checked).toBe(false);
      expect(primary().disabled).toBe(true);
    });

    it('does not apply a signature for another type or template', async () => {
      await open({ ...SIGNED, signature: { ...SIGNED.signature!, type: 'lama' } });
      expect(box().checked).toBe(false);
      expect(primary().disabled).toBe(true);
    });

    it('shows the server message and unticks the box when signing fails', async () => {
      await open();
      await tick();
      http.expectOne(SIGN_URL).flush(problem('forbidden', 403, 'You cannot sign this summary.'), {
        status: 403,
        statusText: 'Forbidden',
      });
      await settle();
      expect(squash(el().querySelector('.notice.err'))).toBe('You cannot sign this summary.');
      expect(box().checked).toBe(false);
      expect(changed).not.toHaveBeenCalled();
      expect(outdated).not.toHaveBeenCalled();
    });

    it('ends the work here when the patient is not admitted any more', async () => {
      await open();
      await tick();
      http.expectOne(SIGN_URL).flush(
        problem('not-admitted', 409, 'This patient is not admitted any more. The summary cannot be signed.'),
        { status: 409, statusText: 'Conflict' },
      );
      await settle();
      expect(text()).toContain('This patient is not admitted any more. The summary cannot be signed.');
      expect(outdated).toHaveBeenCalledTimes(1);
      expect(box().checked).toBe(false);
    });
  });

  describe('discharging', () => {
    it('sends type, template, the total and deposits the user saw and the pay mode, then emits the discharged admission', async () => {
      await open(SIGNED);
      await choose('Card');
      expect(pressed('dis-mode-l')).toEqual(['Card']);
      primary().click();
      const req = http.expectOne(SHEET_URL);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({
        type: 'normal',
        templateKey: 'general',
        expectedTotalPaise: 500000,
        expectedDepositsPaise: 350000,
        mode: 'Card',
      });
      req.flush(DISCHARGED);
      await settle();
      expect(discharged).toHaveBeenCalledWith(DISCHARGED);
    });

    it('sends UPI by default', async () => {
      await open(SIGNED);
      primary().click();
      expect(http.expectOne(SHEET_URL).request.body.mode).toBe('UPI');
    });

    it('sends no mode when the bill is settled', async () => {
      await open({ ...SIGNED, bill: { totalPaise: 350000, depositsPaise: 350000, balancePaise: 0 } });
      primary().click();
      const body = http.expectOne(SHEET_URL).request.body;
      expect(body).toEqual({
        type: 'normal',
        templateKey: 'general',
        expectedTotalPaise: 350000,
        expectedDepositsPaise: 350000,
      });
    });

    it('sends the chosen type and template', async () => {
      await open({
        ...SIGNED,
        signature: { ...SIGNED.signature!, type: 'death', templateKey: 'surgical' },
      });
      await choose('Death');
      tpl().value = 'surgical';
      tpl().dispatchEvent(new Event('change'));
      await settle();
      primary().click();
      const body = http.expectOne(SHEET_URL).request.body;
      expect(body.type).toBe('death');
      expect(body.templateKey).toBe('surgical');
    });

    it('is disabled while the request runs, so a second tap sends nothing', async () => {
      await open(SIGNED);
      primary().click();
      await settle();
      expect(primary().disabled).toBe(true);
      expect(btn('Back').disabled).toBe(true);
      primary().click();
      http.expectOne(SHEET_URL).flush(DISCHARGED);
      await settle();
      expect(discharged).toHaveBeenCalledTimes(1);
    });

    it('does nothing when the summary is not signed for the choice', async () => {
      await open();
      primary().click();
      await settle();
      http.expectNone(SHEET_URL);
    });

    it('enables the button again after a 4xx failure and shows the server message', async () => {
      await open(SIGNED);
      primary().click();
      http.expectOne(SHEET_URL).flush(problem('x', 400, 'Try later.'), { status: 400, statusText: 'Bad Request' });
      await settle();
      expect(squash(el().querySelector('.notice.err'))).toBe('Try later.');
      expect(primary().disabled).toBe(false);
      expect(discharged).not.toHaveBeenCalled();
      expect(outdated).not.toHaveBeenCalled();
    });

    const LOST = 'We could not discharge the patient. Check the admission before trying again.';

    it('a 5xx may hide a discharge: says to check the admission and asks for a reload', async () => {
      await open(SIGNED);
      primary().click();
      http.expectOne(SHEET_URL).flush(problem('x', 500, 'Try later.'), { status: 500, statusText: 'Server Error' });
      await settle();
      expect(squash(el().querySelector('.notice.err'))).toBe(LOST);
      expect(outdated).toHaveBeenCalledTimes(1);
      expect(primary().disabled).toBe(false);
      expect(discharged).not.toHaveBeenCalled();
    });

    it('no answer at all says the same and asks for a reload', async () => {
      await open(SIGNED);
      primary().click();
      http.expectOne(SHEET_URL).error(new ProgressEvent('error'));
      await settle();
      expect(squash(el().querySelector('.notice.err'))).toBe(LOST);
      expect(outdated).toHaveBeenCalledTimes(1);
    });
  });

  describe('who may discharge', () => {
    const HINT = 'Front desk or the owner completes the discharge';

    for (const role of ['doctor', 'nurse']) {
      it(`disables the button with the hint when the ${role} cannot discharge, even when signed`, async () => {
        await open({ ...SIGNED, canDischarge: false, canRefund: false });
        expect(primary().disabled).toBe(true);
        expect(squash(el().querySelector('#dis-role-hint'))).toBe(HINT);
        expect(primary().getAttribute('aria-describedby')).toBe('dis-role-hint');
        primary().click();
        await settle();
        http.expectNone(SHEET_URL);
      });
    }

    it('shows the role hint, not the sign hint, when unsigned and not allowed', async () => {
      await open({ ...SHEET, canDischarge: false });
      expect(text()).toContain(HINT);
      expect(text()).not.toContain('The doctor signs the summary first');
    });

    for (const role of ['front desk', 'owner']) {
      it(`enables the button for the ${role} once signed, and shows no role hint`, async () => {
        await open({ ...SIGNED, canDischarge: true });
        expect(primary().disabled).toBe(false);
        expect(el().querySelector('#dis-role-hint')).toBeNull();
        expect(primary().getAttribute('aria-describedby')).toBeNull();
      });
    }

    it('leaves the refund rule to the server: canRefund false does not disable the button', async () => {
      await open({
        ...SIGNED,
        canRefund: false,
        bill: { totalPaise: 300000, depositsPaise: 350000, balancePaise: -50000 },
      });
      expect(primary().disabled).toBe(false);
      primary().click();
      http.expectOne(SHEET_URL).flush(problem('refund-needs-owner', 403, 'Only the owner can refund a deposit.'), {
        status: 403,
        statusText: 'Forbidden',
      });
      await settle();
      expect(text()).toContain('Only the owner can refund a deposit.');
    });
  });

  describe('server answers on the discharge button', () => {
    async function submit(sheet: DischargeSheet = SIGNED): Promise<TestRequest> {
      await open(sheet);
      primary().click();
      return http.expectOne(SHEET_URL);
    }

    it('403 refund-needs-owner: shows the message and keeps the button as drawn', async () => {
      const refund = { ...SIGNED, bill: { totalPaise: 300000, depositsPaise: 350000, balancePaise: -50000 } };
      const req = await submit(refund);
      req.flush(problem('refund-needs-owner', 403, 'Only the owner can refund a deposit.'), {
        status: 403,
        statusText: 'Forbidden',
      });
      await settle();
      expect(squash(el().querySelector('.notice.err'))).toBe('Only the owner can refund a deposit.');
      expect(primary().disabled).toBe(false);
      expect(squash(primary())).toBe('Refund ₹500 and discharge');
      expect(outdated).not.toHaveBeenCalled();
    });

    it('any other 403 only shows its message', async () => {
      const req = await submit();
      req.flush(problem('forbidden', 403, 'You cannot do that.'), { status: 403, statusText: 'Forbidden' });
      await settle();
      expect(squash(el().querySelector('.notice.err'))).toBe('You cannot do that.');
      expect(primary().disabled).toBe(false);
    });

    it('409 summary-not-signed: shows the message and reads the sheet again', async () => {
      const req = await submit();
      req.flush(problem('summary-not-signed', 409, 'The doctor signs the summary first'), {
        status: 409,
        statusText: 'Conflict',
      });
      await settle();
      http.expectOne(SHEET_URL).flush(SHEET);
      await settle();
      expect(text()).toContain('The doctor signs the summary first');
      expect(box().checked).toBe(false);
      expect(primary().disabled).toBe(true);
    });

    it('409 bill-changed: shows the message, reads the sheet again and keeps the choices', async () => {
      const req = await submit();
      await choose('Cash');
      req.flush(problem('bill-changed', 409, 'The bill changed. Review the final bill and try again.'), {
        status: 409,
        statusText: 'Conflict',
      });
      await settle();
      http.expectOne(SHEET_URL).flush({
        ...SIGNED,
        bill: { totalPaise: 560000, depositsPaise: 350000, balancePaise: 210000 },
      });
      await settle();
      expect(text()).toContain('The bill changed. Review the final bill and try again.');
      expect(rows()[0]).toBe('Total charges ₹5,600');
      expect(squash(primary())).toBe('Collect ₹2,100 and discharge');
      expect(primary().disabled).toBe(false);
    });

    it('bill-changed then try again sends the new total', async () => {
      const req = await submit();
      req.flush(problem('bill-changed', 409, 'The bill changed. Review the final bill and try again.'), {
        status: 409,
        statusText: 'Conflict',
      });
      await settle();
      http.expectOne(SHEET_URL).flush({
        ...SIGNED,
        bill: { totalPaise: 560000, depositsPaise: 350000, balancePaise: 210000 },
      });
      await settle();
      primary().click();
      const body = http.expectOne(SHEET_URL).request.body;
      expect(body.expectedTotalPaise).toBe(560000);
      expect(body.expectedDepositsPaise).toBe(350000);
    });

    it('409 bill-changed after a deposit: reads the sheet again, shows the new To collect amount and sends the new deposits', async () => {
      const req = await submit();
      expect(req.request.body.expectedDepositsPaise).toBe(350000);
      req.flush(problem('bill-changed', 409, 'The bill changed. Review the final bill and try again.'), {
        status: 409,
        statusText: 'Conflict',
      });
      await settle();
      http.expectOne(SHEET_URL).flush({
        ...SIGNED,
        bill: { totalPaise: 500000, depositsPaise: 450000, balancePaise: 50000 },
      });
      await settle();
      expect(text()).toContain('The bill changed. Review the final bill and try again.');
      expect(rows()[2]).toBe('To collect now ₹500');
      expect(squash(primary())).toBe('Collect ₹500 and discharge');
      primary().click();
      const retry = http.expectOne(SHEET_URL).request.body;
      expect(retry.expectedTotalPaise).toBe(500000);
      expect(retry.expectedDepositsPaise).toBe(450000);
    });

    it('409 not-admitted: shows the message, stops the button and asks for a reload', async () => {
      const req = await submit();
      req.flush(
        problem('not-admitted', 409, 'This patient is not admitted any more. The patient cannot be discharged.'),
        { status: 409, statusText: 'Conflict' },
      );
      await settle();
      expect(text()).toContain('This patient is not admitted any more. The patient cannot be discharged.');
      expect(outdated).toHaveBeenCalledTimes(1);
      expect(primary().disabled).toBe(true);
      expect(discharged).not.toHaveBeenCalled();
    });

    it('409 ipd-readonly: shows the message, stops the button and asks for a reload', async () => {
      const req = await submit();
      req.flush(problem('ipd-readonly', 409, 'IPD is read-only. Nothing can be changed.'), {
        status: 409,
        statusText: 'Conflict',
      });
      await settle();
      expect(text()).toContain('IPD is read-only. Nothing can be changed.');
      expect(outdated).toHaveBeenCalledTimes(1);
      expect(primary().disabled).toBe(true);
    });
  });

  describe('early warning escalation not closed (SAH-33)', () => {
    const OPEN = { ...SIGNED, openEscalation: true };
    const NORMAL_WARNING =
      'This patient has an early warning escalation that is not closed. A doctor must record the response first.';
    const CLOSES_WARNING =
      'This patient has an early warning escalation that is not closed. Completing this discharge closes it as "Closed at discharge", with no doctor response recorded.';
    const warning = () => el().querySelector('[data-testid=dis-escalation]');

    it('shows no warning when no escalation is open', async () => {
      await open(SIGNED);
      expect(warning()).toBeNull();
    });

    it('warns for Normal that a doctor must record the response first, before the button is pressed', async () => {
      await open(OPEN);
      expect(squash(warning())).toBe(NORMAL_WARNING);
      expect(warning()!.className).toContain('notice crit');
      expect(warning()!.getAttribute('role')).toBe('status');
      // The button is not disabled by it: the server decides.
      expect(primary().disabled).toBe(false);
    });

    it('warns that Death, On request (LAMA) and Referred close it as "Closed at discharge"', async () => {
      await open(OPEN);
      for (const type of ['Death', 'On request (LAMA)', 'Referred']) {
        await choose(type);
        expect(squash(warning())).toBe(CLOSES_WARNING);
      }
      await choose('Normal');
      expect(squash(warning())).toBe(NORMAL_WARNING);
    });

    it('409 escalation-open: shows the server message once, reads the sheet again and keeps the warning', async () => {
      await open(SIGNED);
      primary().click();
      http.expectOne(SHEET_URL).flush(problem('escalation-open', 409, NORMAL_WARNING), {
        status: 409,
        statusText: 'Conflict',
      });
      await settle();
      http.expectOne(SHEET_URL).flush(OPEN);
      await settle();
      expect(text().split(NORMAL_WARNING).length - 1).toBe(1);
      expect(el().querySelector('.notice.err')!.textContent).toContain(NORMAL_WARNING);
      expect(warning()).toBeNull();
      expect(discharged).not.toHaveBeenCalled();
      expect(outdated).not.toHaveBeenCalled();
    });
  });

  describe('sign-off older than the escalation (SAH-33 review fix, ADR-007 R2)', () => {
    const OLD = { ...SIGNED, openEscalation: true, signoffBeforeEscalation: true };
    const EARLIER =
      'This patient has an early warning escalation that started after the doctor signed the summary. A doctor must record the response or sign the summary again.';
    const NORMAL_WARNING =
      'This patient has an early warning escalation that is not closed. A doctor must record the response first.';
    const CLOSES_WARNING =
      'This patient has an early warning escalation that is not closed. Completing this discharge closes it as "Closed at discharge", with no doctor response recorded.';
    const warning = () => el().querySelector('[data-testid=dis-escalation]');

    it('shows the box unticked although a signature of this type and template exists', async () => {
      await open(OLD);
      expect(box().checked).toBe(false);
      expect(box().disabled).toBe(false);
      expect(el().querySelector('.signed-by')).toBeNull();
      expect(text()).toContain('Summary checked and signed by Dr. Meera Kulkarni');
      expect(primary().disabled).toBe(true);
    });

    it('shows the new warning for Death, On request (LAMA) and Referred, and the normal one for Normal', async () => {
      await open(OLD);
      expect(squash(warning())).toBe(NORMAL_WARNING);
      for (const type of ['Death', 'On request (LAMA)', 'Referred']) {
        await choose(type);
        expect(squash(warning())).toBe(EARLIER);
        expect(squash(warning())).not.toBe(CLOSES_WARNING);
      }
      await choose('Normal');
      expect(squash(warning())).toBe(NORMAL_WARNING);
    });

    it('keeps the close-at-discharge warning when the sign-off covers the escalation', async () => {
      await open({ ...OLD, signoffBeforeEscalation: false });
      await choose('Referred');
      expect(squash(warning())).toBe(CLOSES_WARNING);
    });

    it('ticking signs again, then the box shows ticked and the close-at-discharge warning returns', async () => {
      await open(OLD);
      await choose('Referred');
      await tick();
      http
        .expectOne(SIGN_URL)
        .flush({
          ...OLD,
          signature: { ...OLD.signature!, type: 'referred' },
          signoffBeforeEscalation: false,
        });
      await settle();
      expect(box().checked).toBe(true);
      expect(squash(warning())).toBe(CLOSES_WARNING);
      expect(changed).toHaveBeenCalledTimes(1);
    });

    it('409 escalation-open shows the new text once, through the error notice, and reads the sheet again', async () => {
      const REFERRED = { ...SIGNED.signature!, type: 'referred' as const };
      await open({ ...SIGNED, signature: REFERRED, openEscalation: true });
      await choose('Referred');
      primary().click();
      http.expectOne(SHEET_URL).flush(problem('escalation-open', 409, EARLIER), {
        status: 409,
        statusText: 'Conflict',
      });
      await settle();
      http.expectOne(SHEET_URL).flush({ ...OLD, signature: REFERRED });
      await settle();
      expect(text().split(EARLIER).length - 1).toBe(1);
      expect(el().querySelector('.notice.err')!.textContent).toContain(EARLIER);
      expect(box().checked).toBe(false);
      expect(discharged).not.toHaveBeenCalled();
      expect(outdated).not.toHaveBeenCalled();
    });
  });

  it('emits back and closed', async () => {
    await open();
    btn('Back').click();
    expect(back).toHaveBeenCalledTimes(1);
    el().querySelector<HTMLButtonElement>('button[aria-label=Close]')!.click();
    expect(closed).toHaveBeenCalledTimes(1);
  });

  it('opening the sheet only reads', async () => {
    await open();
    const writes = http.match((r) => r.method !== 'GET');
    expect(writes.length).toBe(0);
  });
});
