import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { AdmitDialogData } from '../admit/admit-dialog.component';
import { AdmissionRequestsComponent } from './admission-requests.component';
import { AdmissionRequestList, AdmissionRequestRow, requestTypeText } from './admission-requests.model';

const URL = '/api/v1/ipd/admission-requests';

const row = (over: Partial<AdmissionRequestRow> = {}): AdmissionRequestRow => ({
  kind: 'EMR',
  requestId: 'q1',
  sittingId: null,
  emergencyCaseId: null,
  patientId: 'p1',
  patientName: 'Asha Mehta',
  age: 41,
  sex: 'F',
  admissionType: 'planned',
  plannedStayDays: 3,
  doctorName: 'Dr. Rao',
  diagnoses: 'Hernia',
  note: 'Fasting from midnight',
  requestedAt: '2026-11-13T05:30:00Z',
  openAdmission: null,
  ...over,
});

describe('AdmissionRequestsComponent (SAH-67-3, H16)', () => {
  let fixture: ComponentFixture<AdmissionRequestsComponent>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let admit: AdmitDialogData[];
  let counts: number[];

  function boot(): void {
    TestBed.configureTestingModule({ providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdmissionRequestsComponent);
    admit = [];
    counts = [];
    fixture.componentInstance.admitRequested.subscribe((d) => admit.push(d));
    fixture.componentInstance.count.subscribe((n) => counts.push(n));
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  }

  const answer = (list: AdmissionRequestList) => {
    http.expectOne(URL).flush(list);
    fixture.detectChanges();
  };

  afterEach(() => http.verify());

  it('lists the requests with the type, the doctor, the diagnoses and the note', () => {
    boot();
    answer({ canAdmit: true, items: [row()] });
    const text = el.querySelector('[data-testid=request-row]')!.textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('Asha Mehta 41 F');
    expect(text).toContain('IPD 3 days · Dr. Rao');
    expect(text).toContain('Hernia');
    expect(text).toContain('Fasting from midnight');
    expect(counts).toEqual([1]);
  });

  it('shows nothing for diagnoses and note when the server sends null (front desk, H33), and Admit still works', () => {
    boot();
    answer({ canAdmit: true, items: [row({ diagnoses: null, note: null })] });
    const text = el.querySelector('[data-testid=request-row]')!.textContent!.replace(/\s+/g, ' ').trim();
    expect(text).toContain('Asha Mehta 41 F');
    expect(text).not.toContain('null');
    expect(el.querySelectorAll('[data-testid=request-row] .main > span').length).toBe(1);
    el.querySelector<HTMLButtonElement>('[data-act=admit-request]')!.click();
    expect(admit).toEqual([{ admissionType: 'planned', patientId: 'p1', admissionRequestId: 'q1' }]);
  });

  it('says "No admission requests." when there are none', () => {
    boot();
    answer({ canAdmit: true, items: [] });
    expect(el.querySelector('[data-testid=no-requests]')!.textContent).toBe('No admission requests.');
  });

  it('"Admit" asks for the existing sheet, filled with the type, the reason and the request', () => {
    boot();
    answer({ canAdmit: true, items: [row({ admissionType: 'daycare' })] });
    el.querySelector<HTMLButtonElement>('[data-act=admit-request]')!.click();
    expect(admit).toEqual([{ admissionType: 'daycare', reason: 'Hernia', patientId: 'p1', admissionRequestId: 'q1' }]);
  });

  it('an Emergency request has no patient: it sends the case', () => {
    boot();
    answer({ canAdmit: true, items: [row({ patientId: null, emergencyCaseId: 'c1', admissionType: 'emergency', plannedStayDays: null })] });
    el.querySelector<HTMLButtonElement>('[data-act=admit-request]')!.click();
    expect(admit[0]).toEqual({ admissionType: 'emergency', reason: 'Hernia', emergencyCaseId: 'c1', admissionRequestId: 'q1' });
  });

  it('a course sitting sends the sitting', () => {
    boot();
    answer({ canAdmit: true, items: [row({ kind: 'COURSE_SITTING', requestId: null, sittingId: 's1', courseNo: 'TC-0412', admissionType: 'daycare' })] });
    expect(el.textContent).toContain('Treatment course TC-0412');
    el.querySelector<HTMLButtonElement>('[data-act=admit-request]')!.click();
    expect(admit[0]).toMatchObject({ treatmentCourseSittingId: 's1' });
    expect(admit[0].admissionRequestId).toBeUndefined();
  });

  it('shows "Already admitted" with the IP number and no Admit button', () => {
    boot();
    answer({ canAdmit: true, items: [row({ openAdmission: { admissionId: 'a1', ipNumber: 'IP/26-27/0042' } })] });
    expect(el.querySelector('[data-testid=already-admitted]')!.textContent!.trim()).toBe('Already admitted · IP/26-27/0042');
    expect(el.querySelector('[data-act=admit-request]')).toBeNull();
    expect(counts).toEqual([0]);
  });

  it('has no Admit button when IPD is read-only', () => {
    boot();
    answer({ canAdmit: false, items: [row()] });
    expect(el.querySelector('[data-act=admit-request]')).toBeNull();
  });

  it('shows a message and a retry when the list cannot be read', () => {
    boot();
    http.expectOne(URL).flush(null, { status: 500, statusText: 'x' });
    fixture.detectChanges();
    expect(el.querySelector('.notice.err')!.textContent).toContain('We could not load the admission requests');
    el.querySelector<HTMLButtonElement>('[data-act=retry-requests]')!.click();
    answer({ canAdmit: true, items: [] });
  });

  it('words the type the way the mockup does', () => {
    expect(requestTypeText({ admissionType: 'planned', plannedStayDays: 3 })).toBe('IPD 3 days');
    expect(requestTypeText({ admissionType: 'planned', plannedStayDays: null })).toBe('Planned');
    expect(requestTypeText({ admissionType: 'emergency', plannedStayDays: null })).toBe('Emergency');
    expect(requestTypeText({ admissionType: 'daycare', plannedStayDays: null })).toBe('Day care');
  });
});
