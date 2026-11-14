import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { casePaperContext } from '../emr.fixtures';
import { EmrRecordView } from '../emr.model';
import { EmrListValue } from '../emr-phase3.model';
import { Anchor, specialtyRecord } from '../specialty/specialty.fixtures';
import { SpPainScore, SpSurgicalExam } from '../specialty/specialty.model';
import { EmrRecordStore } from './emr-record.store';

const EMR = '/api/v1/opd/emr/records';

const oncoAnchors: Anchor[] = [
  { key: 'onc_tox', component: 'ONC_TOX', name: 'Side effects' },
  { key: 'onc_bsa', component: 'ONC_EXAM', name: 'Examination' },
  { key: 'onc_regimen', component: 'CHEMO_PLAN', name: 'Chemotherapy', part: 'TREATMENT' },
];

describe('EmrRecordStore, phase 4 (ADR-023)', () => {
  let store: EmrRecordStore;
  let http: HttpTestingController;

  function boot(view: EmrRecordView): void {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting(), EmrRecordStore],
    });
    store = TestBed.inject(EmrRecordStore);
    http = TestBed.inject(HttpTestingController);
    store.load(view.id);
    http.expectOne(`${EMR}/${view.id}`).flush(view);
    http.expectOne(`${EMR}/${view.id}/case-paper`).flush(casePaperContext());
  }

  afterEach(() => http.verify());

  describe('the profile', () => {
    it('comes from the server view, else from the editors in the layout, and is null for a generic record', () => {
      boot(specialtyRecord('physio', [{ key: 'pain', component: 'PAIN_SCORE' }]));
      expect(store.profile()).toBe('physio');
      TestBed.resetTestingModule();
      const generic = specialtyRecord('gm', []);
      boot({ ...generic, specialty: null, layout: { ...generic.layout, sections: generic.layout.sections.map((s) => ({ ...s, specialtyEditor: null })) } });
      expect(store.profile()).toBeNull();
    });
  });

  describe('a specialty section', () => {
    beforeEach(() => boot(specialtyRecord('physio', [{ key: 'pain', component: 'PAIN_SCORE', required: true }], { mode: 'SESSION' })));

    it('is Done by the component rule while the doctor types', () => {
      expect(store.stateOf('pain')?.done).toBe(false);
      const empty: SpPainScore = { component: 'PAIN_SCORE', schemaVersion: 1, score: null };
      store.setSpecialty('pain', empty);
      expect(store.stateOf('pain')?.done).toBe(false);
      store.setSpecialty('pain', { ...empty, score: 4 });
      expect(store.stateOf('pain')?.done).toBe(true);
      expect(store.dirty()).toBe(true);
    });

    it('is saved under values.specialty and the saved record comes back', () => {
      store.setSpecialty('pain', { component: 'PAIN_SCORE', schemaVersion: 1, score: 4, site: null });
      let ok = false;
      store.save().subscribe((v) => (ok = v));
      const req = http.expectOne(`${EMR}/r1/draft`);
      expect(req.request.method).toBe('PUT');
      const sent = (req.request.body as { sections: { key: string; values: Record<string, unknown> }[] }).sections[0];
      expect(sent.key).toBe('pain');
      expect(sent.values['specialty']).toEqual({ component: 'PAIN_SCORE', schemaVersion: 1, score: 4, site: null });
      expect((req.request.body as { screen?: unknown }).screen).toBeUndefined();
      req.flush(specialtyRecord('physio', [{ key: 'pain', component: 'PAIN_SCORE', required: true }], { mode: 'SESSION' }, { revision: 4 }));
      expect(ok).toBe(true);
      expect(store.record()?.revision).toBe(4);
    });

    it('leaves server-stamped properties out of the body', () => {
      store.setSpecialty('pain', { component: 'PAIN_SCORE', schemaVersion: 1, score: 2 });
      store.save().subscribe();
      const body = http.expectOne(`${EMR}/r1/draft`).request.body as { sections: { values: { specialty: object } }[] };
      expect(Object.keys(body.sections[0].values.specialty).sort()).toEqual(['component', 'schemaVersion', 'score']);
    });
  });

  describe('visit mode (screen)', () => {
    it('is sent with the draft save and adopted from the saved record', () => {
      boot(specialtyRecord('og', [{ key: 'anc', component: 'ANC_CHECK' }], { mode: null }));
      expect(store.visitMode()).toBe('ANC');
      store.setScreenMode('GYNAE');
      expect(store.dirty()).toBe(true);
      store.save().subscribe();
      const req = http.expectOne(`${EMR}/r1/draft`);
      expect((req.request.body as { screen: unknown }).screen).toEqual({ mode: 'GYNAE' });
      req.flush(specialtyRecord('og', [{ key: 'anc', component: 'ANC_CHECK' }], { mode: 'GYNAE' }, { revision: 4 }));
      expect(store.screen().mode).toBe('GYNAE');
      expect(store.dirty()).toBe(false);
    });

    it('hides the sections the mode does not use, and a required one then stops asking', () => {
      boot(
        specialtyRecord('og', [
          { key: 'anc', component: 'ANC_CHECK', required: true },
          { key: 'gyn_exam', component: 'GYNAE_EXAM' },
        ]),
      );
      const needed = (): string[] => store.footerItems().map((i) => i.key).filter((k) => k !== 'diagnosis');
      expect(store.sectionsOf('ASSESSMENT').map((s) => s.key)).toEqual(['anc']);
      expect(needed()).toEqual(['anc']);
      store.setScreenMode('GYNAE');
      expect(store.sectionsOf('ASSESSMENT').map((s) => s.key)).toEqual(['gyn_exam']);
      expect(needed()).toEqual([]);
    });

    it('uses the server list of hidden sections and its effective mode while the mode on screen is the saved one (contract 0.5.1)', () => {
      boot(
        specialtyRecord(
          'og',
          [
            { key: 'anc', component: 'ANC_CHECK' },
            { key: 'gyn_exam', component: 'GYNAE_EXAM' },
          ],
          { mode: 'GYNAE', hiddenSectionKeys: ['anc'] },
        ),
      );
      expect(store.visitMode()).toBe('GYNAE');
      expect([...store.hiddenKeys()]).toEqual(['anc']);
      expect(store.sectionsOf('ASSESSMENT').map((s) => s.key)).toEqual(['gyn_exam']);
    });

    it('works the hidden sections out itself when the doctor switches the mode, and keeps pcpndt while Form F is required (H21)', () => {
      const og = { formFRequired: true } as never;
      boot(
        specialtyRecord(
          'og',
          [
            { key: 'anc', component: 'ANC_CHECK' },
            { key: 'pcpndt', component: 'FORM_F' },
            { key: 'gyn_exam', component: 'GYNAE_EXAM' },
          ],
          { mode: 'ANC', og, hiddenSectionKeys: ['gyn_exam'] },
        ),
      );
      expect(store.sectionsOf('ASSESSMENT').map((s) => s.key)).toEqual(['anc', 'pcpndt']);
      store.setScreenMode('GYNAE');
      expect(store.sectionsOf('ASSESSMENT').map((s) => s.key)).toEqual(['pcpndt', 'gyn_exam']);
    });

    it('opens a physio visit in the first-visit mode until a session exists', () => {
      boot(specialtyRecord('physio', [{ key: 'pain', component: 'PAIN_SCORE' }], { physio: { sessionNo: null, sessionsPlanned: null, packageName: null, romPercents: [] } }));
      expect(store.visitMode()).toBe('FIRST_VISIT');
      TestBed.resetTestingModule();
      boot(specialtyRecord('physio', [{ key: 'pain', component: 'PAIN_SCORE' }], { physio: { sessionNo: 4, sessionsPlanned: 10, packageName: 'Knee package', romPercents: [] } }));
      expect(store.visitMode()).toBe('SESSION');
    });
  });

  describe('the footer of a specialty screen', () => {
    it('uses the server’s items while nothing is unsaved', () => {
      boot(
        specialtyRecord('surgery', [{ key: 'sx_plan', component: 'SURGERY_PLAN', required: true, name: 'Surgery plan' }], {}, { stillNeededItems: [{ key: 'sx_plan', sectionKey: 'sx_plan', label: 'OT booking', count: null }] }),
      );
      expect(store.footer()).toBe('Still needed: OT booking.');
      expect(store.ready()).toBe(false);
    });

    it('lists the oncology items whatever the Required setting, with the grade count, and asks for the decision', () => {
      boot(specialtyRecord('onco', oncoAnchors));
      const labels = (): string[] => store.footerItems().map((i) => i.label).filter((l) => l !== 'diagnosis');
      expect(labels()).toEqual(['9 side effect grade(s)', 'systemic exam', 'send chemo order', "today's decision"]);
      store.setOncDecision('GIVE_AS_PLANNED', null);
      expect(labels()).toEqual(['9 side effect grade(s)', 'systemic exam', 'send chemo order']);
      store.setOncDecision('DELAY_1_WEEK', 'CLINICAL_JUDGEMENT');
      expect(labels()).toContain('record the hold');
    });

    it('sends the decision with the draft save and says so when the server needs a reason', () => {
      boot(specialtyRecord('onco', oncoAnchors));
      store.setOncDecision('STOP', null);
      store.save().subscribe();
      const req = http.expectOne(`${EMR}/r1/draft`);
      expect((req.request.body as { screen: unknown }).screen).toEqual({ oncDecision: { choice: 'STOP', reasonKey: null } });
      req.flush({ type: 'https://sahaj.in/problems/decision-reason-required', title: 'Reason needed', status: 400, detail: 'Pick a reason (saved with the note).' }, { status: 400, statusText: 'Bad Request' });
      expect(store.saveError()).toBe('Pick a reason (saved with the note).');
      expect(store.dirty()).toBe(true);
    });

    it('counts the oncology plan as Done once the order is sent or held', () => {
      boot(specialtyRecord('onco', oncoAnchors));
      expect(store.stateOf('onc_regimen')?.done).toBe(false);
      store.orderStatusOverride.set('SENT');
      expect(store.stateOf('onc_regimen')?.done).toBe(true);
      expect(store.footerItems().map((i) => i.key)).not.toContain('onc_regimen');
      store.orderStatusOverride.set('DRAFT');
      expect(store.stateOf('onc_regimen')?.done).toBe(false);
    });
  });

  describe('rows and tests a specialty adds', () => {
    beforeEach(() => boot(specialtyRecord('paeds', [{ key: 'wtdose', component: 'WEIGHT_DOSE' }])));

    it('adds prescription rows once, skipping the same name', () => {
      const row = { id: 'x1', medicineId: null, name: 'Paracetamol syrup 120 mg / 5 ml', form: null, frequency: null, frequencyText: 'every 6 hours if needed, at most 4 doses a day', days: 3, when: 'AFTER_FOOD' as const, source: 'DOSE_BY_WEIGHT' as const };
      expect(store.addPrescriptionRows([row])).toBe(true);
      expect(store.addPrescriptionRows([{ ...row, id: 'x2' }])).toBe(true);
      expect(store.prescriptionRows()).toHaveLength(1);
      store.removePrescriptionRows(['paracetamol syrup 120 mg / 5 ml']);
      expect(store.prescriptionRows()).toHaveLength(0);
    });

    it('says so when the visit has no prescription section', () => {
      TestBed.resetTestingModule();
      const view = specialtyRecord('paeds', [{ key: 'wtdose', component: 'WEIGHT_DOSE' }]);
      boot({ ...view, layout: { ...view.layout, sections: view.layout.sections.filter((s) => s.key !== 'rx') } });
      expect(store.addPrescriptionRows([])).toBe(false);
      expect(store.notice()).toBe('This visit has no prescription section, so the medicines were not added.');
    });

    it('adds and removes an investigation, and an item list entry returns its new ids for Undo', () => {
      store.addInvestigations(['OGTT (75 g)', 'ogtt (75 g)']);
      const items = (store.values()['investigations']['tests'] as EmrListValue).items;
      expect(items.map((i) => i.text)).toEqual(['OGTT (75 g)']);
      store.removeInvestigation('OGTT (75 g)');
      expect((store.values()['investigations']['tests'] as EmrListValue).items).toEqual([]);
      const ids = store.addListEntries('DIAGNOSIS', [{ itemKey: null, text: 'Viral fever', newToList: true }]);
      expect(ids).toHaveLength(1);
      expect(store.hasDiagnosis()).toBe(true);
      store.removeListItems('DIAGNOSIS', ids);
      expect(store.hasDiagnosis()).toBe(false);
    });
  });

  describe('server actions of its own', () => {
    it('adopts the booked record without losing an entry being typed', () => {
      boot(specialtyRecord('surgery', [{ key: 'sx_findings', component: 'SURGICAL_EXAM' }, { key: 'sx_plan', component: 'SURGERY_PLAN' }]));
      const typing: SpSurgicalExam = { component: 'SURGICAL_EXAM', schemaVersion: 1, abdomen: ['SOFT'], herniaSites: [] };
      store.setSpecialty('sx_findings', typing);
      store.adopt(specialtyRecord('surgery', [{ key: 'sx_findings', component: 'SURGICAL_EXAM' }, { key: 'sx_plan', component: 'SURGERY_PLAN' }], { surgery: { bookState: 'BOOKED' } as never }, { revision: 9 }));
      expect(store.record()?.revision).toBe(9);
      expect(store.specialtyValue('sx_findings')).toEqual(typing);
    });

    it('reads the record again after the order changes', () => {
      boot(specialtyRecord('onco', oncoAnchors));
      store.orderStatusOverride.set('SENT');
      store.refreshRecord();
      http.expectOne(`${EMR}/r1`).flush(specialtyRecord('onco', oncoAnchors, {}, { revision: 8 }));
      expect(store.record()?.revision).toBe(8);
      expect(store.orderStatusOverride()).toBeNull();
    });
  });
});
