import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ScrollTo } from '../../../core/scroll/scroll-to.service';
import { AdmitDialogComponent } from '../../ipd/admit/admit-dialog.component';
import { casePaperContext, emptyPastPage, record } from '../emr.fixtures';
import { EmrRecordView } from '../emr.model';
import { FinishDialogComponent } from '../finish/finish-dialog.component';
import { AdmitAdviceDialogComponent } from './admit-advice-dialog.component';
import { EmrAdmitRequestDialogComponent } from './emr-admit-request-dialog.component';
import { EmrRecordPage } from './emr-record.page';

const EMR = '/api/v1/opd/emr/records';

describe('EmrRecordPage', () => {
  let fixture: ComponentFixture<EmrRecordPage>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let snack: ReturnType<typeof vi.fn>;
  let dialogOpen: ReturnType<typeof vi.fn>;
  let dialogResult: unknown;
  let scroll: { section: ReturnType<typeof vi.fn> };
  let router: { currentNavigation: () => unknown; navigateByUrl: ReturnType<typeof vi.fn> };

  async function boot(view: EmrRecordView = record(), navState: unknown = null): Promise<void> {
    snack = vi.fn();
    dialogResult = undefined;
    dialogOpen = vi.fn(() => ({ afterClosed: () => of(dialogResult) }));
    scroll = { section: vi.fn() };
    router = {
      currentNavigation: () => ({ previousNavigation: null, extras: { state: navState } }),
      navigateByUrl: vi.fn(),
    };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: (key: string) => (key === 'recordId' ? 'r1' : null) } } } },
        { provide: Router, useValue: router },
        { provide: MatSnackBar, useValue: { open: snack } },
        { provide: MatDialog, useValue: { open: dialogOpen } },
        { provide: ScrollTo, useValue: scroll },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EmrRecordPage);
    fixture.detectChanges();
    http.expectOne(`${EMR}/r1`).flush(view);
    await settle();
    el = fixture.nativeElement as HTMLElement;
  }

  /**
   * The Search list asks for the doctor's quick options when a card is drawn; answer with none. The
   * record also asks for the case paper context, the key facts and the first page of the history.
   */
  function flushQuick(): void {
    http
      .match((req) => req.url.includes('/emr/quick-options/'))
      .forEach((req) => req.flush({ master: 'complaints', items: [] }));
    http.match((req) => req.url.endsWith('/case-paper')).forEach((req) => req.flush(casePaperContext()));
    http.match((req) => req.url.endsWith('/key-facts')).forEach((req) => req.flush({ starterKey: 'gm', facts: [] }));
    http.match((req) => req.url.includes('/past-visits')).forEach((req) => req.flush(emptyPastPage()));
    http.match((req) => req.url.endsWith('/encounter-vitals')).forEach((req) => req.flush({ current: null, earlier: null, sources: { opd: 'NOT_CAPTURED', ipd: 'AVAILABLE', er: 'NOT_CAPTURED' } }));
    http.match((req) => req.url.includes('/allergy-classes')).forEach((req) => req.flush([]));
    http.match((req) => req.url.includes('/medicine-favourites')).forEach((req) => req.flush([]));
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    flushQuick();
    await fixture.whenStable();
    fixture.detectChanges();
    flushQuick();
  }

  const q = <T extends Element = HTMLElement>(sel: string) => el.querySelector<T>(sel);
  const qa = (sel: string) => Array.from(el.querySelectorAll<HTMLElement>(sel));
  const act = (name: string) => q<HTMLButtonElement>(`[data-act=${name}]`)!;
  const footer = () => q('[data-testid=emr-footer] .status')!.textContent!.trim();
  const tab = (name: string) => qa('[role=tab]').find((t) => t.textContent!.includes(name))!;

  async function type(target: HTMLInputElement, text: string): Promise<void> {
    target.value = text;
    target.dispatchEvent(new Event('input'));
    await settle();
  }

  afterEach(() => {
    flushQuick();
    http.verify();
  });

  describe('header (SAH-47-1, SAH-52-1)', () => {
    it('shows the title, the department, the patient strip, the doctor and the room', async () => {
      await boot();
      const header = q('[data-testid=emr-header]')!.textContent!.replace(/\s+/g, ' ');
      expect(header).toContain('Consultation');
      expect(header).toContain('General Medicine');
      expect(header).toContain('Test Patient');
      expect(header).toContain('52 y · Male · UHID U-000042 · Follow-up, 3rd visit');
      expect(header).toContain('Dr Test · Room 2');
    });

    it('shows the allergy, known conditions and last visit as tags with text', async () => {
      await boot();
      const tags = qa('.tags .pill').map((p) => p.textContent!.trim());
      expect(tags).toEqual(['Allergy: Sulfa drugs', 'Known: Type 2 DM', 'Last visit: 14 days ago']);
    });

    it('shows no allergy tag and "New patient" when nothing is recorded', async () => {
      const view = record();
      view.header = { ...view.header, allergyText: null, chronicText: null, lastVisitDaysAgo: null, uhid: null };
      await boot(view);
      expect(q('.tags')!.textContent).not.toContain('Allergy');
      expect(q('.tags')!.textContent).not.toContain('No known allergy');
      expect(q('.pid')!.textContent).toContain('New patient');
    });

    it('never shows the ABHA tag (it waits for DEC-040)', async () => {
      await boot();
      expect(el.textContent).not.toContain('ABHA');
    });

    it('says the starter layout is used', async () => {
      const view = record();
      view.layout = { ...view.layout, layoutSource: 'STARTER', versionNo: null };
      await boot(view);
      expect(q('[data-testid=layout-note]')!.textContent).toBe('Using the starter layout');
    });

    it('lets the doctor switch the department while the visit is a draft', async () => {
      await boot();
      const select = q<HTMLSelectElement>('[data-act=switch-department]')!;
      expect(select.querySelectorAll('option')).toHaveLength(2);
      select.value = 'dental';
      select.dispatchEvent(new Event('change'));
      const req = http.expectOne(`${EMR}/r1/department`);
      expect(req.request.body).toEqual({ departmentKey: 'dental', revision: 3 });
      req.flush(record({ revision: 4 }));
    });

    it('hides the department switch on a read-only record', async () => {
      await boot(record({ readOnly: true, readOnlyReason: 'SIGNED' }));
      expect(q('[data-act=switch-department]')).toBeNull();
    });
  });

  describe('OPD, IPD and Emergency differences (SAH-52)', () => {
    it('OPD: visit type control with Emergency available, and the Admit to IPD button', async () => {
      await boot();
      const buttons = qa('.seg button');
      expect(buttons.map((b) => b.textContent!.trim())).toEqual(['New', 'Follow-up', 'Revisit', 'Emergency']);
      expect(buttons[1].getAttribute('aria-pressed')).toBe('true');
      expect((buttons[3] as HTMLButtonElement).disabled).toBe(false);
      expect(buttons[3].getAttribute('title')).toBeNull();
      expect(act('admit').textContent).toContain('Admit to IPD');
    });

    it('OPD: the doctor changes the visit type', async () => {
      await boot();
      qa('.seg button')[2].click();
      const req = http.expectOne('/api/v1/opd/visits/v1/visit-type');
      expect(req.request.body).toEqual({ visitType: 'REVISIT' });
      req.flush({});
      await settle();
      expect(qa('.seg button')[2].getAttribute('aria-pressed')).toBe('true');
      expect(q('.pid')!.textContent).toContain('Revisit, 3rd visit');
    });

    it('IPD: reads "Daily progress note", "IPD · day n" and has no visit-type control or Admit button', async () => {
      const view = record({ encounterType: 'IPD_ROUND', roundDate: '2026-11-12' });
      view.header = {
        ...view.header,
        visitType: null,
        visitOrdinal: null,
        admission: { admissionId: 'a1', ipNumber: 'IP/26-27/0042', dayNumber: 3, status: 'ADMITTED' },
      };
      await boot(view);
      expect(q('h2')!.textContent).toBe('Daily progress note');
      expect(q('[data-testid=ipd-day]')!.textContent).toBe('IPD · day 3');
      expect(q('.seg')).toBeNull();
      expect(act('admit')).toBeNull();
    });

    it('Emergency: reads "Emergency assessment" and the triage level, and keeps Admit to IPD', async () => {
      const view = record({ encounterType: 'ER_ASSESSMENT' });
      view.header = {
        ...view.header,
        visitType: null,
        visitOrdinal: null,
        emergency: { caseId: 'c1', triageLevel: 1, triageLevelName: 'Red · immediate', status: 'WAITING' },
      };
      await boot(view);
      expect(q('h2')!.textContent).toBe('Emergency assessment');
      expect(q('[data-testid=triage]')!.textContent).toBe('Red · immediate');
      expect(q('.seg')).toBeNull();
      expect(act('admit')).not.toBeNull();
    });

    it('says the queue status was already updated when the open found it moved', async () => {
      await boot(record(), { stageOutcome: 'ALREADY_UPDATED' });
      expect(q('[data-testid=stage-note]')!.textContent).toContain('The queue status was already updated.');
    });

    it('says nothing about the queue after an ordinary open', async () => {
      await boot(record(), { stageOutcome: 'MOVED' });
      expect(q('[data-testid=stage-note]')).toBeNull();
    });
  });

  describe('Admit to IPD (SAH-52-4)', () => {
    const prefill = (over: Record<string, unknown> = {}) => ({
      patient: { patientId: 'p1', name: 'Test Patient', age: 52, sex: 'M', mobile: '9876543210' },
      suggestedType: 'planned',
      diagnoses: ['Viral fever'],
      procedure: null,
      plannedStayDays: null,
      emergencyCaseId: null,
      openRequest: null,
      openAdmission: null,
      canAdmitDirectly: false,
      ...over,
    });

    it('with IPD on: reads the facts and opens the admission request sheet, without marking anything yet', async () => {
      await boot();
      dialogResult = null;
      act('admit').click();
      http.expectOne(`${EMR}/r1/admission-prefill`).flush(prefill());
      await settle();
      expect(dialogOpen).toHaveBeenCalledWith(
        EmrAdmitRequestDialogComponent,
        expect.objectContaining({ data: { prefill: expect.objectContaining({ suggestedType: 'planned' }) } }),
      );
      http.expectNone(`${EMR}/r1/admission-advice`);
    });

    it('a doctor sends the request: one message, no admission sheet', async () => {
      await boot();
      const request = { id: 'q1', status: 'OPEN', admissionType: 'planned', plannedStayDays: 3, createdAt: '2026-11-13T05:00:00Z', admissionId: null, ipNumber: null };
      dialogResult = { request, prefill: prefill(), openSheet: false };
      act('admit').click();
      http.expectOne(`${EMR}/r1/admission-prefill`).flush(prefill());
      await settle();
      expect(snack).toHaveBeenCalledWith('Admission request sent', 'Close', expect.anything());
      expect(dialogOpen).not.toHaveBeenCalledWith(AdmitDialogComponent, expect.anything());
    });

    it('the owner goes on to the existing sheet, pre-filled with the request, the type and the diagnoses', async () => {
      await boot();
      const request = { id: 'q1', status: 'OPEN', admissionType: 'daycare', plannedStayDays: null, createdAt: '2026-11-13T05:00:00Z', admissionId: null, ipNumber: null };
      dialogResult = { request, prefill: prefill({ canAdmitDirectly: true }), openSheet: true };
      act('admit').click();
      http.expectOne(`${EMR}/r1/admission-prefill`).flush(prefill({ canAdmitDirectly: true }));
      await settle();
      expect(dialogOpen).toHaveBeenCalledWith(
        AdmitDialogComponent,
        expect.objectContaining({
          data: expect.objectContaining({ admissionRequestId: 'q1', admissionType: 'daycare', patientId: 'p1', reason: 'Viral fever' }),
        }),
      );
      http.match(`${EMR}/r1`).forEach((r) => r.flush(record()));
    });

    it('shows the button as pressed with the state of the record', async () => {
      await boot(
        record({ admissionAdvised: true, admission: { state: 'REQUESTED', requestId: 'q1', admissionId: null, ipNumber: null } }),
      );
      expect(act('admit').textContent).toContain('Admission requested');
      expect(act('admit').getAttribute('aria-pressed')).toBe('true');
    });

    it('shows "Already admitted" with the IP number and opens that admission even when the visit is read-only', async () => {
      await boot(
        record({
          readOnly: true,
          readOnlyReason: 'SIGNED',
          status: 'SIGNED',
          admission: { state: 'ALREADY_ADMITTED', requestId: null, admissionId: 'a9', ipNumber: 'IP/26-27/0042' },
        }),
      );
      expect(act('admit').textContent).toContain('Already admitted · IP/26-27/0042');
      expect(act('admit').disabled).toBe(false);
      act('admit').click();
      expect(dialogOpen).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ data: { id: 'a9' } }));
    });

    it('with IPD off: opens the OPD-only sheet, and the advice carries the referral line', async () => {
      const view = record({ ipd: { state: 'OFF', canSwitchOn: true } });
      await boot(view);
      dialogResult = { referralLine: 'Refer to a surgeon' };
      act('admit').click();
      expect(dialogOpen).toHaveBeenCalledWith(
        AdmitAdviceDialogComponent,
        expect.objectContaining({ data: { canSwitchOn: true } }),
      );
      const req = http.expectOne(`${EMR}/r1/admission-advice`);
      expect(req.request.body).toEqual({ revision: 3, referralLine: 'Refer to a surgeon' });
      req.flush(record({ revision: 4, admissionAdvised: true, referralLine: 'Refer to a surgeon' }));
    });

    it('with IPD off: does nothing when the sheet is cancelled, and tells a non-owner nothing about switching on', async () => {
      await boot(record({ ipd: { state: 'OFF', canSwitchOn: false } }));
      dialogResult = null;
      act('admit').click();
      expect(dialogOpen).toHaveBeenCalledWith(
        AdmitAdviceDialogComponent,
        expect.objectContaining({ data: { canSwitchOn: false } }),
      );
      http.expectNone(`${EMR}/r1/admission-advice`);
    });

    it('is switched off on a read-only record', async () => {
      await boot(record({ readOnly: true, readOnlyReason: 'SIGNED' }));
      expect(act('admit').disabled).toBe(true);
    });
  });

  describe('two parts (SAH-47-2)', () => {
    it('shows both tabs with subtitles and "n of m required"', async () => {
      await boot();
      const tabs = qa('.part');
      expect(tabs).toHaveLength(2);
      expect(tabs[0].textContent).toContain('Assessment');
      expect(tabs[0].textContent).toContain('Complaints and examination');
      expect(tabs[0].textContent).toContain('0 of 1 required');
      expect(tabs[1].textContent).toContain('Diagnosis, medicines, tests and services');
    });

    it('uses the part lines of the configuration page for a department without its own words', async () => {
      const view = record();
      view.layout = { ...view.layout, starterKey: 'physio' };
      await boot(view);
      expect(qa('.part')[0].textContent).toContain('What the doctor finds');
    });

    it('shows only the sections of the open part, in the snapshot order', async () => {
      await boot();
      expect(qa('app-section-card h3').map((h) => h.textContent!.trim().replace(' *', ''))).toEqual([
        'Vitals',
        'Chief complaints',
        'Allergies',
      ]);
      tab('Treatment').click();
      await settle();
      expect(qa('app-section-card h3').map((h) => h.textContent!.trim().replace(' *', ''))).toEqual([
        'Diagnosis',
        'Prescription (Rx)',
        'Dental chart (odontogram)',
      ]);
    });

    it('shows a section moved to the other part in that part', async () => {
      const view = record();
      view.layout.sections = view.layout.sections.map((s) => (s.key === 'vitals' ? { ...s, part: 'TREATMENT' as const } : s));
      await boot(view);
      expect(qa('app-section-card h3').map((h) => h.textContent!.trim().replace(' *', ''))).not.toContain('Vitals');
    });

    it('opens the part saved with the draft', async () => {
      await boot(record({ uiState: { part: 'TREATMENT', sectionKey: null } }));
      expect(tab('Treatment').getAttribute('aria-selected')).toBe('true');
    });

    it('lists both parts in the left list with done over total', async () => {
      await boot();
      const heads = qa('.nav-h').map((h) => h.textContent!.replace(/\s+/g, ' ').trim());
      expect(heads).toEqual(['1Assessment0/3', '2Treatment0/3']);
      const items = qa('.nav-i').map((i) => i.textContent!.replace(/\s+/g, ' ').trim());
      expect(items[1]).toBe('! Chief complaintsRequired');
    });

    it('moves between parts with Continue and Back even when something is missing', async () => {
      await boot();
      act('continue').click();
      await settle();
      expect(tab('Treatment').getAttribute('aria-selected')).toBe('true');
      expect(q('.recap')!.textContent).toContain('From assessment:');
      expect(q('.recap')!.textContent).toContain('nothing recorded yet');
      act('back-to-assessment').click();
      await settle();
      expect(tab('Assessment').getAttribute('aria-selected')).toBe('true');
      expect(q('[data-act=continue]')).not.toBeNull();
    });

    it('opens a section from the left list: its part, expanded and marked, scrolled to', async () => {
      await boot();
      qa('.nav-i').find((i) => i.textContent!.includes('Diagnosis'))!.click();
      await settle();
      expect(tab('Treatment').getAttribute('aria-selected')).toBe('true');
      expect(q('#sec-diagnosis')!.classList).toContain('hl');
      expect(scroll.section).toHaveBeenCalledWith('sec-diagnosis');
    });
  });

  describe('sections (SAH-47-3)', () => {
    it('shows each card with a star, a status tag and who can fill', async () => {
      await boot();
      const card = q('#sec-complaints')!;
      expect(card.querySelector('.star')).not.toBeNull();
      expect(card.querySelector('[data-testid=section-status]')!.textContent).toBe('Required');
      expect(card.textContent).toContain('Can be filled by: Doctor');
      expect(q('#sec-vitals [data-testid=section-status]')!.textContent).toBe('Optional');
    });

    it('opens a section collapsed when the snapshot says so, and opens it on a tap', async () => {
      const view = record();
      view.layout.sections = view.layout.sections.map((s) => (s.key === 'vitals' ? { ...s, collapsed: true } : s));
      await boot(view);
      expect(q('#sec-vitals .body')).toBeNull();
      q<HTMLButtonElement>('#sec-vitals .toggle')!.click();
      await settle();
      expect(q('#sec-vitals .body')).not.toBeNull();
    });

    it('renders the library fields with the generic renderer', async () => {
      await boot();
      expect(qa('#sec-vitals app-field-renderer')).toHaveLength(3);
      expect(q('#sec-vitals .name')!.textContent).toContain('BP systolic');
      expect(q('#sec-vitals .meta')!.textContent).toBe('mmHg · alert above 140');
    });

    it('marks a vital outside the limits as High with text', async () => {
      await boot();
      await type(q<HTMLInputElement>('#f-vitals-bp_systolic')!, '150');
      expect(q('#sec-vitals [data-testid=vital-flag]')!.textContent).toBe('High');
    });

    it('shows "Copied from <date>" and Done only after the doctor keeps the values', async () => {
      const view = record();
      view.sections = view.sections.map((s) =>
        s.key === 'vitals'
          ? { ...s, status: 'REQUIRED' as const, values: { bp_systolic: 120, bp_diastolic: 80 }, copiedFrom: { recordId: 'r0', date: '2026-10-24' }, copyPendingConfirmation: true }
          : s,
      );
      view.layout.sections = view.layout.sections.map((s) => (s.key === 'vitals' ? { ...s, required: true } : s));
      await boot(view);
      expect(q('#sec-vitals .copied')!.textContent).toContain('Copied from 24 Oct 2026');
      expect(q('#sec-vitals [data-testid=section-status]')!.textContent).toBe('Required');
      q<HTMLButtonElement>('[data-act=keep-copy]')!.click();
      await settle();
      expect(q('#sec-vitals [data-testid=section-status]')!.textContent).toBe('Done');
    });

    it('shows an empty section list for a part with nothing in it', async () => {
      const view = record();
      view.layout.sections = view.layout.sections.filter((s) => s.part === 'ASSESSMENT');
      view.sections = view.sections.filter((s) => view.layout.sections.some((l) => l.key === s.key));
      await boot(view);
      tab('Treatment').click();
      await settle();
      expect(q('.cards .empty')!.textContent).toContain('No sections in this part');
    });
  });

  describe('what is still needed (SAH-47-4)', () => {
    it('lists the missing required sections in red, in lower case', async () => {
      await boot();
      expect(footer()).toBe('Still needed: chief complaints, diagnosis.');
      expect(q('[data-testid=emr-footer] .status')!.classList).toContain('crit');
    });

    it('turns green and says Ready to complete when every required section is done', async () => {
      const view = record();
      view.sections = view.sections.map((s) =>
        s.key === 'complaints'
          ? { ...s, status: 'DONE' as const, values: { complaint: [{ itemKey: null, text: 'Fever', newToList: true }] } }
          : s.key === 'diagnosis'
            ? { ...s, status: 'DONE' as const, values: { provisional: [{ itemKey: 'J06.9', text: 'URTI', newToList: false }] } }
            : s,
      );
      await boot(view);
      expect(footer()).toBe('Ready to complete. Prescription prints in English with English drug names.');
      expect(q('[data-testid=emr-footer] .status')!.classList).toContain('good');
    });

    it('follows what the doctor types and shows a tick on a finished part', async () => {
      const view = record();
      view.layout.sections = view.layout.sections.map((s) => (s.key === 'diagnosis' ? { ...s, required: false } : s));
      await boot(view);
      expect(footer()).toBe('Still needed: chief complaints.');
      const box = q<HTMLInputElement>('#f-complaints-complaint')!;
      await type(box, 'knee click');
      q<HTMLButtonElement>('#sec-complaints .addbtn')!.click();
      await settle();
      expect(footer()).toBe('Ready to complete. Prescription prints in English with English drug names.');
      expect(qa('.part')[0].textContent).toContain('✓');
      expect(qa('.part')[0].textContent).toContain('1 of 1 required');
    });

    it('opens the first missing section, marks it and does nothing else when Complete is tapped', async () => {
      await boot();
      tab('Treatment').click();
      await settle();
      act('complete').click();
      await settle();
      expect(tab('Assessment').getAttribute('aria-selected')).toBe('true');
      expect(q('#sec-complaints')!.classList).toContain('hl');
      expect(snack).toHaveBeenCalledWith('Still needed: chief complaints, diagnosis.', 'Close', expect.anything());
      http.expectNone(`${EMR}/r1/draft`);
      http.expectNone(`${EMR}/r1/finish-check`);
    });

    it('asks the server to check when everything is done, then opens "Check before completing"', async () => {
      const view = record();
      view.layout.sections = view.layout.sections.map((s) => ({ ...s, required: false }));
      await boot(view);
      act('complete').click();
      const req = http.expectOne(`${EMR}/r1/finish-check`);
      expect(req.request.body).toEqual({});
      req.flush({
        ready: true,
        blockers: [],
        encounterType: 'OPD_VISIT',
        effects: {},
        info: [],
        share: { whatsapp: { available: false, reason: 'Needs the WhatsApp service' }, abha: { available: false, reason: 'Needs the ABDM service' } },
      });
      await settle();
      expect(dialogOpen).toHaveBeenCalledWith(FinishDialogComponent, expect.objectContaining({ ariaLabelledBy: 'finish-title' }));
    });

    it('does not open the dialog when the server still lists a missing section, and opens that section', async () => {
      const view = record();
      view.layout.sections = view.layout.sections.map((s) => ({ ...s, required: false }));
      await boot(view);
      act('complete').click();
      http.expectOne(`${EMR}/r1/finish-check`).flush({
        ready: false,
        blockers: [{ key: 'STILL_NEEDED', message: 'Still needed: diagnosis.', sectionKeys: ['diagnosis'] }],
        encounterType: 'OPD_VISIT',
        effects: {},
        info: [],
        share: { whatsapp: { available: false, reason: 'x' }, abha: { available: false, reason: 'y' } },
      });
      await settle();
      expect(dialogOpen).not.toHaveBeenCalledWith(FinishDialogComponent, expect.anything());
      expect(snack).toHaveBeenCalledWith('Still needed: diagnosis.', 'Close', expect.anything());
      expect(tab('Treatment').getAttribute('aria-selected')).toBe('true');
    });
  });

  describe('Save draft (SAH-47-5)', () => {
    async function typeVital(): Promise<void> {
      await type(q<HTMLInputElement>('#f-vitals-pulse')!, '72');
    }

    it('saves what is typed and says Draft saved', async () => {
      await boot();
      await typeVital();
      act('save-draft').click();
      const req = http.expectOne(`${EMR}/r1/draft`);
      expect(req.request.body.sections).toEqual([{ key: 'vitals', values: { pulse: 72 } }]);
      req.flush(record({ revision: 4 }));
      await settle();
      expect(snack).toHaveBeenCalledWith('Draft saved', 'Close', expect.anything());
    });

    it('shows a busy state and then an error, keeping the values, when the network fails', async () => {
      await boot();
      await typeVital();
      act('save-draft').click();
      await settle();
      expect(act('save-draft').textContent).toContain('Saving');
      http.expectOne(`${EMR}/r1/draft`).error(new ProgressEvent('error'));
      await settle();
      expect(q('[data-testid=save-error]')!.textContent).toContain('Your entries are still on screen.');
      expect(q<HTMLInputElement>('#f-vitals-pulse')!.value).toBe('72');
      expect(act('save-draft').disabled).toBe(false);
    });

    it('offers a reload when the record was changed elsewhere', async () => {
      await boot();
      await typeVital();
      act('save-draft').click();
      http.expectOne(`${EMR}/r1/draft`).flush(
        { type: 'https://sahaj.in/problems/emr-record-changed', title: 'x', status: 409 },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();
      expect(q('[data-testid=save-error]')!.textContent).toContain('Reload to see the latest.');
      q<HTMLButtonElement>('[data-act=reload]')!.click();
      http.expectOne(`${EMR}/r1`).flush(record({ revision: 9 }));
    });

    it('shows the field error and sends nothing for a value out of range', async () => {
      await boot();
      await type(q<HTMLInputElement>('#f-vitals-pulse')!, '9999999');
      act('save-draft').click();
      await settle();
      http.expectNone(`${EMR}/r1/draft`);
      expect(q('#sec-vitals .err-text')!.textContent).toContain('Enter a number from');
    });
  });

  describe('leaving (SAH-47-5)', () => {
    it('leaves without a question when nothing is unsaved', async () => {
      await boot();
      expect(fixture.componentInstance.confirmLeave()).toBe(true);
    });

    it('asks to save first, and follows the choice', async () => {
      await boot();
      await type(q<HTMLInputElement>('#f-vitals-pulse')!, '72');
      const ask = () => fixture.componentInstance.confirmLeave() as Observable<boolean>;
      let result: boolean | undefined;

      dialogResult = 'stay';
      ask().subscribe((v) => (result = v));
      expect(result).toBe(false);

      dialogResult = 'leave';
      ask().subscribe((v) => (result = v));
      expect(result).toBe(true);

      dialogResult = 'save';
      result = undefined;
      ask().subscribe((v) => (result = v));
      http.expectOne(`${EMR}/r1/draft`).flush(record({ revision: 4 }));
      expect(result).toBe(true);
    });

    it('stays when the save before leaving fails', async () => {
      await boot();
      await type(q<HTMLInputElement>('#f-vitals-pulse')!, '72');
      dialogResult = 'save';
      let result: boolean | undefined;
      (fixture.componentInstance.confirmLeave() as Observable<boolean>).subscribe((v) => (result = v));
      http.expectOne(`${EMR}/r1/draft`).error(new ProgressEvent('error'));
      expect(result).toBe(false);
    });

    it('warns before the browser tab is closed with unsaved entries', async () => {
      await boot();
      const clean = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(clean);
      expect(clean.defaultPrevented).toBe(false);
      await type(q<HTMLInputElement>('#f-vitals-pulse')!, '72');
      const dirty = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(dirty);
      expect(dirty.defaultPrevented).toBe(true);
    });

    it('goes back to the queue by the type of the encounter when there is no earlier page', async () => {
      await boot();
      act('back').click();
      expect(router.navigateByUrl).toHaveBeenCalledWith('/app/queue');
    });
  });

  describe('read-only records', () => {
    it('says a signed visit opens read-only and switches every control off', async () => {
      await boot(record({ readOnly: true, readOnlyReason: 'SIGNED' }));
      expect(q('[data-testid=readonly-note]')!.textContent).toContain('This visit is signed, so it opens read-only.');
      expect(q<HTMLInputElement>('#f-vitals-pulse')!.disabled).toBe(true);
      expect(act('save-draft').disabled).toBe(true);
      expect(act('complete').disabled).toBe(true);
    });

    it('says why an IPD or Emergency encounter that cannot be read opens read-only', async () => {
      await boot(record({ readOnly: true, readOnlyReason: 'ENCOUNTER_CLOSED' }));
      expect(q('[data-testid=readonly-note]')!.textContent).toContain('This encounter is closed');
    });
  });

  describe('right-hand panel (SAH-47-6)', () => {
    it('shows Past visits first and a Case paper tab, each with its subtitle', async () => {
      await boot();
      const tabs = qa('.rtabs button');
      expect(tabs[0].textContent!.replace(/\s+/g, ' ').trim()).toBe('Past visitsOPD + IPD');
      expect(tabs[1].textContent!.replace(/\s+/g, ' ').trim()).toBe('Case paper0 of 10 filled · live');
      expect(tabs[0].getAttribute('aria-selected')).toBe('true');
    });

    it('shows the history in the first tab and the live paper in the second, and keeps the choice for this visit only', async () => {
      await boot();
      expect(q('app-past-visits')).not.toBeNull();
      expect(q('app-case-paper-panel')).toBeNull();
      qa('.rtabs button')[1].click();
      await settle();
      expect(qa('.rtabs button')[1].getAttribute('aria-selected')).toBe('true');
      expect(q('app-case-paper-panel')).not.toBeNull();
      expect(q('app-past-visits')).toBeNull();
      expect(window.localStorage.length).toBe(0);
      expect(window.sessionStorage.length).toBe(0);
    });

    it('counts the headings with content in the Case paper subtitle while the doctor types', async () => {
      await boot();
      expect(qa('.rtabs button')[1].textContent!.replace(/s+/g, ' ')).toContain('0 of 10 filled · live');
    });
  });

  describe('states', () => {
    it('shows a message and a retry when the record cannot be opened', async () => {
      snack = vi.fn();
      router = { currentNavigation: () => null, navigateByUrl: vi.fn() };
      TestBed.configureTestingModule({
        providers: [
          provideHttpClient(withInterceptors([problemDetailInterceptor])),
          provideHttpClientTesting(),
          { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: (key: string) => (key === 'recordId' ? 'r1' : null) } } } },
          { provide: Router, useValue: router },
          { provide: MatSnackBar, useValue: { open: snack } },
          { provide: MatDialog, useValue: { open: vi.fn() } },
          { provide: ScrollTo, useValue: { section: vi.fn() } },
        ],
      });
      http = TestBed.inject(HttpTestingController);
      fixture = TestBed.createComponent(EmrRecordPage);
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Opening the EMR');
      http.expectOne(`${EMR}/r1`).flush(
        { type: 'https://sahaj.in/problems/outside-scope', title: 'Forbidden', status: 403, detail: 'This patient is in a ward you do not cover.' },
        { status: 403, statusText: 'Forbidden' },
      );
      await settle();
      el = fixture.nativeElement as HTMLElement;
      expect(q('[role=alert]')!.textContent).toContain('This patient is in a ward you do not cover.');
      expect(q('[data-act=retry]')).not.toBeNull();
    });
  });
});
