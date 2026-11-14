import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ShellStore } from '../../../core/shell/shell.store';
import { EmrRecordStore } from '../record/emr-record.store';
import { account, course } from './course.fixtures';
import { CoursePanelComponent, NO_COURSE } from './course-panel.component';
import { TreatmentCourseKind } from './course.model';

const OPD = '/api/v1/opd/emr/courses';
const BILLING = '/api/v1/billing/courses';

describe('CoursePanelComponent (SAH-75-3)', () => {
  let fixture: ComponentFixture<CoursePanelComponent>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let dirty = false;
  const save = vi.fn(() => of(true));

  function boot(kind: TreatmentCourseKind, opts: { readOnly?: boolean; showStart?: boolean } = {}): void {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of({ get: () => null }), snapshot: { queryParamMap: { get: () => null } } } },
        { provide: ShellStore, useValue: { me: () => ({ role: 'DOCTOR' }) } },
        {
          provide: EmrRecordStore,
          useValue: {
            record: () => ({ encounterType: 'OPD_VISIT', encounterId: 'v7', header: { patientId: 'p1' } }),
            recordId: () => 'r1',
            readOnly: () => opts.readOnly ?? false,
            dirty: () => dirty,
            save,
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CoursePanelComponent);
    fixture.componentRef.setInput('kind', kind);
    if (opts.showStart !== undefined) {
      fixture.componentRef.setInput('showStart', opts.showStart);
    }
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  const listCall = (kind: TreatmentCourseKind) => http.expectOne((r) => r.url === OPD && r.params.get('patientId') === 'p1' && r.params.get('kind') === kind);
  const q = (sel: string) => el.querySelector<HTMLElement>(sel);

  afterEach(() => {
    dirty = false;
    save.mockClear();
  });

  it('asks for the patient\'s courses of the kind of the department, and shows a loading line meanwhile', () => {
    boot('REHAB');
    expect(q('[data-testid=course-panel-loading]')).not.toBeNull();
    listCall('REHAB').flush([]);
    fixture.detectChanges();
    expect(q('[data-testid=course-panel-loading]')).toBeNull();
  });

  it('shows the open course through the course component, with the link to the full course', () => {
    boot('CHEMO');
    listCall('CHEMO').flush([{ id: 'c1', courseNo: 'TC-0412', kind: 'CHEMO', unit: 'CYCLE', name: 'Chemo', startedOn: '2026-10-28', status: 'ACTIVE', sittingCount: 3, doneCount: 1 }]);
    fixture.detectChanges();
    http.expectOne(`${OPD}/c1`).flush(course({ kind: 'CHEMO', unit: 'CYCLE' }));
    http.expectOne(`${BILLING}/c1/account`).flush(account());
    http.expectOne((r) => r.url === OPD && r.params.get('kind') === 'CHEMO').flush([]);
    fixture.detectChanges();
    expect(q('[data-testid=course-no]')!.textContent).toBe('TC-0412');
    expect(q('[data-act=open-full]')!.getAttribute('href')).toBe('/app/courses/c1');
  });

  it('shows "No course started yet." and one "Start course" button when the patient has no course of the kind', () => {
    boot('ANC');
    listCall('ANC').flush([]);
    fixture.detectChanges();
    expect(q('[data-testid=course-panel-empty]')!.textContent).toContain('Pregnancy care · ANC course');
    expect(q('[data-testid=course-panel-empty]')!.textContent).toContain(NO_COURSE);
    expect(el.querySelectorAll('[data-act=start-course]')).toHaveLength(1);
  });

  it('starts a course for this visit with the kind of the department, saving unsaved entries first, once', () => {
    dirty = true;
    boot('SURGERY');
    listCall('SURGERY').flush([]);
    fixture.detectChanges();
    q('[data-act=start-course]')!.click();
    expect(save).toHaveBeenCalledTimes(1);
    const req = http.expectOne((r) => r.method === 'POST' && r.url === OPD);
    expect(req.request.body).toEqual({ fromRecordId: 'r1', kind: 'SURGERY' });
    expect(req.request.headers.has('Idempotency-Key')).toBe(true);
    req.flush(course({ kind: 'SURGERY', unit: 'STEP' }));
    fixture.detectChanges();
    http.expectOne(`${OPD}/c1`).flush(course({ kind: 'SURGERY', unit: 'STEP' }));
    http.expectOne(`${BILLING}/c1/account`).flush(account());
    http.expectOne((r) => r.url === OPD && r.method === 'GET').flush([]);
  });

  it('shows nothing for an empty Dental panel, which keeps its own start button', () => {
    boot('DENTAL', { showStart: false });
    listCall('DENTAL').flush([]);
    fixture.detectChanges();
    expect(q('[data-testid=course-panel-empty]')).toBeNull();
  });

  it('offers no start in a read-only visit', () => {
    boot('REHAB', { readOnly: true });
    listCall('REHAB').flush([]);
    fixture.detectChanges();
    expect(q('[data-act=start-course]')).toBeNull();
  });

  it('shows an error with a retry that does not hide anything else', () => {
    boot('REHAB');
    listCall('REHAB').flush({ type: 'https://sahaj.in/problems/server', title: 'x', status: 500, detail: 'Try later.' }, { status: 500, statusText: 'Err' });
    fixture.detectChanges();
    expect(q('[data-testid=course-panel-error]')!.textContent).toContain('Try later.');
    q('[data-act=retry-panel]')!.click();
    listCall('REHAB').flush([]);
  });
});
