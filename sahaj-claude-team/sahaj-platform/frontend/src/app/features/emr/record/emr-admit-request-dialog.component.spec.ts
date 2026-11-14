import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { casePaperContext, record } from '../emr.fixtures';
import { EmrAdmissionPrefill } from '../emr-finish.model';
import { EmrAdmitRequestDialogComponent, requestTypeWord } from './emr-admit-request-dialog.component';
import { EmrRecordStore } from './emr-record.store';

const EMR = '/api/v1/opd/emr/records';

const prefill = (over: Partial<EmrAdmissionPrefill> = {}): EmrAdmissionPrefill => ({
  patient: { patientId: 'p1', name: 'Test Patient', age: 52, sex: 'M', mobile: '9876543210' },
  suggestedType: 'planned',
  diagnoses: ['Hernia', 'Type 2 diabetes'],
  procedure: 'Hernia repair',
  plannedStayDays: 2,
  emergencyCaseId: null,
  openRequest: null,
  openAdmission: null,
  canAdmitDirectly: false,
  ...over,
});

const VIEW = { id: 'q1', status: 'OPEN', admissionType: 'planned', plannedStayDays: 2, createdAt: '2026-11-13T05:00:00Z', admissionId: null, ipNumber: null };

describe('EmrAdmitRequestDialogComponent (SAH-67-3)', () => {
  let fixture: ComponentFixture<EmrAdmitRequestDialogComponent>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;
  let el: HTMLElement;

  function boot(data: EmrAdmissionPrefill): void {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: { close } },
        { provide: MAT_DIALOG_DATA, useValue: { prefill: data } },
        EmrRecordStore,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(EmrRecordStore).load('r1');
    http.expectOne(`${EMR}/r1`).flush(record());
    http.expectOne(`${EMR}/r1/case-paper`).flush(casePaperContext());
    fixture = TestBed.createComponent(EmrAdmitRequestDialogComponent);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  const send = () => el.querySelector<HTMLButtonElement>('[data-act=admit-send]')!;

  it('shows the patient, the diagnoses and the procedure that are filled in', () => {
    boot(prefill());
    const facts = el.querySelector('[data-testid=admit-facts]')!.textContent!.replace(/\s+/g, ' ');
    expect(facts).toContain('Test Patient');
    expect(facts).toContain('52 y · Male · 9876543210');
    expect(el.querySelector('[data-testid=admit-diagnoses]')!.textContent).toBe('Hernia, Type 2 diabetes');
    expect(el.querySelector('[data-testid=admit-procedure]')!.textContent).toBe('Procedure: Hernia repair');
  });

  it('a doctor sends the request with the type, the stay and the note; one Idempotency-Key', () => {
    boot(prefill());
    expect(send().textContent!.trim()).toBe('Send admission request');
    const note = el.querySelector<HTMLTextAreaElement>('[data-field=note]')!;
    note.value = 'Fasting from midnight';
    note.dispatchEvent(new Event('input'));
    send().click();
    const req = http.expectOne(`${EMR}/r1/admission-request`);
    expect(req.request.body).toEqual({ admissionType: 'planned', plannedStayDays: 2, note: 'Fasting from midnight' });
    expect(req.request.headers.get('Idempotency-Key')).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
    expect(req.request.headers.get('Idempotency-Key')!.startsWith('sign-')).toBe(false);
    req.flush(VIEW, { status: 201, statusText: 'Created' });
    http.expectOne(`${EMR}/r1`).flush(record());
    http.match(`${EMR}/r1/case-paper`).forEach((r) => r.flush(casePaperContext()));
    expect(close).toHaveBeenCalledWith(expect.objectContaining({ openSheet: false, request: expect.objectContaining({ id: 'q1' }) }));
  });

  it('the owner "Requests and chooses bed" and goes on to the sheet', () => {
    boot(prefill({ canAdmitDirectly: true, suggestedType: 'emergency', plannedStayDays: null }));
    expect(send().textContent!.trim()).toBe('Request and choose bed');
    send().click();
    const req = http.expectOne(`${EMR}/r1/admission-request`);
    expect(req.request.body).toEqual({ admissionType: 'emergency' });
    req.flush({ ...VIEW, admissionType: 'emergency', plannedStayDays: null });
    http.expectOne(`${EMR}/r1`).flush(record());
    http.match(`${EMR}/r1/case-paper`).forEach((r) => r.flush(casePaperContext()));
    expect(close).toHaveBeenCalledWith(expect.objectContaining({ openSheet: true }));
  });

  it('the doctor can change the type to Day care', () => {
    boot(prefill({ plannedStayDays: null }));
    el.querySelector<HTMLButtonElement>('[data-type=daycare]')!.click();
    fixture.detectChanges();
    expect(el.querySelector('[data-field=stay]')).toBeNull();
    send().click();
    expect(http.expectOne(`${EMR}/r1/admission-request`).request.body).toEqual({ admissionType: 'daycare' });
  });

  it('a planned stay must be 1 to 60 days', () => {
    boot(prefill());
    const stay = el.querySelector<HTMLInputElement>('[data-field=stay]')!;
    stay.value = '61';
    stay.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(send().disabled).toBe(true);
    expect(el.textContent).toContain('Enter 1 to 60 days');
  });

  it('shows an open request and never makes a second one', () => {
    boot(prefill({ openRequest: { ...VIEW, status: 'OPEN' } as never }));
    expect(el.querySelector('[data-testid=admit-open]')!.textContent).toContain('Admission requested · IPD 2 days');
    expect(el.querySelector('[data-act=admit-send]')).toBeNull();
  });

  it('an owner with an open request goes on to the sheet from it', () => {
    boot(prefill({ canAdmitDirectly: true, openRequest: { ...VIEW } as never }));
    el.querySelector<HTMLButtonElement>('[data-act=admit-sheet]')!.click();
    expect(close).toHaveBeenCalledWith(expect.objectContaining({ openSheet: true }));
  });

  it('says "Already admitted" with the IP number and offers no request', () => {
    boot(prefill({ openAdmission: { admissionId: 'a1', ipNumber: 'IP/26-27/0042' } }));
    expect(el.querySelector('[data-testid=admit-already]')!.textContent).toBe('Already admitted · IP/26-27/0042');
    expect(el.querySelector('[data-act=admit-send]')).toBeNull();
  });

  it('shows the server\'s words when the request is refused', () => {
    boot(prefill());
    send().click();
    http.expectOne(`${EMR}/r1/admission-request`).flush(
      { type: 'https://sahaj.in/problems/already-admitted', title: 'x', status: 409, detail: 'Already admitted · IP/26-27/0042' },
      { status: 409, statusText: 'x' },
    );
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=admit-error]')!.textContent).toBe('Already admitted · IP/26-27/0042');
  });

  it('words the types as the mockup does', () => {
    expect(requestTypeWord('planned', 3)).toBe('IPD 3 days');
    expect(requestTypeWord('planned', null)).toBe('Planned');
    expect(requestTypeWord('emergency', null)).toBe('Emergency');
    expect(requestTypeWord('daycare', null)).toBe('Day care');
  });
});
