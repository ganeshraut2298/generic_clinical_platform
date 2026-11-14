import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { EMR_COMPONENT_CONTEXT, EmrComponentContext } from '../emr-context';
import { emptyPastPage } from '../emr.fixtures';
import { EmrKeyFacts, EmrPastVisit, EmrPastVisitDetail, EmrPastVisitPage } from '../emr-phase3.model';
import { PastVisitsPanelComponent } from './past-visits-panel.component';

const EMR = '/api/v1/opd/emr/records/r1';

const opdVisit: EmrPastVisit = { kind: 'OPD', id: 'p1', date: '2026-10-28', title: 'BP follow-up', place: 'Dr Test · General Medicine', signed: true };
const ipdVisit: EmrPastVisit = { kind: 'IPD', id: 'a1', date: '2025-07-12', title: 'Dengue fever · admitted 3 days', place: 'Dr Test · Ward 2 bed 14', signed: true };

const facts: EmrKeyFacts = {
  starterKey: 'gm',
  facts: [
    { key: 'allergies', label: 'Allergies', value: 'Sulfa drugs', important: true },
    { key: 'long_term', label: 'Long-term', value: 'Type 2 diabetes (2019)', important: false },
    { key: 'surgeries', label: 'Surgeries', value: null, important: false },
    { key: 'regular_meds', label: 'Regular meds', value: 'Telmisartan 40', important: false },
  ],
};

function detail(over: Partial<EmrPastVisitDetail> = {}): EmrPastVisitDetail {
  return {
    kind: 'OPD',
    id: 'p1',
    signed: true,
    lines: [{ label: 'Complaints', value: 'Headache on and off' }],
    medicines: [{ name: 'Telmisartan 40 mg', frequency: '1-0-0', days: 30, when: 'AFTER_FOOD' }],
    results: [{ test: 'HbA1c', value: '8.1 %', abnormal: true }, { test: 'Creatinine', value: '0.9 mg/dL', abnormal: false }],
    tests: ['HbA1c'],
    canCopyMedicines: true,
    canRepeatTests: true,
    copyDisabledReason: null,
    open: { target: 'RECORD', id: 'p1' },
    ...over,
  };
}

