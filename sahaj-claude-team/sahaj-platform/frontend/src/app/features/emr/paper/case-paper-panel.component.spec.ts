import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { casePaperContext, field, record, section } from '../emr.fixtures';
import { EmrRecordView } from '../emr.model';
import { EmrCasePaperContext, EmrListValue, EmrPrescriptionValue } from '../emr-phase3.model';
import { EmrRecordStore } from '../record/emr-record.store';
import { CasePaperPanelComponent } from './case-paper-panel.component';

const EMR = '/api/v1/opd/emr/records';

function printable(over: Partial<EmrRecordView> = {}): EmrRecordView {
  const base = record(over);
  const sections = [
    section({ key: 'vitals', name: 'Vitals', print: true, fields: [field({ key: 'weight', type: 'NUMBER', valueKind: 'NUMBER' }), field({ key: 'bp_systolic', type: 'NUMBER', valueKind: 'NUMBER' }), field({ key: 'bp_diastolic', type: 'NUMBER', valueKind: 'NUMBER' })] }),
    section({ key: 'complaints', name: 'Chief complaints', print: true, required: true, fields: [field({ key: 'complaint', valueKind: 'COMPONENT', type: 'SEARCH_LIST', required: true, component: { type: 'EMR_LIST', listKind: 'COMPLAINTS' } })] }),
    section({ key: 'allergy', name: 'Allergies', print: true, fields: [field({ key: 'drug_allergy', valueKind: 'ITEM_LIST', type: 'SEARCH_LIST' })] }),
    section({ key: 'rx', name: 'Prescription (Rx)', part: 'TREATMENT', print: true, fields: [field({ key: 'drug', valueKind: 'COMPONENT', type: 'SEARCH_LIST', component: { type: 'PRESCRIPTION', listKind: null } })] }),
  ];
  return { ...base, layout: { ...base.layout, sections }, sections: sections.map((s) => ({ key: s.key, status: 'OPTIONAL', values: {}, copiedFrom: null, copyPendingConfirmation: false, updatedAt: null })) };
}

const complaint = (text: string): EmrListValue => ({
  component: 'EMR_LIST',
  kind: 'COMPLAINTS',
  schemaVersion: 1,
  items: [{ id: 'i1', itemKey: null, text, newToList: true, attr1: 'D2', attr2: 'MODERATE', active: true }],
});

