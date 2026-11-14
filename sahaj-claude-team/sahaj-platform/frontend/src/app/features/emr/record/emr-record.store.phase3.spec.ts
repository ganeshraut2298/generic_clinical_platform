import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { casePaperContext, field, record, recordSection, section } from '../emr.fixtures';
import { EmrRecordView } from '../emr.model';
import { EmrBodyMapValue, EmrListValue, EmrPrescriptionValue } from '../emr-phase3.model';
import { EmrRecordStore } from './emr-record.store';

const EMR = '/api/v1/opd/emr/records';

const complaintField = field({ key: 'complaint', valueKind: 'COMPONENT', type: 'SEARCH_LIST', required: true, component: { type: 'EMR_LIST', listKind: 'COMPLAINTS' } });
const rxField = field({ key: 'drug', valueKind: 'COMPONENT', type: 'SEARCH_LIST', component: { type: 'PRESCRIPTION', listKind: null } });
const bodyField = field({ key: 'map', valueKind: 'COMPONENT', type: 'BODY_MAP', component: { type: 'BODY_MAP', listKind: null } });

function phase3Record(over: Partial<EmrRecordView> = {}): EmrRecordView {
  const sections = [
    section({ key: 'complaints', name: 'Chief complaints', required: true, print: true, fields: [complaintField] }),
    section({ key: 'allergy', name: 'Allergies', print: true, fields: [field({ key: 'drug_allergy', valueKind: 'ITEM_LIST', type: 'SEARCH_LIST' })] }),
    section({ key: 'local', name: 'Local examination', print: true, fields: [bodyField] }),
    section({ key: 'rx', name: 'Prescription (Rx)', part: 'TREATMENT', print: true, fields: [rxField] }),
  ];
  const base = record(over);
  return {
    ...base,
    layout: { ...base.layout, sections },
    sections: sections.map((s) => recordSection(s.key, { status: s.required ? 'REQUIRED' : 'OPTIONAL' })),
    stillNeeded: ['complaints'],
    ...over,
  };
}

const list = (text: string, extra: object = {}): EmrListValue => ({
  component: 'EMR_LIST',
  kind: 'COMPLAINTS',
  schemaVersion: 1,
  items: [{ id: 'i1', itemKey: null, text, newToList: true, attr1: 'D3', attr2: 'MODERATE', active: true, ...extra }],
});

const rx = (review: boolean | null): EmrPrescriptionValue => ({
  component: 'PRESCRIPTION',
  schemaVersion: 1,
  rows: [
    {
      id: 'r1',
      medicineId: 'm2',
      name: 'Co-trimoxazole 960 mg',
      form: 'Tablet',
      frequency: '1-0-1',
      days: 5,
      when: 'AFTER_FOOD',
      source: 'SEARCH',
      allergyReview: review === null ? null : { allergyClass: 'SULFA', confirmed: review },
    },
  ],
});

const SULFA = [{ itemKey: 'SULFA', text: 'Sulfa drugs', newToList: false }];

