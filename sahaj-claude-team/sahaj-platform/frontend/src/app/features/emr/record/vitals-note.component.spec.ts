import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { casePaperContext, record } from '../emr.fixtures';
import { EmrEncounterVitals, EmrVitalsReading } from '../emr-finish.model';
import { EmrRecordStore } from './emr-record.store';
import { VitalsNoteComponent } from './vitals-note.component';
import { readingText, vitalsFieldValues } from './vitals-prefill.logic';

const EMR = '/api/v1/opd/emr/records';
const READING: EmrVitalsReading = {
  sourceEntryId: 'e1',
  source: 'IPD_NURSING_CHART',
  recordedByName: 'Sr. Anjali Pawar',
  recordedAt: '2026-11-13T05:12:00Z',
  bpSystolic: 146,
  bpDiastolic: 92,
  pulse: 82,
  spo2: 98,
  temperature: { value: 37, unit: '°C' },
  respiratoryRate: 18,
};
const sources = { opd: 'NOT_CAPTURED', ipd: 'AVAILABLE', er: 'NOT_CAPTURED' } as const;

describe('vitals from the nursing chart (SAH-68-1)', () => {
  describe('values', () => {
    it('fills BP, pulse, SpO₂ and temperature; the temperature field is in °F so °C is converted', () => {
      expect(vitalsFieldValues(READING)).toEqual({ bp_systolic: 146, bp_diastolic: 92, pulse: 82, spo2: 98, temperature: 98.6 });
      expect(vitalsFieldValues({ ...READING, temperature: { value: 99.1, unit: '°F' } })['temperature']).toBe(99.1);
    });

    it('leaves out what the nurse did not record (the doctor fills it)', () => {
      expect(vitalsFieldValues({ sourceEntryId: 'e', source: 'IPD_NURSING_CHART', recordedByName: 'x', recordedAt: '2026-11-13T05:12:00Z', pulse: 80 })).toEqual({ pulse: 80 });
    });

    it('words the reading with units', () => {
      expect(readingText(READING)).toBe('BP 146/92 · Pulse 82 · SpO₂ 98% · Temp 37 °C · RR 18');
    });
  });

  describe('on screen', () => {
    let fixture: ComponentFixture<VitalsNoteComponent>;
    let http: HttpTestingController;
    let store: EmrRecordStore;
    let el: HTMLElement;

    function boot(vitals: EmrEncounterVitals, over: Parameters<typeof record>[0] = {}): void {
      TestBed.configureTestingModule({ providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting(), EmrRecordStore] });
      http = TestBed.inject(HttpTestingController);
      store = TestBed.inject(EmrRecordStore);
      store.load('r1');
      http.expectOne(`${EMR}/r1`).flush(record({ encounterType: 'IPD_ROUND', ...over }));
      http.expectOne(`${EMR}/r1/case-paper`).flush(casePaperContext());
      fixture = TestBed.createComponent(VitalsNoteComponent);
      fixture.detectChanges();
      http.expectOne(`${EMR}/r1/encounter-vitals`).flush(vitals);
      fixture.detectChanges();
      el = fixture.nativeElement as HTMLElement;
    }

    afterEach(() => http.verify());

    it('pre-fills the empty Vitals fields once and says who recorded them and when', () => {
      boot({ current: READING, earlier: null, sources });
      expect(store.values()['vitals']).toMatchObject({ bp_systolic: 146, bp_diastolic: 92, pulse: 82 });
      expect(el.querySelector('[data-testid=vitals-recorded]')!.textContent).toBe('Recorded by Sr. Anjali Pawar · Nurse at 10:42 AM');
      expect(el.querySelector('[data-testid=vitals-offer]')).toBeNull();
    });

    it('sends the entry it was pre-filled from with the next save of the section', () => {
      boot({ current: READING, earlier: null, sources });
      store.save().subscribe();
      const req = http.expectOne(`${EMR}/r1/draft`);
      expect(req.request.body.sections[0]).toMatchObject({ key: 'vitals', prefill: { source: 'IPD_NURSING_CHART', sourceEntryId: 'e1' } });
      req.flush(record({ encounterType: 'IPD_ROUND', revision: 4 }));
    });

    it('does not overwrite what the doctor typed: it offers the reading instead', () => {
      TestBed.configureTestingModule({ providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting(), EmrRecordStore] });
      http = TestBed.inject(HttpTestingController);
      store = TestBed.inject(EmrRecordStore);
      store.load('r1');
      const view = record({ encounterType: 'IPD_ROUND' });
      view.sections = view.sections.map((s) => (s.key === 'vitals' ? { ...s, values: { pulse: 70 } } : s));
      http.expectOne(`${EMR}/r1`).flush(view);
      http.expectOne(`${EMR}/r1/case-paper`).flush(casePaperContext());
      fixture = TestBed.createComponent(VitalsNoteComponent);
      fixture.detectChanges();
      http.expectOne(`${EMR}/r1/encounter-vitals`).flush({ current: READING, earlier: null, sources });
      fixture.detectChanges();
      el = fixture.nativeElement as HTMLElement;
      expect(store.values()['vitals']).toEqual({ pulse: 70 });
      expect(el.querySelector('[data-testid=vitals-offer]')!.textContent).toContain('New vitals recorded. Use them?');
      el.querySelector<HTMLButtonElement>('[data-act=use-vitals]')!.click();
      fixture.detectChanges();
      expect(store.values()['vitals']).toMatchObject({ pulse: 82, bp_systolic: 146 });
      expect(el.querySelector('[data-testid=vitals-offer]')).toBeNull();
    });

    it('offers a reading recorded after the EMR opened ("New vitals recorded. Use them?")', () => {
      boot({ current: READING, earlier: null, sources });
      store.vitalsRead.set({ current: { ...READING, sourceEntryId: 'e2', pulse: 90, recordedAt: '2026-11-13T06:00:00Z' }, earlier: null, sources });
      fixture.detectChanges();
      expect(el.querySelector('[data-testid=vitals-offer]')!.textContent).toContain('Pulse 90');
    });

    it('says "Last taken by <name>, <date and time>. Doctor\'s entry for this visit is still needed." and copies nothing', () => {
      boot({ current: null, earlier: { recordedByName: 'Sr. Meena Rao', recordedAt: '2026-11-12T10:00:00Z' }, sources });
      expect(el.querySelector('[data-testid=vitals-earlier]')!.textContent!.replace(/\s+/g, ' ').trim()).toBe(
        "Last taken by Sr. Meena Rao, 12 Nov 2026, 3:30 PM. Doctor's entry for this visit is still needed.",
      );
      expect(store.values()['vitals']).toEqual({});
    });

    it('draws nothing when no reading exists', () => {
      boot({ current: null, earlier: null, sources });
      expect(el.textContent!.trim()).toBe('');
    });

    it('reads nothing for an OPD visit (the OPD vitals step stores no values)', () => {
      TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), EmrRecordStore] });
      http = TestBed.inject(HttpTestingController);
      store = TestBed.inject(EmrRecordStore);
      store.load('r1');
      http.expectOne(`${EMR}/r1`).flush(record());
      http.expectOne(`${EMR}/r1/case-paper`).flush(casePaperContext());
      store.loadVitals();
      http.expectNone(`${EMR}/r1/encounter-vitals`);
    });
  });
});