describe('PastVisitsPanelComponent (SAH-50)', () => {
  let fixture: ComponentFixture<PastVisitsPanelComponent>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let copyItems: ReturnType<typeof vi.fn>;

  function boot(page: EmrPastVisitPage = emptyPastPage(), allergyLine: string | null = null): void {
    copyItems = vi.fn(() => of({ added: 2, skipped: 0, record: {} }));
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: EMR_COMPONENT_CONTEXT, useValue: { copyItems } as unknown as EmrComponentContext },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PastVisitsPanelComponent);
    fixture.componentRef.setInput('recordId', 'r1');
    fixture.componentRef.setInput('allergyLine', allergyLine);
    fixture.detectChanges();
    http.expectOne(`${EMR}/key-facts`).flush(facts);
    flushPage(page);
    el = fixture.nativeElement as HTMLElement;
  }

  function flushPage(page: EmrPastVisitPage): void {
    http.expectOne((r) => r.url === `${EMR}/past-visits`).flush(page);
    fixture.detectChanges();
  }

  const q = <T extends Element = HTMLElement>(sel: string) => el.querySelector<T>(sel);
  const qa = (sel: string) => Array.from(el.querySelectorAll<HTMLElement>(sel));
  const page = (items: EmrPastVisit[], over: Partial<EmrPastVisitPage> = {}): EmrPastVisitPage =>
    emptyPastPage({ counts: { opd: items.filter((i) => i.kind === 'OPD').length, ipd: items.filter((i) => i.kind !== 'OPD').length }, items, ...over });

  async function type(text: string): Promise<void> {
    const input = q<HTMLInputElement>('input[type=search]')!;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    vi.advanceTimersByTime(400);
    fixture.detectChanges();
  }

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    http.verify();
  });

  describe('header and key facts (SAH-50-1, SAH-50-2)', () => {
    it('shows "Patient history" with the count "n OPD · m IPD"', () => {
      boot(page([opdVisit, ipdVisit]));
      expect(q('h3')!.textContent).toBe('Patient history');
      expect(q('[data-testid=history-count]')!.textContent).toBe('1 OPD · 1 IPD');
    });

    it('shows four labelled facts, an important one with text as well as colour, and "Not recorded" for a gap', () => {
      boot(page([opdVisit]));
      const rows = qa('.fact').map((f) => [f.querySelector('.fl')!.textContent, f.querySelector('.fv')!.textContent!.replace(/\s+/g, ' ').trim()]);
      expect(rows).toEqual([
        ['Allergies', 'Important: Sulfa drugs'],
        ['Long-term', 'Type 2 diabetes (2019)'],
        ['Surgeries', 'Not recorded'],
        ['Regular meds', 'Telmisartan 40'],
      ]);
      expect(q('.fv.imp')!.textContent).toContain('Important:');
    });

    it('follows the allergy the doctor is editing in this visit', () => {
      boot(page([opdVisit]), 'Penicillin');
      expect(qa('.fact')[0].textContent).toContain('Penicillin');
      expect(qa('.fact')[0].textContent).not.toContain('Sulfa');
    });

    it('says "First visit. No earlier records." when there is nothing', () => {
      boot();
      expect(q('[data-testid=first-visit]')!.textContent).toBe('First visit. No earlier records.');
    });

    it('says "Some entries are hidden" when ward scope leaves admissions out', () => {
      boot(page([opdVisit], { someHidden: true }));
      expect(q('[data-testid=some-hidden]')!.textContent).toBe('Some entries are hidden');
    });
  });

  describe('the rows', () => {
    it('shows date, type chip, title and the doctor with the place, newest first as the server sends them', () => {
      boot(page([opdVisit, ipdVisit]));
      const rows = qa('.visit');
      expect(rows[0].textContent).toContain('28 Oct 2026');
      expect(rows[0].querySelector('.type')!.textContent).toBe('OPD');
      expect(rows[0].textContent).toContain('BP follow-up');
      expect(rows[0].textContent).toContain('Dr Test · General Medicine');
      expect(rows[1].querySelector('.type')!.textContent).toBe('IPD');
    });

    it('shows an IPD round note with the IPD chip', () => {
      boot(page([{ ...ipdVisit, kind: 'IPD_ROUND' }]));
      expect(q('.type')!.textContent).toBe('IPD');
    });

    it('loads more when the server says there are more', () => {
      boot(page([opdVisit], { hasMore: true }));
      q<HTMLButtonElement>('[data-act=load-more]')!.click();
      const req = http.expectOne((r) => r.url === `${EMR}/past-visits`);
      expect(req.request.params.get('page')).toBe('1');
      req.flush(page([ipdVisit], { page: 1, hasMore: false }));
      fixture.detectChanges();
      expect(qa('.visit')).toHaveLength(2);
      expect(q('[data-act=load-more]')).toBeNull();
    });

    it('shows an error with a retry when the load fails, and keeps what was typed', async () => {
      vi.useFakeTimers();
      boot(page([opdVisit]));
      await type('hba1c');
      http.expectOne((r) => r.url === `${EMR}/past-visits`).flush({ detail: 'boom' }, { status: 500, statusText: 'Server Error' });
      fixture.detectChanges();
      expect(q('.notice.err')).not.toBeNull();
      expect(q<HTMLInputElement>('input[type=search]')!.value).toBe('hba1c');
      q<HTMLButtonElement>('[data-act=retry-history]')!.click();
      const retry = http.expectOne((r) => r.url === `${EMR}/past-visits`);
      expect(retry.request.params.get('q')).toBe('hba1c');
      retry.flush(emptyPastPage());
    });
  });

  describe('filter and search (SAH-50-3)', () => {
    it('limits the rows to OPD or IPD, with All selected at first', () => {
      boot(page([opdVisit, ipdVisit]));
      expect(qa('[role=tab]').map((t) => [t.textContent!.trim(), t.getAttribute('aria-selected')])).toEqual([['All', 'true'], ['OPD', 'false'], ['IPD', 'false']]);
      q<HTMLButtonElement>('[data-filter=IPD]')!.click();
      const req = http.expectOne((r) => r.url === `${EMR}/past-visits`);
      expect(req.request.params.get('type')).toBe('IPD');
      req.flush(page([ipdVisit]));
      fixture.detectChanges();
      expect(qa('.visit')).toHaveLength(1);
    });

    it('searches on the server after a short wait and shows "Nothing matches." with no error', async () => {
      vi.useFakeTimers();
      boot(page([opdVisit]));
      await type('xyz');
      const req = http.expectOne((r) => r.url === `${EMR}/past-visits`);
      expect(req.request.params.get('q')).toBe('xyz');
      req.flush(emptyPastPage({ counts: { opd: 1, ipd: 0 } }));
      fixture.detectChanges();
      expect(q('[data-testid=nothing-matches]')!.textContent).toBe('Nothing matches.');
      expect(q('.notice.err')).toBeNull();
    });

    it('has the placeholder of the mockup and a label', () => {
      boot();
      expect(q('input[type=search]')!.getAttribute('placeholder')).toBe('Search: drug, test, diagnosis');
      expect(q('.search .sr-only')!.textContent).toBe('Search history');
    });
  });

  describe('details and actions (SAH-50-4)', () => {
    function open(d: EmrPastVisitDetail = detail(), v: EmrPastVisit = opdVisit): void {
      q<HTMLButtonElement>('[data-act=toggle-visit]')!.click();
      http.expectOne(`${EMR}/past-visits/${v.kind}/${v.id}`).flush(d);
      fixture.detectChanges();
    }

    it('opens one row at a time with its lines, medicines and results, an abnormal one in red with the word', () => {
      boot(page([opdVisit, ipdVisit]));
      expect(q('.chev')!.textContent).toBe('Details ▾');
      open();
      const detailEl = q('[data-testid=visit-detail]')!;
      expect(detailEl.textContent).toContain('Complaints');
      expect(detailEl.textContent).toContain('Telmisartan 40 mg · 1-0-0 · 30 days · After food');
      expect(detailEl.querySelector('.abn')!.textContent).toContain('8.1 % · Abnormal');
      expect(qa('.res')[1].textContent).not.toContain('Abnormal');
      expect(q('.chev')!.textContent).toBe('Hide ▴');
      qa('[data-act=toggle-visit]')[1].click();
      http.expectOne(`${EMR}/past-visits/IPD/a1`).flush(detail({ kind: 'IPD', id: 'a1', open: { target: 'ADMISSION', id: 'a1' } }));
      fixture.detectChanges();
      expect(qa('[data-testid=visit-detail]')).toHaveLength(1);
    });

    it('says "No signed record yet." when nothing is signed', () => {
      boot(page([opdVisit]));
      open(detail({ signed: false, lines: [], medicines: [], results: [], tests: [] }));
      expect(q('[data-testid=visit-detail]')!.textContent).toContain('No signed record yet.');
    });

    it('copies the medicines to today and says how many', () => {
      boot(page([opdVisit]));
      open();
      q<HTMLButtonElement>('[data-act=copy-medicines]')!.click();
      fixture.detectChanges();
      expect(copyItems).toHaveBeenCalledWith({ source: 'PAST_RECORD', sourceRecordId: 'p1', what: 'MEDICINES' });
      expect(q('[data-testid=history-toast]')!.textContent).toBe("2 medicine(s) copied to today's prescription");
    });

    it('repeats the tests and says how many were added to today\'s orders', () => {
      boot(page([opdVisit]));
      open();
      q<HTMLButtonElement>('[data-act=repeat-tests]')!.click();
      fixture.detectChanges();
      expect(copyItems).toHaveBeenCalledWith({ source: 'PAST_RECORD', sourceRecordId: 'p1', what: 'TESTS' });
      expect(q('[data-testid=history-toast]')!.textContent).toBe("2 test(s) added to today's orders");
    });

    it('does not show a copy button when the visit has no medicines or no tests', () => {
      boot(page([opdVisit]));
      open(detail({ medicines: [], tests: [] }));
      expect(q('[data-act=copy-medicines]')).toBeNull();
      expect(q('[data-act=repeat-tests]')).toBeNull();
    });

    it('switches both copy actions off with "This visit is signed" once today is signed', () => {
      boot(page([opdVisit]));
      open(detail({ canCopyMedicines: false, canRepeatTests: false, copyDisabledReason: 'RECORD_SIGNED' }));
      expect(q<HTMLButtonElement>('[data-act=copy-medicines]')!.disabled).toBe(true);
      expect(q<HTMLButtonElement>('[data-act=repeat-tests]')!.disabled).toBe(true);
      expect(q('[data-testid=signed-note]')!.textContent).toBe('This visit is signed');
    });

    it('shows a message when the copy did not work', () => {
      boot(page([opdVisit]));
      open();
      copyItems.mockReturnValueOnce(of(null));
      q<HTMLButtonElement>('[data-act=copy-medicines]')!.click();
      fixture.detectChanges();
      expect(q('[data-testid=history-toast]')!.textContent).toBe('We could not copy. Try again.');
    });

    it('opens the full visit of an OPD row, and the discharge summary of an IPD row, in a new tab', () => {
      const open$ = vi.spyOn(window, 'open').mockReturnValue(null);
      boot(page([opdVisit, ipdVisit]));
      open();
      expect(q('[data-act=open-full]')!.textContent!.trim()).toBe('Open full visit');
      q<HTMLButtonElement>('[data-act=open-full]')!.click();
      expect(open$).toHaveBeenCalledWith('/app/emr/r1/past/p1', '_blank', 'noopener');
      qa('[data-act=toggle-visit]')[1].click();
      http.expectOne(`${EMR}/past-visits/IPD/a1`).flush(detail({ kind: 'IPD', id: 'a1', open: { target: 'ADMISSION', id: 'a1' } }));
      fixture.detectChanges();
      const second = qa('[data-act=open-full]').at(-1)!;
      expect(second.textContent!.trim()).toBe('Open discharge summary');
    });

    it('shows an error when the details cannot be opened', () => {
      boot(page([opdVisit]));
      q<HTMLButtonElement>('[data-act=toggle-visit]')!.click();
      http.expectOne(`${EMR}/past-visits/OPD/p1`).flush({}, { status: 500, statusText: 'Server Error' });
      fixture.detectChanges();
      expect(q('.detail .err-text')!.textContent).toContain('We could not open the details');
    });
  });
});
