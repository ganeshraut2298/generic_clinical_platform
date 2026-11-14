import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, Router, UrlTree, provideRouter, ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ShellStore } from '../../../core/shell/shell.store';
import { account, course, sitting } from './course.fixtures';
import { CourseAccount, TreatmentCourseView } from './course.model';
import { CoursePage } from './course.page';
import { courseRoleGuard } from './course.routes';

const OPD = '/api/v1/opd/emr/courses';
const BILLING = '/api/v1/billing/courses';

describe('CoursePage (SAH-58)', () => {
  let fixture: ComponentFixture<CoursePage>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let dialogOpen: ReturnType<typeof vi.fn>;
  let dialogResult: unknown;
  let params: BehaviorSubject<{ get: (k: string) => string | null }>;

  function boot(c: TreatmentCourseView = course(), a: CourseAccount = account(), opts: { role?: string; visitId?: string | null } = {}): void {
    dialogResult = undefined;
    dialogOpen = vi.fn(() => ({ afterClosed: () => of(dialogResult) }));
    params = new BehaviorSubject<{ get: (k: string) => string | null }>({ get: (k: string) => (k === 'courseId' ? 'c1' : null) });
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: params, snapshot: { queryParamMap: { get: () => opts.visitId ?? null } } } },
        { provide: ShellStore, useValue: { me: () => ({ role: opts.role ?? 'OWNER' }) } },
        { provide: MatDialog, useValue: { open: dialogOpen } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CoursePage);
    fixture.detectChanges();
    http.expectOne(`${OPD}/c1`).flush(c);
    http.expectOne(`${BILLING}/c1/account`).flush(a);
    http.expectOne((r) => r.url === OPD && r.params.get('patientId') === 'p1').flush([]);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  const q = <T extends Element = HTMLElement>(sel: string) => el.querySelector<T>(sel);
  const qa = (sel: string) => Array.from(el.querySelectorAll<HTMLElement>(sel));
  const view = (name: string) => {
    q<HTMLButtonElement>(`[data-view=${name}]`)!.click();
    fixture.detectChanges();
  };
  const click = (sel: string) => {
    q<HTMLButtonElement>(sel)!.click();
    fixture.detectChanges();
  };
  const toast = () => q('[data-testid=course-toast]')?.textContent?.trim();

  /** Today's sitting (s2) may be marked done or missed; the planned one (s3) rescheduled, removed or moved to day care. */
  function actionsCourse(over: Partial<TreatmentCourseView> = {}): TreatmentCourseView {
    const base = course();
    return course({
      sittings: [
        base.sittings[0],
        { ...base.sittings[1], displayNo: 2, allowedActions: ['MARK_DONE', 'MISSED'] },
        { ...base.sittings[2], displayNo: 3, allowedActions: ['RESCHEDULE', 'REMOVE', 'REQUEST_ADMISSION'] },
      ],
      ...over,
    });
  }

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  describe('the course (SAH-58-1)', () => {
    it('shows the name, the number, the sub line and the one-line note about the running account', () => {
      boot();
      expect(q('h2')!.textContent).toBe('Dental treatment course');
      expect(q('[data-testid=course-no]')!.textContent).toBe('TC-0412');
      expect(q('.hl')!.textContent).toContain('Started 28 Oct 2026 · Dr Test · estimate signed by patient');
      expect(q('.hl')!.textContent).toContain('one running account: advances and part payments carry across visits');
    });

    it('shows estimate, paid, advance balance and due today in rupees with Indian grouping', () => {
      boot(course(), account({ estimatePaise: 12500000, paidPaise: 600000 }));
      const stats = qa('.stat').map((s) => [s.querySelector('.sl')!.textContent, s.querySelector('.sv')!.textContent]);
      expect(stats).toEqual([['Course estimate', '₹1,25,000'], ['Paid so far', '₹6,000'], ['Advance balance', '₹2,750'], ['Due today', '₹0']]);
    });

    it('shows the three views Visits, Table and Payments', () => {
      boot();
      expect(qa('[role=tab]').map((t) => t.textContent!.trim())).toEqual(['Visits', 'Table', 'Payments']);
    });

    it('draws one step for each sitting with its number, date and encounter', () => {
      boot();
      const steps = qa('.step');
      expect(steps).toHaveLength(3);
      expect(steps[0].textContent).toContain('S1');
      expect(steps[0].textContent).toContain('28 Oct 2026');
      expect(steps[1].classList).toContain('today');
    });

    it('lists the patient\'s other courses and opens one', () => {
      boot();
      const router = TestBed.inject(Router);
      const go = vi.spyOn(router, 'navigate').mockResolvedValue(true);
      // a second course makes the switcher appear
      fixture.componentInstance['others'].set([
        { id: 'c1', courseNo: 'TC-0412', kind: 'DENTAL', unit: 'SITTING', name: 'Dental treatment course', startedOn: '2026-10-28', status: 'ACTIVE', sittingCount: 3, doneCount: 1 },
        { id: 'c2', courseNo: 'TC-0413', kind: 'REHAB', unit: 'SESSION', name: 'Rehab package', startedOn: '2026-11-01', status: 'ACTIVE', sittingCount: 10, doneCount: 0 },
      ]);
      fixture.detectChanges();
      const buttons = qa('[data-testid=other-courses] .oc');
      expect(buttons).toHaveLength(2);
      expect(buttons[0].getAttribute('aria-current')).toBe('true');
      buttons[1].click();
      expect(go).toHaveBeenCalledWith(['/app/courses', 'c2']);
    });

    it('shows an error with a retry when the course cannot be opened', () => {
      TestBed.configureTestingModule({
        providers: [
          provideHttpClient(withInterceptors([problemDetailInterceptor])),
          provideHttpClientTesting(),
          provideRouter([]),
          { provide: ActivatedRoute, useValue: { paramMap: of({ get: () => 'c1' }), snapshot: { queryParamMap: { get: () => null } } } },
          { provide: ShellStore, useValue: { me: () => ({ role: 'OWNER' }) } },
        ],
      });
      http = TestBed.inject(HttpTestingController);
      fixture = TestBed.createComponent(CoursePage);
      fixture.detectChanges();
      http.expectOne(`${OPD}/c1`).flush({ type: 'https://sahaj.in/problems/course-not-found', title: 'x', status: 404, detail: 'This course does not exist.' }, { status: 404, statusText: 'Not Found' });
      fixture.detectChanges();
      el = fixture.nativeElement as HTMLElement;
      expect(q('[role=alert]')!.textContent).toContain('This course does not exist.');
      q<HTMLButtonElement>('[data-act=retry]')!.click();
      http.expectOne(`${OPD}/c1`).flush(course());
      http.expectOne(`${BILLING}/c1/account`).flush(account());
      http.expectOne((r) => r.url === OPD).flush([]);
      fixture.detectChanges();
      expect(q('h2')!.textContent).toBe('Dental treatment course');
    });
  });

  describe('Visits and Table', () => {
    it('shows each sitting as a card with its status chip, encounter, items, charge and note', () => {
      boot();
      const cards = qa('[data-testid=timeline] .card');
      expect(cards).toHaveLength(3);
      expect(cards[0].textContent).toContain('Sitting 1 · 28 Oct 2026');
      expect(cards[0].textContent).toContain('OPD visit · OPD-10231');
      expect(cards[0].textContent).toContain('Done');
      expect(cards[0].textContent).toContain('₹1,750');
      expect(cards[1].querySelector('.st')!.textContent).toBe('Today');
      expect(cards[2].textContent).toContain('Planned');
    });

    it('shows "Billed in IPD" with the stay total for a sitting linked to an admission', () => {
      boot(course({ sittings: [sitting({ encounterKind: 'IPD', encounterLabel: 'Delivery admission', encounterRef: 'IP-2232', settledInIpd: true, ipdBillTotalPaise: 5500000, status: 'TODAY', admissionId: 'a1' })] }));
      expect(q('.card')!.textContent).toContain('Billed in IPD · ₹55,000');
      expect(q('.card')!.textContent).toContain('IPD · Delivery admission · IP-2232');
    });

    it('shows the same data in the Table view with the columns of the mockup', () => {
      boot();
      view('table');
      expect(qa('[data-testid=course-table] th').map((h) => h.textContent!.trim())).toEqual(['#', 'Date', 'Encounter', 'Work', 'Charge', 'Status']);
      expect(qa('[data-testid=course-table] tbody tr')).toHaveLength(3);
      expect(qa('[data-testid=course-table] tbody tr')[0].textContent).toContain('S1');
    });
  });

  describe('planning (SAH-58-2)', () => {
    it('adds a sitting with the choices of the course kind, and one tap sends one key', () => {
      boot();
      expect(qa('.add [data-add]').map((b) => b.textContent!.trim())).toEqual(['+ OPD sitting', '+ Day-care admission', '+ IPD admission']);
      click('[data-add=opd_sitting]');
      const req = http.expectOne(`${OPD}/c1/sittings`);
      expect(req.request.body).toEqual({ revision: 5, addOptionKey: 'opd_sitting' });
      const key = req.request.headers.get('Idempotency-Key');
      expect(key).toMatch(/^[0-9a-f-]{36}$/);
      expect(q<HTMLButtonElement>('[data-add=opd_sitting]')!.disabled).toBe(true);
      req.flush(course({ revision: 6, sittings: [...course().sittings, sitting({ id: 's4', sittingNo: 4 })] }));
      http.expectOne(`${BILLING}/c1/account`).flush(account());
      fixture.detectChanges();
      expect(toast()).toBe('OPD sitting added to the course');
      expect(qa('[data-testid=timeline] .card')).toHaveLength(4);
    });

    it('sends the same key again after a failure, so a retry makes one sitting', () => {
      boot();
      click('[data-add=opd_sitting]');
      const first = http.expectOne(`${OPD}/c1/sittings`);
      const key = first.request.headers.get('Idempotency-Key');
      first.flush({}, { status: 0, statusText: 'Unknown Error' });
      fixture.detectChanges();
      click('[data-add=opd_sitting]');
      const second = http.expectOne(`${OPD}/c1/sittings`);
      expect(second.request.headers.get('Idempotency-Key')).toBe(key);
      second.flush(course({ revision: 6 }));
      http.expectOne(`${BILLING}/c1/account`).flush(account());
    });

    it('offers no choice for day care or IPD when IPD is off, because the server leaves them out', () => {
      boot(course({ addOptions: [{ key: 'opd_sitting', label: 'OPD sitting', encounterKind: 'OPD' }] }));
      expect(qa('.add [data-add]')).toHaveLength(1);
      expect(q('[data-act=move]')).toBeNull();
    });

    it('moves a planned OPD sitting to day care and says an admission request was created (SAH-75-10)', () => {
      boot(actionsCourse());
      click('[data-action=REQUEST_ADMISSION]');
      const req = http.expectOne(`${OPD}/c1/sittings/s3/admission-request`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ revision: 5, encounterKind: 'DAY_CARE' });
      req.flush(course({ revision: 6 }));
      http.expectOne(`${BILLING}/c1/account`).flush(account());
      fixture.detectChanges();
      expect(toast()).toBe('Will be done as a day-care admission. Admission request created.');
    });

    it('changes the date, work and charge of a planned sitting only', () => {
      boot();
      expect(qa('[data-testid=timeline] .card')[0].querySelector('[data-act=edit]')).toBeNull();
      qa('[data-testid=timeline] .card')[2].querySelector<HTMLButtonElement>('[data-act=edit]')!.click();
      fixture.detectChanges();
      const form = q('[data-testid=edit-form]')!;
      const set = (sel: string, v: string) => {
        const input = form.querySelector<HTMLInputElement>(sel)!;
        input.value = v;
        input.dispatchEvent(new Event('input'));
      };
      set('input[type=date]', '2026-11-20');
      set('textarea', 'Obturation\nFilling 47');
      set('input[inputmode=numeric]', '3,500');
      click('[data-act=save-edit]');
      const req = http.expectOne(`${OPD}/c1/sittings/s3`);
      expect(req.request.body).toEqual({ revision: 5, encounterKind: 'OPD', plannedDate: '2026-11-20', items: ['Obturation', 'Filling 47'], chargePaise: 350000, note: null });
      req.flush(course({ revision: 6 }));
      http.expectOne(`${BILLING}/c1/account`).flush(account());
    });

    it('asks for whole rupees and sends nothing when the charge is not a number', () => {
      boot();
      qa('[data-testid=timeline] .card')[2].querySelector<HTMLButtonElement>('[data-act=edit]')!.click();
      fixture.detectChanges();
      const input = q<HTMLInputElement>('[data-testid=edit-form] input[inputmode=numeric]')!;
      input.value = '12.5';
      input.dispatchEvent(new Event('input'));
      click('[data-act=save-edit]');
      expect(q('[data-testid=plan-error]')!.textContent).toContain('Enter the charge in whole rupees.');
    });

    it('shows the server message when the course was changed elsewhere', () => {
      boot(actionsCourse());
      click('[data-action=REMOVE]');
      http.expectOne(`${OPD}/c1/sittings/s3/remove`).flush({ type: 'https://sahaj.in/problems/course-changed', title: 'x', status: 409 }, { status: 409, statusText: 'Conflict' });
      http.expectOne(`${OPD}/c1`).flush(course({ revision: 6 }));
      http.expectOne(`${BILLING}/c1/account`).flush(account());
      fixture.detectChanges();
      expect(q('[data-testid=plan-error]')!.textContent).toContain('This sitting changed. Reload.');
    });

    it('links today\'s visit to a planned sitting when the course is opened from today\'s EMR', () => {
      boot(course(), account(), { visitId: 'v7' });
      click('[data-act=link]');
      const req = http.expectOne(`${OPD}/c1/sittings/s3/link-visit`);
      expect(req.request.body).toEqual({ revision: 5, visitId: 'v7' });
      req.flush(course({ revision: 6 }));
      http.expectOne(`${BILLING}/c1/account`).flush(account());
      fixture.detectChanges();
      expect(toast()).toContain('is today’s sitting');
    });

    it('cannot plan or change anything for front desk, billing or a doctor who is not the course doctor', () => {
      boot(course({ canPlan: false }), account({ canCollect: false }), { role: 'BILLING' });
      expect(q('.add')).toBeNull();
      expect(q('[data-act=edit]')).toBeNull();
      expect(q('[data-act=move]')).toBeNull();
      expect(q('[data-act=link]')).toBeNull();
    });

    it('opens the admission sheet for "Admit now" with the patient and the sitting, then reloads', () => {
      boot(course({ canAdmit: true, sittings: [sitting({ id: 's9', sittingNo: 9, encounterKind: 'IPD', encounterLabel: 'Delivery admission', admissionRequested: true, allowedActions: ['ADMIT'] })] }));
      dialogResult = { ipNumber: 'IP-2232' };
      click('[data-action=ADMIT]');
      expect(dialogOpen.mock.calls[0][1].data).toEqual({ patientId: 'p1', treatmentCourseSittingId: 's9' });
      http.expectOne(`${OPD}/c1`).flush(course());
      http.expectOne(`${BILLING}/c1/account`).flush(account());
      http.expectOne((r) => r.url === OPD).flush([]);
      fixture.detectChanges();
      expect(toast()).toBe('Admitted · IP-2232. Advance from this course is carried to the IPD bill.');
    });

    it('offers "Admit now" only to those who may admit, and only for a planned stay', () => {
      boot(course({ canAdmit: false, sittings: [sitting({ encounterKind: 'IPD', encounterLabel: 'Delivery admission' })] }));
      expect(q('[data-action=ADMIT]')).toBeNull();
    });
  });

  describe('sitting actions drawn from the server\'s list (SAH-75-10)', () => {
    const refresh = (next: TreatmentCourseView): void => {
      http.expectOne(`${BILLING}/c1/account`).flush(account());
      fixture.detectChanges();
      expect(next).toBeTruthy();
    };

    it('draws one button for each allowed action, in the words of the design, and none for a done sitting', () => {
      boot(actionsCourse());
      const today = qa('article.card')[1];
      expect(Array.from(today.querySelectorAll('[data-action]')).map((b) => b.textContent!.trim())).toEqual(['Mark sitting done', 'Patient did not come']);
      const planned = qa('article.card')[2];
      const words = Array.from(planned.querySelectorAll('[data-action]')).map((b) => b.textContent!.trim());
      expect(words).toEqual(['Reschedule: 18 Nov 2026 ▾', 'Remove', 'Move to day care / IPD']);
      expect(qa('article.card')[0].querySelectorAll('[data-action]')).toHaveLength(0);
    });

    it('draws no action for a role the server gave none (front desk, billing)', () => {
      boot(course({ canPlan: false }), account({ canCollect: false }), { role: 'BILLING' });
      expect(qa('[data-action]')).toHaveLength(0);
    });

    it('marks today\'s sitting done with the revision and the visit of the EMR, and says the charge the server returns', () => {
      boot(actionsCourse(), account(), { visitId: 'v7' });
      click('[data-action=MARK_DONE]');
      const req = http.expectOne(`${OPD}/c1/sittings/s2/mark-done`);
      expect(req.request.body).toEqual({ revision: 5, visitId: 'v7' });
      const base = course();
      req.flush(course({ revision: 6, sittings: [base.sittings[0], { ...base.sittings[1], displayNo: 2, status: 'DONE', chargePaise: 150000 }, base.sittings[2]] }));
      refresh(base);
      expect(toast()).toBe('Sitting 2 done · ₹1,500 added to the account');
    });

    it('says "billed in IPD" when the sitting is settled in IPD', () => {
      boot(actionsCourse());
      click('[data-action=MARK_DONE]');
      const req = http.expectOne(`${OPD}/c1/sittings/s2/mark-done`);
      const base = course();
      req.flush(course({ revision: 6, sittings: [base.sittings[0], { ...base.sittings[1], displayNo: 2, status: 'DONE', settledInIpd: true }, base.sittings[2]] }));
      refresh(base);
      expect(toast()).toBe('Sitting 2 done · billed in IPD');
    });

    it('marks "Patient did not come" with no charge and the toast "Marked missed."', () => {
      boot(actionsCourse());
      click('[data-action=MISSED]');
      const req = http.expectOne(`${OPD}/c1/sittings/s2/missed`);
      expect(req.request.body).toEqual({ revision: 5 });
      const base = course();
      req.flush(course({ revision: 6, sittings: [base.sittings[0], { ...base.sittings[1], status: 'MISSED', allowedActions: ['REBOOK'] }, base.sittings[2]] }));
      refresh(base);
      expect(toast()).toBe('Marked missed.');
      expect(qa('article.card')[1].textContent).toContain('Missed');
      expect(qa('article.card')[1].querySelector('[data-action=REBOOK]')!.textContent!.trim()).toBe('Rebook');
    });

    it('rebooks a missed sitting for a chosen date, today or later', () => {
      const base = course();
      boot(course({ sittings: [{ ...base.sittings[0], status: 'MISSED', allowedActions: ['REBOOK'] }] }));
      click('[data-action=REBOOK]');
      const input = q<HTMLInputElement>('[data-testid=date-choice] input')!;
      input.value = '2020-01-01';
      input.dispatchEvent(new Event('input'));
      click('[data-act=confirm-date]');
      expect(q('[data-testid=date-error]')!.textContent).toBe('Choose today or a later date.');
      http.expectNone(`${OPD}/c1/sittings/s1/rebook`);
      input.value = '2099-12-01';
      input.dispatchEvent(new Event('input'));
      click('[data-act=confirm-date]');
      const req = http.expectOne(`${OPD}/c1/sittings/s1/rebook`);
      expect(req.request.body).toEqual({ revision: 5, plannedDate: '2099-12-01' });
      req.flush(course({ revision: 6 }));
      refresh(base);
      expect(toast()).toBe('Rebooked for 1 Dec 2099');
    });

    it('reschedules a planned sitting to a new date with the revision', () => {
      boot(actionsCourse());
      click('[data-action=RESCHEDULE]');
      const input = q<HTMLInputElement>('[data-testid=date-choice] input')!;
      input.value = '2099-12-05';
      input.dispatchEvent(new Event('input'));
      click('[data-act=confirm-date]');
      const req = http.expectOne(`${OPD}/c1/sittings/s3/reschedule`);
      expect(req.request.body).toEqual({ revision: 5, plannedDate: '2099-12-05' });
      req.flush(course({ revision: 6 }));
      refresh(course());
      expect(toast()).toContain('moved to 5 Dec 2099');
    });

    it('removes a planned sitting and says "<Unit> removed from plan"', () => {
      boot(actionsCourse());
      click('[data-action=REMOVE]');
      const req = http.expectOne(`${OPD}/c1/sittings/s3/remove`);
      expect(req.request.body).toEqual({ revision: 5 });
      req.flush(course({ revision: 6 }));
      refresh(course());
      expect(toast()).toBe('Sitting removed from plan');
    });

    it('names a sitting by its renumbered number, not the stable one (after a removal)', () => {
      const base = course();
      boot(course({ sittings: [{ ...base.sittings[0], sittingNo: 1, displayNo: 1 }, { ...base.sittings[2], sittingNo: 3, displayNo: 2 }] }));
      expect(qa('.steps .sl').map((e) => e.textContent!.trim())).toEqual(['S1', 'S2']);
      expect(qa('article.card strong')[1].textContent).toContain('Sitting 2');
    });

    it('shows a second press or a stale screen as "This sitting changed. Reload." and reads the course again', () => {
      boot(actionsCourse());
      click('[data-action=MARK_DONE]');
      http.expectOne(`${OPD}/c1/sittings/s2/mark-done`).flush({ type: 'https://sahaj.in/problems/course-changed', title: 'x', status: 409 }, { status: 409, statusText: 'Conflict' });
      http.expectOne(`${OPD}/c1`).flush(course({ revision: 6 }));
      refresh(course());
      expect(q('[data-testid=plan-error]')!.textContent).toContain('This sitting changed. Reload.');
    });

    it('shows the server\'s message for a refused action and leaves the screen as it was', () => {
      boot(actionsCourse());
      click('[data-action=MISSED]');
      http.expectOne(`${OPD}/c1/sittings/s2/missed`).flush({ type: 'https://sahaj.in/problems/sitting-not-available', title: 'x', status: 409, detail: 'The patient came for this sitting.' }, { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();
      expect(q('[data-testid=plan-error]')!.textContent).toContain('The patient came for this sitting.');
      expect(qa('article.card')[1].querySelectorAll('[data-action]')).toHaveLength(2);
    });

    it('sub line adds "estimate signed by patient" only when the estimate was signed (G15-4)', () => {
      boot(course({ estimateSigned: false, note: null }));
      expect(q('.hl')!.textContent).toContain('Started 28 Oct 2026 · Dr Test');
      expect(q('.hl')!.textContent).not.toContain('estimate signed by patient');
      expect(q('.hl')!.textContent).not.toContain('sample rates');
    });

    it('shows a chip, a dot and a card for Missed in red, and the Table says Missed', () => {
      const base = course();
      boot(course({ sittings: [{ ...base.sittings[0], status: 'MISSED' }] }));
      expect(q('.step.missed')).not.toBeNull();
      expect(q('article.card.missed')).not.toBeNull();
      view('table');
      expect(q('[data-testid=course-table]')!.textContent).toContain('Missed');
    });
  });

  describe('Payments (SAH-58-3, SAH-58-4)', () => {
    it('offers the four payment types and starts with Today\'s sitting, taking the advance off first', () => {
      boot(course(), account({ creditPaise: 50000, dueFromEarlierPaise: 100000, todayChargePaise: 150000, dueTodayPaise: 200000 }));
      view('payments');
      expect(qa('.type b').map((b) => b.textContent)).toEqual(['Today’s sitting', 'Full course balance', 'Part payment', 'Advance']);
      expect(q('[data-testid=amount]')!.textContent).toBe('₹2,000');
      expect(q('[data-act=collect]')!.textContent!.trim()).toBe('Collect ₹2,000 by UPI');
    });

    it('asks the full amount when the advance is not used first', () => {
      boot(course(), account({ creditPaise: 50000, dueFromEarlierPaise: 100000, todayChargePaise: 150000 }));
      view('payments');
      click('[data-act=use-credit]');
      expect(q('[data-testid=amount]')!.textContent).toBe('₹2,500');
    });

    it('suggests half of today\'s amount for a part payment and the balance for the full balance', () => {
      boot();
      view('payments');
      click('[data-type=partial]');
      expect(q('[data-testid=amount]')!.textContent).toBe('₹500');
      click('[data-type=balance]');
      expect(q('[data-testid=amount]')!.textContent).toBe('₹250');
    });

    it('changes the amount by ₹500 with − and +, never below zero', () => {
      boot();
      view('payments');
      click('[data-type=partial]');
      click('[data-act=more]');
      expect(q('[data-testid=amount]')!.textContent).toBe('₹1,000');
      click('[data-act=less]');
      click('[data-act=less]');
      click('[data-act=less]');
      expect(q('[data-testid=amount]')!.textContent).toBe('₹0');
      expect(q<HTMLButtonElement>('[data-act=collect]')!.disabled).toBe(true);
    });

    it('refuses an amount over the balance for a part payment, with the reason', () => {
      boot();
      view('payments');
      click('[data-type=partial]');
      click('[data-act=more]');
      click('[data-act=more]');
      expect(q('.panel2 .crit')!.textContent).toContain('The balance on the course is ₹250');
      expect(q<HTMLButtonElement>('[data-act=collect]')!.disabled).toBe(true);
    });

    it('lets an advance go over the balance, because it stays as credit', () => {
      boot();
      view('payments');
      click('[data-type=advance]');
      for (let i = 0; i < 4; i++) {
        click('[data-act=more]');
      }
      expect(q('[data-testid=amount]')!.textContent).toBe('₹2,000');
      expect(q<HTMLButtonElement>('[data-act=collect]')!.disabled).toBe(false);
    });

    it('collects once with the type, mode, credit and sitting, and shows the receipt number', () => {
      boot(course(), account({ creditPaise: 0, dueFromEarlierPaise: 0, todayChargePaise: 150000, balancePaise: 450000 }));
      view('payments');
      qa('.mode')[1].click();
      fixture.detectChanges();
      click('[data-act=collect]');
      const req = http.expectOne(`${BILLING}/c1/payments`);
      expect(req.request.body).toEqual({ type: 'VISIT_PAYMENT', amountPaise: 150000, mode: 'CASH', useCredit: true, againstSittingId: 's2' });
      expect(req.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
      expect(q<HTMLButtonElement>('[data-act=collect]')!.disabled).toBe(true);
      req.flush({ receiptNo: 'RC-2400', account: account({ receiptCount: 3 }) });
      fixture.detectChanges();
      expect(toast()).toBe('Payment collected · Receipt RC-2400');
    });

    it('sends the same key on a retry after a lost network, so one payment is recorded', () => {
      boot(course(), account({ creditPaise: 0, todayChargePaise: 150000, balancePaise: 450000 }));
      view('payments');
      click('[data-act=collect]');
      const first = http.expectOne(`${BILLING}/c1/payments`);
      const key = first.request.headers.get('Idempotency-Key');
      first.flush({}, { status: 0, statusText: 'Unknown Error' });
      fixture.detectChanges();
      expect(q('[data-testid=pay-error]')!.textContent).toContain('Nothing was taken twice');
      click('[data-act=collect]');
      const second = http.expectOne(`${BILLING}/c1/payments`);
      expect(second.request.headers.get('Idempotency-Key')).toBe(key);
      second.flush({ receiptNo: 'RC-2400', account: account() });
    });

    it('uses a new key when the payment was changed before the retry', () => {
      boot(course(), account({ creditPaise: 0, todayChargePaise: 150000, balancePaise: 450000 }));
      view('payments');
      click('[data-act=collect]');
      const first = http.expectOne(`${BILLING}/c1/payments`);
      first.flush({}, { status: 0, statusText: 'Unknown Error' });
      fixture.detectChanges();
      click('[data-act=more]');
      click('[data-act=collect]');
      const second = http.expectOne(`${BILLING}/c1/payments`);
      expect(second.request.headers.get('Idempotency-Key')).not.toBe(first.request.headers.get('Idempotency-Key'));
      second.flush({ receiptNo: 'RC-2401', account: account() });
    });

    it('names an IPD deposit for the planned admission', () => {
      boot(course(), account({ nextPlannedAdmission: { sittingId: 's9', sittingNo: 9, chargePaise: 5500000, note: 'Admission deposit is due by 36 weeks.' }, suggestions: { partPaymentPaise: 50000, ipdDepositPaise: 2200000 }, balancePaise: 5000000 }));
      view('payments');
      expect(qa('.type b')[3].textContent).toBe('Advance / IPD deposit');
      click('[data-type=advance]');
      expect(q('[data-testid=amount]')!.textContent).toBe('₹22,000');
      click('[data-act=collect]');
      const req = http.expectOne(`${BILLING}/c1/payments`);
      expect(req.request.body).toMatchObject({ type: 'IPD_DEPOSIT', againstSittingId: 's9', amountPaise: 2200000 });
      req.flush({ receiptNo: 'RC-2402', account: account() });
    });

    it('hides the collect panel from a doctor, and billing, who only read', () => {
      boot(course({ canPlan: false }), account({ canCollect: false }), { role: 'DOCTOR' });
      view('payments');
      expect(q('[data-act=collect]')).toBeNull();
      expect(q('#pay-title')).toBeNull();
      expect(q('#ledger-title')!.textContent).toBe('Receipts and adjustments');
    });

    it('lists receipts and adjustments with their columns, and the note under the panel with the real balance', () => {
      boot();
      view('payments');
      expect(qa('.ledger th').map((h) => h.textContent!.trim())).toEqual(['Date', 'Type', 'Mode', 'Against', 'Amount', 'Receipt']);
      const rows = qa('.ledger tbody tr');
      expect(rows[0].textContent).toContain('Advance');
      expect(rows[0].textContent).toContain('₹5,000');
      expect(rows[1].textContent).toContain('Part payment');
      expect(rows[2].textContent).toContain('Advance used');
      expect(rows[2].textContent).toContain('−₹1,500');
      expect(q('[data-testid=pay-note]')!.textContent).toBe(
        'Charges are added when a sitting is marked done. Advances and IPD deposits stay as credit and are used first. Balance on the course: ₹250.',
      );
    });

    it('says there is no payment yet', () => {
      boot(course(), account({ ledger: [] }));
      view('payments');
      expect(q('[data-testid=ledger-empty]')!.textContent).toBe('No payment yet.');
    });

    it('opens a receipt from its number for the counter roles only', () => {
      boot();
      view('payments');
      click('[data-act=receipt]');
      expect(dialogOpen).toHaveBeenCalledTimes(1);
      expect(dialogOpen.mock.calls[0][1].data).toEqual({ courseId: 'c1', receiptNo: 2201 });
    });

    it('shows a doctor the receipt numbers as text, not as a link', () => {
      boot(course({ canPlan: false }), account({ canCollect: false }), { role: 'DOCTOR' });
      view('payments');
      expect(q('[data-act=receipt]')).toBeNull();
      expect(q('.ledger tbody')!.textContent).toContain('RC-2201');
    });
  });
});

describe('courseRoleGuard (SAH-58-1)', () => {
  function run(role: string | undefined): boolean | UrlTree {
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ShellStore, useValue: { me: () => (role ? { role } : null) } }] });
    return TestBed.runInInjectionContext(() => courseRoleGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot)) as boolean | UrlTree;
  }

  it('lets the owner, doctors, front desk and billing in', () => {
    for (const role of ['OWNER', 'DOCTOR', 'VISITING_DOCTOR', 'FRONT_DESK', 'BILLING']) {
      TestBed.resetTestingModule();
      expect(run(role)).toBe(true);
    }
  });

  it('sends a nurse, a pharmacist, a counsellor and an unknown user to Not allowed', () => {
    for (const role of ['NURSE', 'PHARMACIST', 'COUNSELLOR', undefined]) {
      TestBed.resetTestingModule();
      const result = run(role);
      expect(result).toBeInstanceOf(UrlTree);
      expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/app/not-allowed');
    }
  });
});