describe('EmrRecordStore, phase 3 (ADR-022)', () => {
  let store: EmrRecordStore;
  let http: HttpTestingController;

  function boot(view: EmrRecordView = phase3Record(), pastRecordId?: string): void {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting(), EmrRecordStore],
    });
    store = TestBed.inject(EmrRecordStore);
    http = TestBed.inject(HttpTestingController);
    store.load(view.id, pastRecordId);
    http.expectOne(pastRecordId ? `${EMR}/${view.id}/past-records/${pastRecordId}` : `${EMR}/${view.id}`).flush(view);
    http.expectOne(`${EMR}/${view.id}/case-paper`).flush(casePaperContext());
  }

  afterEach(() => http.verify());

  describe('loading', () => {
    it('opens an earlier signed visit through the past record endpoint, read-only', () => {
      boot(phase3Record({ readOnly: true, readOnlyReason: 'HISTORY', status: 'SIGNED' }), 'p9');
      expect(store.readOnly()).toBe(true);
      expect(store.record()?.readOnlyReason).toBe('HISTORY');
    });

    it('takes the print choices of the record, and the clinic default until a choice is made', () => {
      boot(phase3Record({ printPreferences: { language: null, paperSize: null } }));
      expect(store.printLanguage()).toBe('EN');
      expect(store.paperSize()).toBe('A4');
    });
  });

  describe('print choices (SAH-55-2)', () => {
    it('saves this visit\'s choice with PUT, and the clinic setting is not touched', () => {
      boot();
      store.setPrint({ language: 'MR' });
      const req = http.expectOne(`${EMR}/r1/print-preferences`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({ language: 'MR', paperSize: null });
      req.flush({});
      expect(store.printLanguage()).toBe('MR');
      store.setPrintLanguage('HI');
      expect(http.expectOne(`${EMR}/r1/print-preferences`).request.body).toEqual({ language: 'HI', paperSize: null });
    });

    it('keeps the choice on screen when saving it fails', () => {
      boot();
      store.setPrint({ paperSize: 'A5' });
      http.expectOne(`${EMR}/r1/print-preferences`).flush({}, { status: 500, statusText: 'Server Error' });
      expect(store.paperSize()).toBe('A5');
    });

    it('words the ready footer with the chosen language', () => {
      boot();
      store.setValue('complaints', 'complaint', list('Headache'));
      expect(store.footer()).toBe('Ready to complete. Prescription prints in English with English drug names.');
      store.setPrint({ language: 'MR' });
      http.expectOne(`${EMR}/r1/print-preferences`).flush({});
      expect(store.footer()).toBe('Ready to complete. Prescription prints in Marathi with English drug names.');
    });
  });

  describe('Done with a component field (ADR-022 D2)', () => {
    it('is Done with one active item in the list', () => {
      boot();
      expect(store.stateOf('complaints')?.done).toBe(false);
      store.setValue('complaints', 'complaint', list('Headache'));
      expect(store.stateOf('complaints')?.done).toBe(true);
      expect(store.ready()).toBe(true);
    });

    it('is not Done when every item is switched off, but the items are still saved', () => {
      boot();
      store.setValue('complaints', 'complaint', list('Headache', { active: false }));
      expect(store.stateOf('complaints')?.done).toBe(false);
      store.save().subscribe();
      const req = http.expectOne(`${EMR}/r1/draft`);
      expect(req.request.body.sections[0].values.complaint.items[0].active).toBe(false);
      req.flush(phase3Record({ revision: 4 }));
    });
  });

  describe('saving component values', () => {
    it('sends only the writable properties of an item', () => {
      boot();
      store.setValue('complaints', 'complaint', list('Headache', { addedByName: 'Dr X', addedAt: '2026-11-12T05:00:00Z', code: 'R51' }));
      store.save().subscribe();
      const req = http.expectOne(`${EMR}/r1/draft`);
      expect(Object.keys(req.request.body.sections[0].values.complaint.items[0]).sort()).toEqual(['active', 'attr1', 'attr2', 'id', 'itemKey', 'newToList', 'text']);
      req.flush(phase3Record({ revision: 4 }));
    });

    it('takes back the values the server stamped (Added on, Added by) when nothing was edited since', () => {
      boot();
      store.setValue('complaints', 'complaint', list('Headache'));
      store.save().subscribe();
      const saved = phase3Record({ revision: 4 });
      saved.sections[0] = recordSection('complaints', { values: { complaint: list('Headache', { addedAt: '2026-11-12T05:34:00Z', addedByName: 'Dr Test' }) } });
      http.expectOne(`${EMR}/r1/draft`).flush(saved);
      const stored = store.values()['complaints']['complaint'] as EmrListValue;
      expect(stored.items[0].addedByName).toBe('Dr Test');
      expect(store.dirty()).toBe(false);
    });

    it('does not overwrite what the doctor typed while the save was running', () => {
      boot();
      store.setValue('complaints', 'complaint', list('Headache'));
      store.save().subscribe();
      store.setValue('complaints', 'complaint', list('Cough'));
      const saved = phase3Record({ revision: 4 });
      saved.sections[0] = recordSection('complaints', { values: { complaint: list('Headache', { addedByName: 'Dr Test' }) } });
      http.expectOne(`${EMR}/r1/draft`).flush(saved);
      expect((store.values()['complaints']['complaint'] as EmrListValue).items[0].text).toBe('Cough');
      expect(store.dirty()).toBe(true);
    });

    it('shows the already-billed refusal of the server as it is', () => {
      boot();
      store.setValue('complaints', 'complaint', list('Headache'));
      store.save().subscribe();
      http
        .expectOne(`${EMR}/r1/draft`)
        .flush({ type: 'https://sahaj.in/problems/already-billed', title: 'Already billed', status: 409, detail: 'Already billed. Ask billing to adjust.' }, { status: 409, statusText: 'Conflict' });
      expect(store.saveError()).toBe('Already billed. Ask billing to adjust.');
      expect(store.dirty()).toBe(true);
    });
  });

  describe('drug-class allergy check (SAH-48-2)', () => {
    function bootWithClash(review: boolean | null = null): void {
      boot();
      store.noteMedicineClass('m2', 'SULFA');
      store.setValue('allergy', 'drug_allergy', SULFA);
      store.setValue('rx', 'drug', rx(review));
    }

    it('raises an alert for a drug of a class the patient is allergic to, and blocks Complete', () => {
      bootWithClash();
      store.setValue('complaints', 'complaint', list('Headache'));
      expect(store.allergyAlerts()).toEqual([{ rowId: 'r1', medicineName: 'Co-trimoxazole 960 mg', allergyClass: 'SULFA', reviewed: false }]);
      expect(store.alertBlocked()).toBe(true);
      expect(store.ready()).toBe(false);
      expect(store.footer()).toBe('Remove the drug or confirm the allergy alert before completing.');
      expect(store.printBlocked()).toBe(true);
    });

    it('is not blocked once the doctor has reviewed the alert', () => {
      bootWithClash(true);
      store.setValue('complaints', 'complaint', list('Headache'));
      expect(store.allergyAlerts()[0].reviewed).toBe(true);
      expect(store.ready()).toBe(true);
      expect(store.printBlocked()).toBe(false);
    });

    it('treats "not confirmed" as unresolved', () => {
      bootWithClash(false);
      expect(store.alertBlocked()).toBe(true);
    });

    it('raises it again when the removed drug is added back with a new id', () => {
      bootWithClash(true);
      store.setValue('rx', 'drug', { ...rx(null), rows: [{ ...rx(null).rows[0], id: 'r2' }] });
      expect(store.allergyAlerts()).toEqual([{ rowId: 'r2', medicineName: 'Co-trimoxazole 960 mg', allergyClass: 'SULFA', reviewed: false }]);
    });

    it('clears the alert when the allergy class is no longer picked', () => {
      bootWithClash();
      store.setValue('allergy', 'drug_allergy', []);
      expect(store.allergyAlerts()).toEqual([]);
      expect(store.alertBlocked()).toBe(false);
    });

    it('never triggers for free text in the allergy list', () => {
      boot();
      store.noteMedicineClass('m2', 'SULFA');
      store.setValue('allergy', 'drug_allergy', [{ itemKey: null, text: 'sulfa', newToList: true }]);
      store.setValue('rx', 'drug', rx(null));
      expect(store.allergyAlerts()).toEqual([]);
    });

    it('reads the class of a saved row from the server\'s alerts', () => {
      boot(
        phase3Record({
          allergyAlerts: [{ sectionKey: 'rx', fieldKey: 'drug', rowId: 'r1', medicineKey: 'm2', medicineName: 'Co-trimoxazole 960 mg', allergyClass: 'SULFA', allergyClassLabel: 'Sulfa drugs', reviewed: false, reviewedAt: null, reviewedByName: null }],
          sections: [
            recordSection('complaints'),
            recordSection('allergy', { values: { drug_allergy: SULFA } }),
            recordSection('local'),
            recordSection('rx', { values: { drug: rx(null) } }),
          ],
        }),
      );
      expect(store.allergyAlerts()).toHaveLength(1);
    });

    it('follows the allergies the doctor is editing for the history facts', () => {
      boot();
      expect(store.allergyLine()).toBeNull();
      store.setValue('allergy', 'drug_allergy', SULFA);
      expect(store.allergyLine()).toBe('Sulfa drugs');
    });
  });

  describe('medico-legal tag (SAH-57-3)', () => {
    it('is on while a body map has a medico-legal pin', () => {
      boot();
      expect(store.mlc()).toBe(false);
      const map: EmrBodyMapValue = {
        component: 'BODY_MAP',
        schemaVersion: 1,
        noFindingsConfirmed: false,
        pins: [{ no: 1, side: 'FRONT', x: 0.5, y: 0.5, region: 'head', where: 'Head and face', finding: 'WOUND', cause: 'ASSAULT', severity: 'MILD', sizeCm: 2, note: '', mlc: true }],
      };
      store.setValue('local', 'map', map);
      expect(store.mlc()).toBe(true);
    });
  });

  describe('copy actions (SAH-48-1, SAH-50-4)', () => {
    it('copies on the server and takes the answer for the sections that are not being edited', () => {
      boot();
      let result: unknown = 'not yet';
      store.copyItems({ source: 'LAST_SIGNED_PRESCRIPTION', what: 'MEDICINES' }).subscribe((r) => (result = r));
      const req = http.expectOne(`${EMR}/r1/copy-items`);
      expect(req.request.body).toEqual({ source: 'LAST_SIGNED_PRESCRIPTION', what: 'MEDICINES' });
      const next = phase3Record({ revision: 4 });
      next.sections[3] = recordSection('rx', { values: { drug: rx(null) } });
      req.flush({ added: 1, skipped: 0, record: next });
      expect(result).toMatchObject({ added: 1 });
      expect((store.values()['rx']['drug'] as EmrPrescriptionValue).rows[0].name).toBe('Co-trimoxazole 960 mg');
      expect(store.record()?.revision).toBe(4);
    });

    it('saves what is typed first, so the server copies into what the doctor sees', () => {
      boot();
      store.setValue('complaints', 'complaint', list('Headache'));
      store.copyItems({ source: 'PAST_RECORD', sourceRecordId: 'p9', what: 'TESTS' }).subscribe();
      http.expectOne(`${EMR}/r1/draft`).flush(phase3Record({ revision: 4 }));
      http.expectOne(`${EMR}/r1/copy-items`).flush({ added: 0, skipped: 2, record: phase3Record({ revision: 4 }) });
      expect(store.dirty()).toBe(false);
    });

    it('does not copy when the save before it fails', () => {
      boot();
      store.setValue('complaints', 'complaint', list('Headache'));
      let result: unknown = 'not yet';
      store.copyItems({ source: 'PAST_RECORD', sourceRecordId: 'p9', what: 'TESTS' }).subscribe((r) => (result = r));
      http.expectOne(`${EMR}/r1/draft`).flush({}, { status: 500, statusText: 'Server Error' });
      http.expectNone(`${EMR}/r1/copy-items`);
      expect(result).toBeNull();
    });

    it('answers null on a read-only record, and shows the server message when the copy is refused', () => {
      boot(phase3Record({ readOnly: true }));
      let result: unknown = 'not yet';
      store.copyItems({ source: 'LAST_SIGNED_PRESCRIPTION', what: 'MEDICINES' }).subscribe((r) => (result = r));
      expect(result).toBeNull();
      http.expectNone(`${EMR}/r1/copy-items`);
    });

    it('shows the message of the server when the target is signed (409)', () => {
      boot();
      let result: unknown = 'not yet';
      store.copyItems({ source: 'LAST_SIGNED_PRESCRIPTION', what: 'MEDICINES' }).subscribe((r) => (result = r));
      http
        .expectOne(`${EMR}/r1/copy-items`)
        .flush({ type: 'https://sahaj.in/problems/emr-record-signed', title: 'Signed', status: 409, detail: 'This visit is signed' }, { status: 409, statusText: 'Conflict' });
      expect(result).toBeNull();
      expect(store.notice()).toBe('This visit is signed');
    });
  });

  it('keeps the last signed prescription of the record for "Repeat last Rx"', () => {
    boot(phase3Record({ lastSignedPrescription: { recordId: 'r0', date: '2026-10-28' } }));
    expect(store.lastSignedPrescription()).toEqual({ recordId: 'r0', date: '2026-10-28' });
  });

  it('exposes the patient sex for the body drawing', () => {
    boot();
    expect(store.patientSex()).toBe('M');
  });
});
