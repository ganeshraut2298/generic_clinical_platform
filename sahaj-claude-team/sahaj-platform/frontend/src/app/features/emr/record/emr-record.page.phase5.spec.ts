import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ScrollTo } from '../../../core/scroll/scroll-to.service';
import { casePaperContext, emptyPastPage, record } from '../emr.fixtures';
import { EmrFinishProfileEffects, EmrSignOutcome } from '../emr-finish.model';
import { oncoBlock, surgeryBlock } from '../finish/finish.testing';
import { EmrRecordView } from '../emr.model';
import { specialtyRecord } from '../specialty/specialty.fixtures';
import { EmrFinishFlow } from '../finish/emr-finish.flow';
import { EmrAddendumDialogComponent } from './emr-addendum-dialog.component';
import { EmrConfirmDialogComponent } from './emr-confirm-dialog.component';
import { EmrRecordPage } from './emr-record.page';
import { EmrRecordStore } from './emr-record.store';
import { EmrTriageDialogComponent } from './emr-triage-dialog.component';

const EMR = '/api/v1/opd/emr/records';
const HOLD = { holderMembershipId: 'm2', holderName: 'Rao', expiresAt: '2999-01-01T00:00:00Z', mine: false, canTakeOver: false };

const OUTCOME: EmrSignOutcome = {
  stage: { outcome: 'MOVED', to: 'BILLING' },
  followUp: null,
  billLines: 0,
  billTotalPaise: 0,
  ipdOrders: [],
  round: null,
  emergency: null,
  patientRecord: 'NOT_CHANGED',
  course: null,
  prints: ['PRESCRIPTION'],
};

