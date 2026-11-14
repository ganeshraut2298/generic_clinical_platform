import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { casePaperContext, record, recordSection } from '../emr.fixtures';
import { EmrRecordView } from '../emr.model';
import { EmrRecordStore } from './emr-record.store';

const EMR = '/api/v1/opd/emr/records';
const FEVER = [{ itemKey: null, text: 'Fever', newToList: true }];
const URTI = [{ itemKey: 'J06.9', text: 'Acute upper respiratory infection', newToList: false }];

describe('EmrRecordStore (SAH-47)', () => {
  let store: EmrRecordStore;
  let http: HttpTestingController;

  function boot(view: EmrRecordView = record()): void {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        EmrRecordStore,
      ],
    });
    store = TestBed.inject(EmrRecordStore);
    http = TestBed.inject(HttpTestingController);
    store.load(view.id);
    http.expectOne(`${EMR}/${view.id}`).flush(view);
    http.expectOne(`${EMR}/${view.id}/case-paper`).flush(casePaperContext());
  }

  const problem = (slug: string, status: number, extra: object = {}) => ({
    body: { type: `https://sahaj.in/problems/${slug}`, title: 'x', status, ...extra },
    init: { status, statusText: 'x' },
  });

  afterEach(() => {
    // A reload asks for the case paper context again; answer it so nothing stays open.
    http.match((req) => req.url.endsWith('/case-paper')).forEach((req) => req.flush(casePaperContext()));
    http.verify();
  });

  describe('loading', () => {
    it('shows the position the record was saved at', () => {
      boot(record({ uiState: { part: 'TREATMENT', sectionKey: 'diagnosis' } }));
      expect(store.loading()).toBe(false);
      expect(store.part()).toBe('TREATMENT');
      expect(store.activeSection()).toBe('diagnosis');
    });

    it('opens a section collapsed only when the snapshot says so', () => {
      const view = record();
      view.layout.sections = view.layout.sections.map((s) => (s.key === 'rx' ? { ...s, collapsed: true } : s));
      boot(view);
      expect(store.isCollapsed('rx')).toBe(true);
      expect(store.isCollapsed('vitals')).toBe(false);
      store.toggleCollapsed('rx');
      expect(store.isCollapsed('rx')).toBe(false);
    });

    it('keeps the server answer for Done and Still needed while nothing is typed', () => {
      boot(record({ stillNeeded: ['complaints', 'diagnosis'] }));
      expect(store.needed()).toEqual(['complaints', 'diagnosis']);
      expect(store.footer()).toBe('Still needed: chief complaints, diagnosis.');
      expect(store.ready()).toBe(false);
    });

    it('says why when the record cannot be opened', () => {
      TestBed.configureTestingModule({
        providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting(), EmrRecordStore],
      });
      store = TestBed.inject(EmrRecordStore);
      http = TestBed.inject(HttpTestingController);
      store.load('r1');
      const p = problem('not-own-patient', 403, { detail: 'This patient belongs to another doctor.' });
      http.expectOne(`${EMR}/r1`).flush(p.body, p.init);
      expect(store.loadError()).toBe('This patient belongs to another doctor.');
      expect(store.record()).toBeNull();
    });
  });

  describe('typing', () => {
    it('updates Done, the part counts and Still needed at once', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      expect(store.dirty()).toBe(true);
      expect(store.needed()).toEqual(['diagnosis']);
      expect(store.footer()).toBe('Still needed: diagnosis.');
      expect(store.progress()[0]).toMatchObject({ requiredDone: 1, requiredTotal: 1, complete: true });
      store.setValue('diagnosis', 'provisional', URTI);
      expect(store.ready()).toBe(true);
      expect(store.footer()).toBe('Ready to complete. Prescription prints in English with English drug names.');
      expect(store.stateOf('diagnosis')?.status).toBe('DONE');
    });

    it('goes back to Still needed when a value is cleared', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      store.setValue('complaints', 'complaint', []);
      expect(store.needed()).toContain('complaints');
    });

    it('shows a field problem and does not save while it stands', () => {
      boot();
      store.setValue('vitals', 'bp_systolic', 5_000_000);
      expect(store.problems()['vitals']['bp_systolic']).toContain('Enter a number from');
      let result: boolean | undefined;
      store.save().subscribe((ok) => (result = ok));
      expect(result).toBe(false);
      http.expectNone(`${EMR}/r1/draft`);
      expect(store.saveError()).toBe('Fix the fields marked in red, then save again.');
      expect(store.values()['vitals']['bp_systolic']).toBe(5_000_000);
    });

    it('does not take values on a read-only record', () => {
      boot(record({ readOnly: true, readOnlyReason: 'SIGNED' }));
      store.setValue('complaints', 'complaint', FEVER);
      expect(store.dirty()).toBe(false);
      expect(store.values()['complaints']).toEqual({});
    });
  });

  describe('Save draft', () => {
    it('sends the position and only the changed sections with their non-empty values', () => {
      boot();
      store.setPart('TREATMENT');
      store.goToSection('diagnosis');
      store.setValue('complaints', 'complaint', FEVER);
      store.setValue('complaints', 'severity', '');
      let ok: boolean | undefined;
      store.save().subscribe((v) => (ok = v));
      const req = http.expectOne(`${EMR}/r1/draft`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({
        revision: 3,
        uiState: { part: 'TREATMENT', sectionKey: 'diagnosis' },
        sections: [{ key: 'complaints', values: { complaint: FEVER } }],
      });
      const saved = record({ revision: 4 });
      req.flush(saved);
      expect(ok).toBe(true);
      expect(store.record()!.revision).toBe(4);
      expect(store.dirty()).toBe(false);
      expect(store.saving()).toBe(false);
    });

    it('saves a lone position change with no sections', () => {
      boot();
      store.setPart('TREATMENT');
      store.save().subscribe();
      const req = http.expectOne(`${EMR}/r1/draft`);
      expect(req.request.body.sections).toEqual([]);
      req.flush(record({ revision: 4 }));
    });

    it('makes one request while a save is running', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      store.save().subscribe();
      store.save().subscribe();
      const reqs = http.match(`${EMR}/r1/draft`);
      expect(reqs).toHaveLength(1);
      reqs[0].flush(record({ revision: 4 }));
    });

    it('keeps what was typed while the save runs as unsaved', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      store.save().subscribe();
      store.setValue('complaints', 'severity', 'mild');
      http.expectOne(`${EMR}/r1/draft`).flush(record({ revision: 4 }));
      expect(store.dirty()).toBe(true);
      expect(store.values()['complaints']['severity']).toBe('mild');
    });

    it('keeps the values and says so when the network fails', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      let ok: boolean | undefined;
      store.save().subscribe((v) => (ok = v));
      http.expectOne(`${EMR}/r1/draft`).error(new ProgressEvent('error'));
      expect(ok).toBe(false);
      expect(store.saveError()).toBe('We could not save. Your entries are still on screen.');
      expect(store.dirty()).toBe(true);
      expect(store.saving()).toBe(false);
    });

    it('stops and offers a reload when the record was changed elsewhere (409)', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      store.save().subscribe();
      const p = problem('emr-record-changed', 409);
      http.expectOne(`${EMR}/r1/draft`).flush(p.body, p.init);
      expect(store.conflict()).toBe(true);
      expect(store.saveError()).toBe('This EMR was changed elsewhere. Reload to see the latest.');
      let ok: boolean | undefined;
      store.save().subscribe((v) => (ok = v));
      expect(ok).toBe(false);
      http.expectNone(`${EMR}/r1/draft`);
      store.reload();
      http.expectOne(`${EMR}/r1`).flush(record({ revision: 9 }));
      expect(store.conflict()).toBe(false);
      expect(store.record()!.revision).toBe(9);
      expect(store.dirty()).toBe(false);
    });

    it('saves an IPD or Emergency record like any other (drafts are on, OQ-43-27)', () => {
      boot(record({ encounterType: 'IPD_ROUND' }));
      store.setValue('complaints', 'complaint', FEVER);
      store.save().subscribe();
      http.expectOne(`${EMR}/r1/draft`).flush(record({ encounterType: 'IPD_ROUND', revision: 4 }));
      expect(store.saveError()).toBeNull();
      expect(store.record()!.revision).toBe(4);
    });

    it('marks the field the server names in a 400', () => {
      boot();
      store.setValue('vitals', 'pulse', 70);
      store.save().subscribe();
      const p = problem('validation', 400, { fieldErrors: [{ field: 'sections[0].values.pulse', message: 'Too low for this field.' }] });
      http.expectOne(`${EMR}/r1/draft`).flush(p.body, p.init);
      expect(store.problems()['vitals']['pulse']).toBe('Too low for this field.');
      store.setValue('vitals', 'pulse', 71);
      expect(store.problems()['vitals']).toBeUndefined();
    });
  });

  describe('copy from the last visit (OQ-45-26)', () => {
    function withCopy(): EmrRecordView {
      const view = record();
      view.sections = view.sections.map((s) =>
        s.key === 'vitals'
          ? recordSection('vitals', {
              values: { bp_systolic: 120, bp_diastolic: 80 },
              copiedFrom: { recordId: 'r0', date: '2026-10-24' },
              copyPendingConfirmation: true,
            })
          : s,
      );
      return view;
    }

    it('is not Done until the doctor keeps the values', () => {
      boot(withCopy());
      store.setValue('complaints', 'complaint', FEVER);
      expect(store.copyPending('vitals')).toBe(true);
      expect(store.stateOf('vitals')?.done).toBe(false);
      store.confirmCopy('vitals');
      expect(store.copyPending('vitals')).toBe(false);
      expect(store.stateOf('vitals')?.done).toBe(true);
    });

    it('counts an edit as confirming, and sends confirmCopy once saved', () => {
      boot(withCopy());
      store.setValue('vitals', 'pulse', 72);
      expect(store.copyPending('vitals')).toBe(false);
      store.save().subscribe();
      const req = http.expectOne(`${EMR}/r1/draft`);
      expect(req.request.body.sections[0]).toEqual({
        key: 'vitals',
        values: { bp_systolic: 120, bp_diastolic: 80, pulse: 72 },
        confirmCopy: true,
      });
      req.flush(record({ revision: 4 }));
    });
  });

  describe('moving inside the screen', () => {
    it('opens the part and the section, expanded, and marks it', () => {
      boot();
      store.toggleCollapsed('diagnosis');
      expect(store.isCollapsed('diagnosis')).toBe(true);
      store.goToSection('diagnosis');
      expect(store.part()).toBe('TREATMENT');
      expect(store.activeSection()).toBe('diagnosis');
      expect(store.isCollapsed('diagnosis')).toBe(false);
      expect(store.highlight()).toBe('diagnosis');
      store.clearHighlight();
      expect(store.highlight()).toBeNull();
    });

    it('finds the first missing section and never blocks a move', () => {
      boot();
      expect(store.firstMissing()).toBe('complaints');
      store.setPart('TREATMENT');
      expect(store.part()).toBe('TREATMENT');
    });
  });

  describe('header actions', () => {
    it('changes the OPD visit type on the visit and shows it', () => {
      boot();
      store.changeVisitType('REVISIT');
      const req = http.expectOne('/api/v1/opd/visits/v1/visit-type');
      expect(req.request.method).toBe('PATCH');
      expect(req.request.body).toEqual({ visitType: 'REVISIT' });
      req.flush({});
      expect(store.record()!.header.visitType).toBe('REVISIT');
    });

    it('does not change the visit type of an IPD record', () => {
      boot(record({ encounterType: 'IPD_ROUND' }));
      store.changeVisitType('REVISIT');
      http.expectNone('/api/v1/opd/visits/v1/visit-type');
    });

    it('shows the server message when the visit type is refused', () => {
      boot();
      store.changeVisitType('NEW');
      const p = problem('not-own-patient', 403, { detail: 'This patient belongs to another doctor.' });
      http.expectOne('/api/v1/opd/visits/v1/visit-type').flush(p.body, p.init);
      expect(store.notice()).toBe('This patient belongs to another doctor.');
    });

    it('switches the department after saving what is typed, and keeps nothing deleted', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      store.switchDepartment('dental');
      http.expectOne(`${EMR}/r1/draft`).flush(record({ revision: 4 }));
      const req = http.expectOne(`${EMR}/r1/department`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({ departmentKey: 'dental', revision: 4 });
      const next = record({ revision: 5 });
      next.layout.departmentName = 'Dental';
      req.flush(next);
      expect(store.record()!.layout.departmentName).toBe('Dental');
      expect(store.dirty()).toBe(false);
    });

    it('does not switch when the save before it fails', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      store.switchDepartment('dental');
      http.expectOne(`${EMR}/r1/draft`).error(new ProgressEvent('error'));
      http.expectNone(`${EMR}/r1/department`);
    });

    it('marks Admission advised and sends the referral line only when given', () => {
      boot();
      let ok: boolean | undefined;
      store.adviseAdmission('Refer to a surgeon').subscribe((v) => (ok = v));
      const req = http.expectOne(`${EMR}/r1/admission-advice`);
      expect(req.request.body).toEqual({ revision: 3, referralLine: 'Refer to a surgeon' });
      req.flush(record({ revision: 4, admissionAdvised: true, referralLine: 'Refer to a surgeon' }));
      expect(ok).toBe(true);
      expect(store.record()!.admissionAdvised).toBe(true);
      expect(store.record()!.revision).toBe(4);

      store.adviseAdmission(null).subscribe();
      expect(http.expectOne(`${EMR}/r1/admission-advice`).request.body).toEqual({ revision: 4 });
    });
  });
});
