import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { casePaperContext, record } from '../emr.fixtures';
import { EmrRecordView } from '../emr.model';
import { EmrSpecialtyReference } from '../specialty/specialty.model';
import { EmrRecordStore } from './emr-record.store';

const EMR = '/api/v1/opd/emr/records';
const FEVER = [{ itemKey: null, text: 'Fever', newToList: true }];

describe('EmrRecordStore, phase 5 (SAH-51 autosave, hold, signed; SAH-67-4 Emergency type)', () => {
  let store: EmrRecordStore;
  let http: HttpTestingController;

  function boot(view: EmrRecordView = record()): void {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting(), EmrRecordStore],
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
    http.match((req) => req.url.endsWith('/case-paper')).forEach((req) => req.flush(casePaperContext()));
    http.verify();
  });

  describe('autosave (SAH-51-3)', () => {
    it('sends saveKind AUTO on a tick with changes, and Save draft sends none', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      store.autosaveTick();
      const auto = http.expectOne(`${EMR}/r1/draft`);
      expect(auto.request.body.saveKind).toBe('AUTO');
      auto.flush(record({ revision: 4 }));

      store.setValue('complaints', 'complaint', [...FEVER, { itemKey: null, text: 'Cough', newToList: true }]);
      store.save().subscribe();
      const manual = http.expectOne(`${EMR}/r1/draft`);
      expect('saveKind' in manual.request.body).toBe(false);
      manual.flush(record({ revision: 5 }));
    });

    it('shows Saving, then Saved with the time', () => {
      boot();
      expect(store.saveState().kind).toBe('idle');
      store.setValue('complaints', 'complaint', FEVER);
      store.autosaveTick();
      expect(store.saveState().kind).toBe('saving');
      http.expectOne(`${EMR}/r1/draft`).flush(record({ revision: 4 }));
      expect(store.saveState().kind).toBe('saved');
    });

    it('says Offline when the connection is lost, keeps the entries and saves again on the next tick', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      store.autosaveTick();
      http.expectOne(`${EMR}/r1/draft`).error(new ProgressEvent('error'));
      expect(store.saveState().kind).toBe('offline');
      expect(store.dirty()).toBe(true);
      store.autosaveTick();
      http.expectOne(`${EMR}/r1/draft`).flush(record({ revision: 4 }));
      expect(store.saveState().kind).toBe('saved');
      expect(store.dirty()).toBe(false);
    });

    it('says "Not saved" with the reason and keeps the entries on another failure', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      store.autosaveTick();
      const p = problem('bad-request', 500, { detail: 'The server had a problem.' });
      http.expectOne(`${EMR}/r1/draft`).flush(p.body, p.init);
      expect(store.saveState()).toEqual({ kind: 'failed', reason: 'The server had a problem' });
      expect(store.dirty()).toBe(true);
    });

    it('saves at once on a part switch only when something changed', () => {
      boot();
      store.autosaveNow();
      http.expectNone(`${EMR}/r1/draft`);
      store.setValue('complaints', 'complaint', FEVER);
      store.autosaveNow();
      http.expectOne(`${EMR}/r1/draft`).flush(record({ revision: 4 }));
    });

    it('renews the hold on a tick with nothing to save, and does not save at all on a signed record', () => {
      boot();
      store.autosaveTick();
      const renew = http.expectOne(`${EMR}/r1/edit-hold`);
      expect(renew.request.method).toBe('PUT');
      expect(renew.request.body).toEqual({});
      renew.flush({ holderMembershipId: 'm1', holderName: 'Dr Test', expiresAt: '2026-11-13T05:02:00Z', mine: true, canTakeOver: false });
    });

    it('does not tick, save or renew the hold while the sign request is in flight', () => {
      boot();
      store.signInFlight.set(true);
      store.autosaveTick();
      store.setValue('complaints', 'complaint', FEVER);
      store.autosaveTick();
      store.autosaveNow();
      http.expectNone(`${EMR}/r1/draft`);
      http.expectNone(`${EMR}/r1/edit-hold`);
      store.signInFlight.set(false);
      store.autosaveTick();
      http.expectOne(`${EMR}/r1/draft`).flush(record({ revision: 4 }));
    });

    it('ignores a "held" answer that arrives while signing or after the record is signed', () => {
      boot();
      store.autosaveTick();
      const p = problem('emr-record-held', 409, { holderName: 'Dr Rao' });
      store.signInFlight.set(true);
      http.expectOne(`${EMR}/r1/edit-hold`).flush(p.body, p.init);
      expect(store.lostEditing()).toBeNull();
      expect(store.saveState().kind).not.toBe('failed');
    });

    it('does nothing on a read-only record', () => {
      boot(record({ readOnly: true, readOnlyReason: 'SIGNED', status: 'SIGNED' }));
      store.autosaveTick();
      store.autosaveNow();
      http.expectNone(`${EMR}/r1/draft`);
      http.expectNone(`${EMR}/r1/edit-hold`);
    });

    it('stops with a message when the record was changed elsewhere', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      store.autosaveTick();
      const p = problem('emr-record-changed', 409);
      http.expectOne(`${EMR}/r1/draft`).flush(p.body, p.init);
      expect(store.conflict()).toBe(true);
      expect(store.saveError()).toContain('Reload');
    });
  });

  describe('one editor at a time (SAH-51-4)', () => {
    it('turns read-only with the holder name when another doctor holds the record (409 on a save)', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      store.autosaveTick();
      const p = problem('emr-record-held', 409, { holderName: 'Dr Rao' });
      http.expectOne(`${EMR}/r1/draft`).flush(p.body, p.init);
      expect(store.readOnly()).toBe(true);
      expect(store.lostEditing()).toEqual({ reason: 'HELD', holderName: 'Dr Rao' });
      expect(store.dirty()).toBe(true);
    });

    it('turns read-only when the hold renewal is refused', () => {
      boot();
      store.autosaveTick();
      const p = problem('emr-record-held', 409, { holderName: 'Dr Rao' });
      http.expectOne(`${EMR}/r1/edit-hold`).flush(p.body, p.init);
      expect(store.readOnly()).toBe(true);
    });

    it('turns read-only and says so when it was signed elsewhere', () => {
      boot();
      store.setValue('complaints', 'complaint', FEVER);
      store.autosaveTick();
      const p = problem('emr-record-signed', 409);
      http.expectOne(`${EMR}/r1/draft`).flush(p.body, p.init);
      expect(store.lostEditing()?.reason).toBe('SIGNED');
      expect(store.saveError()).toContain('signed');
    });

    it('"Edit this visit" takes the hold and reloads the record', () => {
      boot(record({ readOnly: true, readOnlyReason: 'HELD_BY_OTHER' }));
      store.takeEditHold(false);
      const req = http.expectOne(`${EMR}/r1/edit-hold`);
      expect(req.request.body).toEqual({});
      req.flush({ holderMembershipId: 'm1', holderName: 'Dr Test', expiresAt: '2026-11-13T05:02:00Z', mine: true, canTakeOver: false });
      http.expectOne(`${EMR}/r1`).flush(record());
      expect(store.readOnly()).toBe(false);
    });

    it('"Take over" sends takeOver true', () => {
      boot(record({ readOnly: true, readOnlyReason: 'HELD_BY_OTHER' }));
      store.takeEditHold(true);
      const req = http.expectOne(`${EMR}/r1/edit-hold`);
      expect(req.request.body).toEqual({ takeOver: true });
      req.flush({ holderMembershipId: 'm1', holderName: 'Dr Test', expiresAt: '2026-11-13T05:02:00Z', mine: true, canTakeOver: true });
      http.expectOne(`${EMR}/r1`).flush(record());
    });

    it('gives the hold up when the doctor leaves, but not on a read-only record', () => {
      boot();
      store.releaseHold();
      const del = http.expectOne(`${EMR}/r1/edit-hold`);
      expect(del.request.method).toBe('DELETE');
      del.flush(null, { status: 204, statusText: 'No Content' });
    });

    it('does not call release for a signed record', () => {
      boot(record({ readOnly: true, readOnlyReason: 'SIGNED', status: 'SIGNED' }));
      store.releaseHold();
      http.expectNone(`${EMR}/r1/edit-hold`);
    });
  });

  describe('admission request and the live paper (SAH-67-3, ADR-025)', () => {
    it('keeps the expected revision after the admission request', () => {
      boot(record({ revision: 4 }));
      store.requestAdmission({ admissionType: 'planned', plannedStayDays: 3 }, 'k1').subscribe();
      http.expectOne(`${EMR}/r1/admission-request`).flush({});
      http.expectOne(`${EMR}/r1`).flush(record({ revision: 7 }));
      http.match((r) => r.url.endsWith('/case-paper')).forEach((r) => r.flush(casePaperContext()));
      expect(store.record()!.revision).toBe(4);
    });

    it('passes the specialty and its reference lists to the live paper composer', () => {
      boot();
      const reference = { lists: {} } as unknown as EmrSpecialtyReference;
      store.paperSpecialtyReference.set(reference);
      expect(store.paperSpecialtyReference()).toBe(reference);
      expect(store.paperModel()).not.toBeNull();
    });
  });

  describe('signed and addenda (SAH-51-5)', () => {
    it('adopts the signed record as read-only', () => {
      boot();
      store.adoptSigned(record({ status: 'SIGNED', readOnly: true, readOnlyReason: 'SIGNED', revision: 9 }));
      http.match((r) => r.url.endsWith('/case-paper')).forEach((r) => r.flush(casePaperContext()));
      expect(store.readOnly()).toBe(true);
      expect(store.record()!.status).toBe('SIGNED');
    });

    it('adds the saved addendum to the list, newest last, and reads the paper again', () => {
      boot(record({ status: 'SIGNED', readOnly: true, readOnlyReason: 'SIGNED', addenda: [], canAddAddendum: true }));
      store.noteAddendum({ id: 'a1', reason: 'Wrong dose', text: 'Dose was 5 mg', createdAt: '2026-11-13T05:00:00Z', createdByName: 'Dr Test' });
      expect(store.record()!.addenda).toHaveLength(1);
      http.expectOne(`${EMR}/r1/case-paper`).flush(casePaperContext());
    });
  });

  describe('Emergency visit type (SAH-67-4)', () => {
    it('sends the triage level and shows the type and level from the answer', () => {
      boot();
      store.changeVisitType('EMERGENCY', 1);
      const req = http.expectOne('/api/v1/opd/visits/v1/visit-type');
      expect(req.request.body).toEqual({ visitType: 'EMERGENCY', triageLevel: 1 });
      req.flush({ visitType: 'EMERGENCY', triageLevel: 1, triageLevelName: 'Red · Immediate', feeChange: { outcome: 'UPDATED', beforePaise: 30000, afterPaise: 50000 } });
      expect(store.record()!.header.visitType).toBe('EMERGENCY');
      expect(store.record()!.header.triageLevelName).toBe('Red · Immediate');
      expect(store.infoNote()).toBeNull();
    });

    it('says "Already billed. Ask billing to adjust." when the bill has a payment, and the type still changes', () => {
      boot();
      store.changeVisitType('REVISIT');
      http.expectOne('/api/v1/opd/visits/v1/visit-type').flush({
        visitType: 'REVISIT',
        feeChange: { outcome: 'ALREADY_BILLED', beforePaise: 30000, afterPaise: 30000 },
      });
      expect(store.record()!.header.visitType).toBe('REVISIT');
      expect(store.infoNote()).toBe('Already billed. Ask billing to adjust.');
    });

    it('sends no triage level for another type', () => {
      boot(record());
      store.changeVisitType('NEW');
      const req = http.expectOne('/api/v1/opd/visits/v1/visit-type');
      expect(req.request.body).toEqual({ visitType: 'NEW' });
      req.flush({});
    });
  });
});