describe('EmrRecordPage, phase 5 (SAH-51 finish, hold, addendum, SAH-67-4 Emergency type)', () => {
  let fixture: ComponentFixture<EmrRecordPage>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let snack: ReturnType<typeof vi.fn>;
  let dialogOpen: ReturnType<typeof vi.fn>;
  let dialogResult: unknown;
  let router: { currentNavigation: () => unknown; navigateByUrl: ReturnType<typeof vi.fn> };

  async function boot(view: EmrRecordView = record()): Promise<void> {
    snack = vi.fn();
    dialogResult = undefined;
    dialogOpen = vi.fn(() => ({ afterClosed: () => of(dialogResult) }));
    router = { currentNavigation: () => ({ previousNavigation: null, extras: { state: null } }), navigateByUrl: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: (key: string) => (key === 'recordId' ? 'r1' : null) } } } },
        { provide: Router, useValue: router },
        { provide: MatSnackBar, useValue: { open: snack } },
        { provide: MatDialog, useValue: { open: dialogOpen } },
        { provide: ScrollTo, useValue: { section: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EmrRecordPage);
    fixture.detectChanges();
    http.expectOne(`${EMR}/r1`).flush(view);
    await settle();
    el = fixture.nativeElement as HTMLElement;
  }

  function flushQuick(): void {
    http.match((req) => req.url.includes('/emr/quick-options/')).forEach((req) => req.flush({ master: 'complaints', items: [] }));
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
  const act = (name: string) => q<HTMLButtonElement>(`[data-act=${name}]`);
  const tab = (name: string) => qa('[role=tab]').find((t) => t.textContent!.includes(name))!;

  afterEach(() => {
    flushQuick();
    http.match(`${EMR}/r1/edit-hold`).forEach((r) => r.flush(null, { status: 204, statusText: 'No Content' }));
    http.verify();
  });

  describe('one editor at a time (SAH-51-4)', () => {
    it('shows "Open for editing by Dr. <name>" and no Save draft, Complete or change controls', async () => {
      await boot(record({ readOnly: true, readOnlyReason: 'HELD_BY_OTHER', editHold: HOLD }));
      expect(q('[data-testid=hold-banner]')!.textContent).toContain('Open for editing by Dr. Rao');
      expect(act('save-draft')).toBeNull();
      expect(act('complete')).toBeNull();
      expect(act('take-over')).toBeNull();
      expect(act('edit-visit')).toBeNull();
      expect(q('[data-testid=save-status]')).toBeNull();
    });

    it('offers "Edit this visit" once the hold has expired', async () => {
      await boot(record({ readOnly: true, readOnlyReason: 'HELD_BY_OTHER', editHold: { ...HOLD, expiresAt: '2020-01-01T00:00:00Z' } }));
      act('edit-visit')!.click();
      const req = http.expectOne(`${EMR}/r1/edit-hold`);
      expect(req.request.body).toEqual({});
      req.flush({ ...HOLD, mine: true });
      http.expectOne(`${EMR}/r1`).flush(record());
      await settle();
      expect(q('[data-testid=hold-banner]')).toBeNull();
      expect(act('complete')).not.toBeNull();
    });

    it('the owner can "Take over" after "Dr. <name> is editing. Take over?"', async () => {
      await boot(record({ readOnly: true, readOnlyReason: 'HELD_BY_OTHER', editHold: { ...HOLD, canTakeOver: true } }));
      dialogResult = true;
      act('take-over')!.click();
      expect(dialogOpen).toHaveBeenCalledWith(
        EmrConfirmDialogComponent,
        expect.objectContaining({ data: expect.objectContaining({ message: 'Dr. Rao is editing. Take over?' }) }),
      );
      const req = http.expectOne(`${EMR}/r1/edit-hold`);
      expect(req.request.body).toEqual({ takeOver: true });
      req.flush({ ...HOLD, mine: true });
      http.expectOne(`${EMR}/r1`).flush(record());
    });

    it('does not take over when the owner says no', async () => {
      await boot(record({ readOnly: true, readOnlyReason: 'HELD_BY_OTHER', editHold: { ...HOLD, canTakeOver: true } }));
      dialogResult = false;
      act('take-over')!.click();
      http.expectNone(`${EMR}/r1/edit-hold`);
    });

    it('turns read-only with the banner when a save is refused because another doctor took the record', async () => {
      await boot();
      const store = fixture.debugElement.injector.get(EmrRecordStore);
      store.lostEditing.set({ reason: 'HELD', holderName: 'Rao' });
      await settle();
      expect(q('[data-testid=hold-banner]')!.textContent).toContain('Open for editing by Dr. Rao');
      expect(act('complete')).toBeNull();
    });
  });

  describe('save status (SAH-51-3)', () => {
    it('is a polite live region, shows Saved with the time after Save draft, and keeps nothing in storage', async () => {
      await boot();
      expect(q('[data-testid=save-status]')!.getAttribute('aria-live')).toBe('polite');
      expect(q('[data-testid=save-status]')!.textContent!.trim()).toBe('');
      act('save-draft')!.click();
      http.expectOne(`${EMR}/r1/draft`).flush(record({ revision: 4 }));
      await settle();
      expect(q('[data-testid=save-status]')!.textContent).toMatch(/^Saved \d{1,2}:\d{2} (AM|PM)$/);
      expect(snack).toHaveBeenCalledWith('Draft saved', 'Close', expect.anything());
      expect(localStorage.length).toBe(0);
      expect(sessionStorage.length).toBe(0);
    });

    it('says Offline when the connection is lost', async () => {
      await boot();
      const store = fixture.debugElement.injector.get(EmrRecordStore);
      store.setValue('complaints', 'complaint', [{ itemKey: null, text: 'Fever', newToList: true }]);
      store.autosaveTick();
      http.expectOne(`${EMR}/r1/draft`).error(new ProgressEvent('error'));
      await settle();
      expect(q('[data-testid=save-status]')!.textContent!.trim()).toBe('Offline. Your changes are not saved yet.');
    });

    it('saves at once when the doctor switches part and something changed (AUTO, no audit)', async () => {
      await boot();
      const store = fixture.debugElement.injector.get(EmrRecordStore);
      store.setValue('complaints', 'complaint', [{ itemKey: null, text: 'Fever', newToList: true }]);
      tab('Treatment').click();
      const req = http.expectOne(`${EMR}/r1/draft`);
      expect(req.request.body.saveKind).toBe('AUTO');
      req.flush(record({ revision: 4, uiState: { part: 'TREATMENT', sectionKey: null } }));
    });

    it('warns before the page is closed with unsaved changes, and not otherwise', async () => {
      await boot();
      const clean = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(clean);
      expect(clean.defaultPrevented).toBe(false);
      fixture.debugElement.injector.get(EmrRecordStore).setValue('complaints', 'complaint', [{ itemKey: null, text: 'Fever', newToList: true }]);
      const dirty = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(dirty);
      expect(dirty.defaultPrevented).toBe(true);
    });
  });

  describe('signed record and addendum (SAH-51-5)', () => {
    const signed = (over: Partial<EmrRecordView> = {}) =>
      record({
        status: 'SIGNED',
        readOnly: true,
        readOnlyReason: 'SIGNED',
        signedAt: '2026-11-13T05:35:00Z',
        signedByName: 'Test',
        canAddAddendum: true,
        addenda: [{ id: 'a1', reason: 'Wrong dose', text: 'Dose was 5 mg', createdAt: '2026-11-13T06:00:00Z', createdByName: 'Test' }],
        ...over,
      });

    it('does not show Complete or Save draft, and shows who signed and when', async () => {
      await boot(signed());
      expect(act('complete')).toBeNull();
      expect(act('save-draft')).toBeNull();
      expect(q('[data-testid=signed-line]')!.textContent).toBe('Signed by Dr. Test · 13 Nov 2026, 11:05 AM IST');
    });

    it('lists the addenda under the signature, newest last', async () => {
      await boot(signed());
      expect(q('[data-testid=addendum]')!.textContent!.replace(/\s+/g, ' ').trim()).toBe(
        'Addendum · 13 Nov 2026, 11:30 AM · Dr. Test: Wrong dose. Dose was 5 mg',
      );
    });

    it('shows "Add addendum" only to the doctor or the owner who may', async () => {
      await boot(signed({ canAddAddendum: false }));
      expect(act('add-addendum')).toBeNull();
    });

    it('opens the form and adds the saved addendum to the list', async () => {
      await boot(signed({ addenda: [] }));
      dialogResult = { id: 'a2', reason: 'Late result', text: 'HbA1c 7.2', createdAt: '2026-11-13T06:30:00Z', createdByName: 'Test' };
      act('add-addendum')!.click();
      expect(dialogOpen).toHaveBeenCalledWith(EmrAddendumDialogComponent, expect.objectContaining({ ariaLabelledBy: 'addendum-title' }));
      http.match(`${EMR}/r1/case-paper`).forEach((r) => r.flush(casePaperContext()));
      await settle();
      expect(qa('[data-testid=addendum]')).toHaveLength(1);
      expect(snack).toHaveBeenCalledWith('Addendum saved', 'Close', expect.anything());
    });

    it('does not take the hold, autosave or warn on a signed record', async () => {
      await boot(signed());
      fixture.debugElement.injector.get(EmrRecordStore).autosaveTick();
      http.expectNone(`${EMR}/r1/edit-hold`);
      http.expectNone(`${EMR}/r1/draft`);
    });
  });

  describe('after a sign (SAH-51-2, SAH-67-1)', () => {
    const emit = (record: EmrRecordView, outcome: EmrSignOutcome = OUTCOME, profile: EmrFinishProfileEffects | null = null) =>
      fixture.debugElement.injector.get(EmrFinishFlow).events.next({ kind: 'signed', outcome, choices: {}, record, sessionNo: null, profile });

    it('IPD round: says "Round done" and goes back to IPD without printing', async () => {
      await boot(record({ encounterType: 'IPD_ROUND' }));
      emit(record({ encounterType: 'IPD_ROUND', status: 'SIGNED', readOnly: true }), { ...OUTCOME, prints: [] });
      expect(snack).toHaveBeenCalledWith('Round done', 'Close', expect.anything());
      expect(router.navigateByUrl).toHaveBeenCalledWith('/app/ipd');
    });

    it('OPD: says "Visit completed · calling next patient", prints the signed prescription, then goes back to the Queue', async () => {
      await boot();
      const print = vi.spyOn(window, 'print').mockImplementation(() => undefined);
      emit(record({ status: 'SIGNED', readOnly: true }));
      expect(snack).toHaveBeenCalledWith('Visit completed · calling next patient', 'Close', expect.anything());
      expect(router.navigateByUrl).not.toHaveBeenCalled();
      const store = fixture.debugElement.injector.get(EmrRecordStore);
      expect(store.rightTab()).toBe('paper');
      store.paperContext.set(casePaperContext({ signature: { signed: true, signerName: 'Dr Test', signedAt: '2026-11-13T05:35:00Z' } }));
      await settle();
      await fixture.whenStable();
      expect(print).toHaveBeenCalled();
      expect(router.navigateByUrl).toHaveBeenCalledWith('/app/queue');
      print.mockRestore();
    });

    it('Oncology: prints the prescription, the cycle record and the chemo calendar in the server order, then goes back', async () => {
      await boot();
      const printed: string[] = [];
      const print = vi.spyOn(window, 'print').mockImplementation(() => {
        printed.push(document.querySelector('.print-root')?.textContent ?? '');
      });
      emit(
        record({ status: 'SIGNED', readOnly: true }),
        { ...OUTCOME, prints: ['PRESCRIPTION', 'CYCLE_RECORD', 'CHEMO_CALENDAR'] },
        oncoBlock({ cycleNo: 3, decision: 'GIVE_AS_PLANNED', nextCycleDate: '2026-12-03', nextCycleNo: 4, nextCycleLabel: 'NEXT_CHEMO' }),
      );
      expect(snack).toHaveBeenCalledWith('Visit completed · cycle note saved, prescriptions printed in English', 'Close', expect.anything());
      http.expectOne(`${EMR}/r1/chemo-order`).flush({ title: 'Not found' }, { status: 404, statusText: 'Not Found' });
      const store = fixture.debugElement.injector.get(EmrRecordStore);
      store.paperContext.set(casePaperContext({ signature: { signed: true, signerName: 'Dr Test', signedAt: '2026-11-13T05:35:00Z' } }));
      await settle();
      await fixture.whenStable();
      expect(print).toHaveBeenCalledTimes(3);
      expect(printed[1]).toContain('Cycle record');
      expect(printed[1]).toContain('Cycle 3');
      expect(printed[1]).toContain('Decision: Give as planned');
      expect(printed[2]).toContain('Chemotherapy calendar');
      expect(printed[2]).toContain('Next chemo Thu 3 Dec 2026 · cycle 4');
      expect(printed[2]).toContain('Call if 38 °C or above.');
      expect(router.navigateByUrl).toHaveBeenCalledWith('/app/queue');
      print.mockRestore();
    });

    it('Surgery with a booking: prints the prescription, the admission letter and the pre-op instructions', async () => {
      await boot();
      const printed: string[] = [];
      const print = vi.spyOn(window, 'print').mockImplementation(() => {
        printed.push(document.querySelector('.print-root')?.textContent ?? '');
      });
      emit(
        record({ status: 'SIGNED', readOnly: true }),
        { ...OUTCOME, prints: ['PRESCRIPTION', 'ADMISSION_LETTER', 'PREOP_SHEET'] },
        surgeryBlock({ bookState: 'BOOKED', procedureText: 'Lap cholecystectomy', otDate: '2026-11-20', slotText: 'OT 2 · 10:00', admission: 'IPD_1_2_DAYS', admissionRequested: true }),
      );
      expect(snack).toHaveBeenCalledWith('Visit completed · Go to ward and OT with the booking', 'Close', expect.anything());
      const store = fixture.debugElement.injector.get(EmrRecordStore);
      store.paperContext.set(casePaperContext({ signature: { signed: true, signerName: 'Dr Test', signedAt: '2026-11-13T05:35:00Z' } }));
      await settle();
      await fixture.whenStable();
      expect(print).toHaveBeenCalledTimes(3);
      expect(printed[1]).toContain('Admission letter');
      expect(printed[1]).toContain('Lap cholecystectomy');
      expect(printed[1]).toContain('20 Nov 2026, OT 2 · 10:00');
      expect(printed[2]).toContain('Pre-op instructions');
      expect(printed[2]).toContain('Medicine orders are placed by the ward');
      print.mockRestore();
    });

    it('offers a document that could not print again before leaving', async () => {
      await boot();
      let call = 0;
      const print = vi.spyOn(window, 'print').mockImplementation(() => {
        call += 1;
        if (call === 2) {
          throw new Error('blocked');
        }
      });
      const afterDismissed = of({ dismissedByAction: false });
      snack.mockReturnValue({ afterDismissed: () => afterDismissed });
      emit(record({ status: 'SIGNED', readOnly: true }), { ...OUTCOME, prints: ['PRESCRIPTION', 'PREOP_SHEET'] }, surgeryBlock({ bookState: 'BOOKED' }));
      const store = fixture.debugElement.injector.get(EmrRecordStore);
      store.paperContext.set(casePaperContext({ signature: { signed: true, signerName: 'Dr Test', signedAt: '2026-11-13T05:35:00Z' } }));
      await settle();
      await fixture.whenStable();
      expect(snack).toHaveBeenCalledWith('We could not print the pre-op instructions.', 'Try again', expect.anything());
      print.mockRestore();
    });

    it('Emergency admit: no print, back to the Emergency page', async () => {
      await boot(record({ encounterType: 'ER_ASSESSMENT' }));
      emit(record({ encounterType: 'ER_ASSESSMENT', status: 'SIGNED', readOnly: true }), {
        ...OUTCOME,
        prints: [],
        emergency: { outcome: 'ADMIT', admissionRequestId: 'q1', opdVisitId: null, tokenNo: null },
      });
      expect(snack).toHaveBeenCalledWith('Assessment signed · admission requested', 'Close', expect.anything());
      expect(router.navigateByUrl).toHaveBeenCalledWith('/app/emergency');
    });

    it('reads the record again when someone else signed it first', async () => {
      await boot();
      fixture.debugElement.injector.get(EmrFinishFlow).events.next({ kind: 'reload' });
      http.expectOne(`${EMR}/r1`).flush(record({ status: 'SIGNED', readOnly: true, readOnlyReason: 'SIGNED' }));
      await settle();
      expect(q('[data-testid=signed-line]')).not.toBeNull();
    });
  });

  describe('Emergency visit type (SAH-67-4)', () => {
    it('asks for a triage level, then sends it', async () => {
      await boot();
      dialogResult = 1;
      q<HTMLButtonElement>('.seg [data-type=EMERGENCY]')!.click();
      expect(dialogOpen).toHaveBeenCalledWith(EmrTriageDialogComponent, expect.objectContaining({ ariaLabelledBy: 'triage-title' }));
      const req = http.expectOne('/api/v1/opd/visits/v1/visit-type');
      expect(req.request.body).toEqual({ visitType: 'EMERGENCY', triageLevel: 1 });
      req.flush({ visitType: 'EMERGENCY', triageLevel: 1, triageLevelName: 'Red · Immediate', triageTone: 'crit' });
      await settle();
      expect(q('[data-type=EMERGENCY]')!.textContent!.trim()).toBe('Emergency · Red · Immediate');
      expect(q('[data-testid=emergency-tag]')!.textContent).toContain('Emergency · Red · Immediate');
    });

    it('sends nothing when the triage question is cancelled', async () => {
      await boot();
      dialogResult = null;
      q<HTMLButtonElement>('.seg [data-type=EMERGENCY]')!.click();
      http.expectNone('/api/v1/opd/visits/v1/visit-type');
    });

    it('says "Already billed. Ask billing to adjust." when the bill has a payment', async () => {
      await boot();
      q<HTMLButtonElement>('.seg [data-type=REVISIT]')!.click();
      http.expectOne('/api/v1/opd/visits/v1/visit-type').flush({ visitType: 'REVISIT', feeChange: { outcome: 'ALREADY_BILLED', beforePaise: 30000, afterPaise: 30000 } });
      await settle();
      expect(q('[data-testid=info-note]')!.textContent).toContain('Already billed. Ask billing to adjust.');
    });
  });

  describe('Paediatrics layout (SAH-75-11 P4)', () => {
    it('puts Growth today and Milestones side by side when both are in the part', async () => {
      await boot(
        specialtyRecord('paeds', [
          { key: 'growth', component: 'GROWTH' as const, required: true },
          { key: 'milestones', component: 'MILESTONES' as const },
        ]),
      );
      http.match((req) => req.url.includes('/specialty-reference/')).forEach((req) => req.flush({ profile: 'paeds', tagNote: '' }));
      expect(qa('app-section-card.half').map((c) => c.getAttribute('data-section') ?? c.querySelector('section')!.getAttribute('data-section'))).toEqual(['growth', 'milestones']);
    });
  });
});