describe('CasePaperPanelComponent (SAH-55)', () => {
  let fixture: ComponentFixture<CasePaperPanelComponent>;
  let http: HttpTestingController;
  let store: EmrRecordStore;
  let el: HTMLElement;

  function boot(ctx: EmrCasePaperContext = casePaperContext(), view: EmrRecordView = printable()): void {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), EmrRecordStore] });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(EmrRecordStore);
    store.load('r1');
    http.expectOne(`${EMR}/r1`).flush(view);
    http.expectOne(`${EMR}/r1/case-paper`).flush(ctx);
    fixture = TestBed.createComponent(CasePaperPanelComponent);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  const q = <T extends Element = HTMLElement>(sel: string) => el.querySelector<T>(sel);
  const qa = (sel: string) => Array.from(el.querySelectorAll<HTMLElement>(sel));
  const rowOf = (label: string) => qa('.prow').find((r) => r.querySelector('.h')!.textContent!.trim() === label)!;

  afterEach(() => {
    vi.restoreAllMocks();
    http.verify();
  });

  describe('header (SAH-55-1)', () => {
    it('shows "Live case paper", the live mark and "n of 10 filled"', () => {
      boot();
      expect(q('h3')!.textContent).toBe('Live case paper');
      expect(q('.live')!.textContent).toBe('● Live');
      expect(q('[data-testid=paper-fill]')!.textContent).toBe('0 of 10 filled');
    });

    it('shows the clinic, the doctor with the registration number, and the patient line', () => {
      boot();
      const text = q('.sheet')!.textContent!.replace(/\s+/g, ' ');
      expect(text).toContain('Test Clinic');
      expect(text).toContain('12 Test Road · 020 5555 0100 · General Medicine');
      expect(text).toContain('Dr Test');
      expect(text).toContain('MBBS, MD');
      expect(text).toContain('Reg. No. MMC 12345');
      expect(text).toContain('Test Patient');
      expect(text).toContain('52 y · Male');
      expect(text).toContain('UHID U-000042');
      expect(text).toContain('OPD · follow-up visit');
    });

    it('shows the age text of a Paediatrics visit ("18 months · Male") and whole years elsewhere (SAH-73-4)', () => {
      const ctx = casePaperContext({ patient: { name: 'Baby', age: 1, ageDetail: { value: 18, unit: 'MONTHS', fromDateOfBirth: true }, sex: 'M', uhid: 'U-1' } });
      boot(ctx, printable({ specialty: { profile: 'paeds', mode: null, tagNote: '' } as never }));
      expect(q('.pat')!.textContent!.replace(/\s+/g, ' ')).toContain('18 months · Male');
      TestBed.resetTestingModule();
      boot(ctx);
      expect(q('.pat')!.textContent!.replace(/\s+/g, ' ')).toContain('1 y · Male');
    });

    it('says "Reg. no. not set" when the doctor has no registration number, and "New patient" without a UHID', () => {
      boot(casePaperContext({ doctor: { name: 'Dr Test', qualification: null, registrationNo: null }, patient: { name: 'Test Patient', age: 52, sex: 'M', uhid: null } }));
      const text = q('.sheet')!.textContent!.replace(/\s+/g, ' ');
      expect(text).toContain('Reg. no. not set');
      expect(text).toContain('New patient');
    });

    it('adds the weight and one vitals line when today\'s vitals exist', () => {
      boot();
      store.setValue('vitals', 'weight', 74);
      store.setValue('vitals', 'bp_systolic', 142);
      store.setValue('vitals', 'bp_diastolic', 90);
      fixture.detectChanges();
      const text = q('.pat')!.textContent!.replace(/\s+/g, ' ');
      expect(text).toContain('52 y · Male · 74 kg');
      expect(text).toContain('BP 142/90');
    });
  });

  describe('ten headings, live', () => {
    it('shows "Not recorded yet" under every empty heading', () => {
      boot();
      expect(qa('.prow')).toHaveLength(10);
      expect(qa('.none').every((n) => n.textContent === 'Not recorded yet')).toBe(true);
    });

    it('fills a heading as the doctor types, without saving, and updates the count', () => {
      boot();
      store.setValue('complaints', 'complaint', complaint('Headache'));
      fixture.detectChanges();
      expect(rowOf('Chief complaints').textContent).toContain('Headache · 2 days');
      expect(q('[data-testid=paper-fill]')!.textContent).toBe('1 of 10 filled');
      expect(store.paperSubtitle()).toBe('1 of 10 filled · live');
      http.expectNone((r) => r.method === 'PUT');
    });

    it('hides the empty headings with "Hide empty", keeps the count, and shows them again', () => {
      boot();
      store.setValue('complaints', 'complaint', complaint('Headache'));
      fixture.detectChanges();
      q<HTMLButtonElement>('[data-act=hide-empty]')!.click();
      fixture.detectChanges();
      expect(qa('.prow')).toHaveLength(1);
      expect(q('[data-act=hide-empty]')!.textContent!.trim()).toBe('✓ Hiding empty');
      expect(q('[data-testid=paper-fill]')!.textContent).toBe('1 of 10 filled');
      q<HTMLButtonElement>('[data-act=hide-empty]')!.click();
      fixture.detectChanges();
      expect(qa('.prow')).toHaveLength(10);
    });

    it('numbers the medicines and prints their names in capitals when the clinic setting is on', () => {
      boot();
      const rx: EmrPrescriptionValue = {
        component: 'PRESCRIPTION',
        schemaVersion: 1,
        rows: [{ id: 'r1', medicineId: 'm1', name: 'Telmisartan 40 mg', form: 'Tablet', frequency: '1-0-0', days: 30, when: 'AFTER_FOOD', source: 'SEARCH' }],
      };
      store.setValue('rx', 'drug', rx);
      fixture.detectChanges();
      const med = Array.from(rowOf('Medicines ℞').querySelectorAll('.med span')).map((x) => x.textContent);
      expect(med).toEqual(['1.', 'TELMISARTAN 40 MG', '1-0-0', '30 days', 'After food']);
    });
  });

  describe('size and language (SAH-55-2)', () => {
    it('starts with the clinic defaults and changes this visit only', () => {
      boot();
      expect(q('[data-size=A4]')!.getAttribute('aria-pressed')).toBe('true');
      expect(q('[data-lang=EN]')!.getAttribute('aria-pressed')).toBe('true');
      q<HTMLButtonElement>('[data-size=A5]')!.click();
      const req = http.expectOne(`${EMR}/r1/print-preferences`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({ language: null, paperSize: 'A5' });
      req.flush({});
      fixture.detectChanges();
      expect(q('.sheet')!.classList).toContain('a5');
    });

    it('shows the headings in Marathi or Hindi', () => {
      boot();
      q<HTMLButtonElement>('[data-lang=MR]')!.click();
      http.expectOne(`${EMR}/r1/print-preferences`).flush({});
      fixture.detectChanges();
      expect(qa('.prow .h')[0].textContent!.trim()).toBe('मुख्य तक्रारी');
      q<HTMLButtonElement>('[data-lang=HI]')!.click();
      http.expectOne(`${EMR}/r1/print-preferences`).flush({});
      fixture.detectChanges();
      expect(qa('.prow .h')[9].textContent!.trim()).toBe('अगली मुलाक़ात');
    });

    it('keeps the entries as typed whatever the heading language', () => {
      boot();
      store.setValue('complaints', 'complaint', complaint('Headache'));
      q<HTMLButtonElement>('[data-lang=MR]')!.click();
      http.expectOne(`${EMR}/r1/print-preferences`).flush({});
      fixture.detectChanges();
      expect(q('.sheet')!.textContent).toContain('Headache · 2 days');
    });
  });

  describe('Print and Share', () => {
    it('prints the paper only, at the chosen size, and leaves nothing behind', () => {
      let seen: { body: boolean; sheets: number; page: string | null } | null = null;
      const print = vi.spyOn(window, 'print').mockImplementation(() => {
        seen = {
          body: document.body.classList.contains('printing-paper'),
          sheets: document.querySelectorAll('.print-root .print-sheet').length,
          page: document.head.querySelector('style[data-print-page]')?.getAttribute('data-print-page') ?? null,
        };
      });
      boot();
      q<HTMLButtonElement>('[data-size=A5]')!.click();
      http.expectOne(`${EMR}/r1/print-preferences`).flush({});
      fixture.detectChanges();
      q<HTMLButtonElement>('[data-act=print]')!.click();
      expect(print).toHaveBeenCalledTimes(1);
      expect(seen).toEqual({ body: true, sheets: 1, page: 'A5' });
      expect(document.body.classList.contains('printing-paper')).toBe(false);
      expect(document.querySelector('.print-root')).toBeNull();
      expect(document.head.querySelector('style[data-print-page]')).toBeNull();
    });

    it('lets the doctor try again when the print fails', () => {
      vi.spyOn(window, 'print').mockImplementation(() => {
        throw new Error('no printer');
      });
      boot();
      q<HTMLButtonElement>('[data-act=print]')!.click();
      fixture.detectChanges();
      expect(q('[data-testid=paper-toast]')!.textContent).toBe('We could not print. Try again.');
      expect(q<HTMLButtonElement>('[data-act=print]')!.disabled).toBe(false);
      expect(document.body.classList.contains('printing-paper')).toBe(false);
    });

    it('disables Print with "Resolve the allergy alert first" while an allergy alert is unresolved', () => {
      boot();
      store.noteMedicineClass('m2', 'SULFA');
      store.setValue('allergy', 'drug_allergy', [{ itemKey: 'SULFA', text: 'Sulfa drugs', newToList: false }]);
      store.setValue('rx', 'drug', {
        component: 'PRESCRIPTION',
        schemaVersion: 1,
        rows: [{ id: 'r9', medicineId: 'm2', name: 'Co-trimoxazole 960 mg', form: 'Tablet', frequency: '1-0-1', days: 5, when: 'AFTER_FOOD', source: 'SEARCH' }],
      });
      fixture.detectChanges();
      expect(q<HTMLButtonElement>('[data-act=print]')!.disabled).toBe(true);
      expect(q('[data-testid=print-blocked]')!.textContent).toBe('Resolve the allergy alert first');
    });

    it('shows Share · WhatsApp / ABHA switched off with the note', () => {
      boot();
      const share = q<HTMLButtonElement>('[data-act=share]')!;
      expect(share.textContent).toBe('Share · WhatsApp / ABHA');
      expect(share.disabled).toBe(true);
      expect(q('#cp-share-note')!.textContent).toBe('Needs the WhatsApp and ABHA services');
    });
  });

  describe('footer and signature', () => {
    it('prints the capitals line when it is on, and always "Not valid without signature."', () => {
      boot();
      const legal = q('.legal')!.textContent!;
      expect(legal).toBe('Medicines in generic names, capital letters (NMC). Not valid without signature.');
      expect(legal).not.toContain('ABHA');
    });

    it('leaves out the capitals line when it is off', () => {
      boot(casePaperContext({ clinicDefaults: { paperSize: 'A4', language: 'EN', capitals: false } }));
      expect(q('.legal')!.textContent).toBe('Not valid without signature.');
    });

    it('prints "Not valid without signature." only until the visit is signed (SAH-51-6)', () => {
      boot();
      expect(q('.legal')!.textContent).toContain('Not valid without signature.');
      TestBed.resetTestingModule();
      boot(casePaperContext({ signature: { signed: true, signerName: 'Dr Test', signedAt: '2026-11-12T05:35:00Z' } }));
      expect(q('.legal')!.textContent).not.toContain('Not valid without signature.');
    });

    it('prints the addenda under the signature, newest last (SAH-51-5)', () => {
      boot(
        casePaperContext({
          signature: { signed: true, signerName: 'Dr Test', signedAt: '2026-11-12T05:35:00Z' },
          addenda: [
            { id: 'a1', reason: 'Wrong dose', text: 'Dose was 5 mg', createdAt: '2026-11-13T06:00:00Z', createdByName: 'Test' },
            { id: 'a2', reason: 'Late result', text: 'HbA1c 7.2', createdAt: '2026-11-13T07:00:00Z', createdByName: 'Dr. Test' },
          ],
        }),
      );
      const lines = qa('[data-testid=paper-addenda] .line').map((l) => l.textContent!.trim());
      expect(lines).toEqual([
        'Addendum · 13 Nov 2026, 11:30 AM · Dr. Test: Wrong dose. Dose was 5 mg',
        'Addendum · 13 Nov 2026, 12:30 PM · Dr. Test: Late result. HbA1c 7.2',
      ]);
    });

    it('leaves the signature area empty until the visit is signed, then shows the doctor and the time', () => {
      boot();
      expect(q('.signline')).not.toBeNull();
      expect(q('.signed')).toBeNull();
      TestBed.resetTestingModule();
      boot(casePaperContext({ signature: { signed: true, signerName: 'Dr Test', signedAt: '2026-11-12T05:35:00Z' } }));
      expect(q('.signed')!.textContent).toBe('Dr Test');
      expect(q('.sign')!.textContent).toContain('12 Nov 2026, 11:05 AM');
    });
  });

  it('shows an error with a retry when the case paper cannot be loaded', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), EmrRecordStore] });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(EmrRecordStore);
    store.load('r1');
    http.expectOne(`${EMR}/r1`).flush(printable());
    http.expectOne(`${EMR}/r1/case-paper`).flush({}, { status: 500, statusText: 'Server Error' });
    fixture = TestBed.createComponent(CasePaperPanelComponent);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
    expect(q('.notice.err')!.textContent).toContain('We could not load the case paper');
    q<HTMLButtonElement>('[data-act=retry-paper]')!.click();
    http.expectOne(`${EMR}/r1/case-paper`).flush(casePaperContext());
    fixture.detectChanges();
    expect(q('.sheet')).not.toBeNull();
  });
});
