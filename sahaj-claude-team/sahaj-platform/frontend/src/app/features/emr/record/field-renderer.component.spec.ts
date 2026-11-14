import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { field } from '../emr.fixtures';
import { EmrFieldDefinition } from '../emr.model';
import { FieldRendererComponent } from './field-renderer.component';

describe('FieldRendererComponent (SAH-47-3)', () => {
  let fixture: ComponentFixture<FieldRendererComponent>;
  let emitted: unknown[];
  let el: HTMLElement;

  function render(def: EmrFieldDefinition, value: unknown = null, disabled = false, error: string | null = null): HTMLElement {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    fixture = TestBed.createComponent(FieldRendererComponent);
    fixture.componentRef.setInput('field', def);
    fixture.componentRef.setInput('value', value);
    fixture.componentRef.setInput('disabled', disabled);
    fixture.componentRef.setInput('error', error);
    fixture.componentRef.setInput('idPrefix', 'f-test');
    emitted = [];
    fixture.componentInstance.valueChange.subscribe((v) => emitted.push(v));
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
    return el;
  }

  const input = (sel = 'input') => el.querySelector<HTMLInputElement>(sel)!;

  function type(target: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement, text: string, event = 'input'): void {
    target.value = text;
    target.dispatchEvent(new Event(event));
  }

  it('shows the label, the meta text and a required marker with a spoken word', () => {
    render(field({ key: 'a', label: 'Complaint', meta: 'SNOMED CT or clinic list', required: true }));
    expect(el.querySelector('.name')!.textContent).toContain('Complaint');
    expect(el.querySelector('.meta')!.textContent).toBe('SNOMED CT or clinic list');
    expect(el.querySelector('.star')).not.toBeNull();
    expect(el.querySelector('.sr-only')!.textContent).toContain('required');
  });

  describe('text', () => {
    it('emits the text, and null when it is cleared', () => {
      render(field({ key: 't', valueKind: 'TEXT' }), 'abc');
      expect(input().value).toBe('abc');
      type(input(), 'abcd');
      type(input(), '');
      expect(emitted).toEqual(['abcd', null]);
    });

    it('is a text area for rich text', () => {
      render(field({ key: 't', type: 'RICH_TEXT', valueKind: 'TEXT' }));
      expect(el.querySelector('textarea')).not.toBeNull();
    });

    it('is switched off on a read-only record', () => {
      render(field({ key: 't', valueKind: 'TEXT' }), 'abc', true);
      expect(input().disabled).toBe(true);
    });
  });

  describe('numbers', () => {
    it('emits a number, and null when cleared', () => {
      render(field({ key: 'n', type: 'NUMBER', valueKind: 'NUMBER' }), 120);
      expect(input().value).toBe('120');
      type(input(), '98.6');
      type(input(), '');
      expect(emitted).toEqual([98.6, null]);
    });

    it('flags a vital outside the limits with text as well as colour', () => {
      render(field({ key: 'bp_systolic', type: 'NUMBER', valueKind: 'NUMBER' }), 150);
      const flag = el.querySelector('[data-testid=vital-flag]')!;
      expect(flag.textContent).toBe('High');
      expect(flag.classList).toContain('p-crit');
    });

    it('flags nothing inside the limits', () => {
      render(field({ key: 'bp_systolic', type: 'NUMBER', valueKind: 'NUMBER' }), 120);
      expect(el.querySelector('[data-testid=vital-flag]')).toBeNull();
    });
  });

  describe('number with unit', () => {
    it('emits the number with its unit', () => {
      render(field({ key: 'd', type: 'NUMBER_UNIT', valueKind: 'NUMBER_UNIT' }), { value: 3, unit: 'days' });
      const inputs = el.querySelectorAll<HTMLInputElement>('input');
      expect(inputs[0].value).toBe('3');
      expect(inputs[1].value).toBe('days');
      type(inputs[0], '4');
      type(inputs[1], 'weeks');
      expect(emitted).toEqual([{ value: 4, unit: 'days' }, { value: 3, unit: 'weeks' }]);
    });

    it('offers the unit as a list when the field has options', () => {
      render(
        field({
          key: 'd', type: 'NUMBER_UNIT', valueKind: 'NUMBER_UNIT', optionsSource: 'FIELD',
          options: [{ key: 'days', label: 'days', isNone: false }, { key: 'weeks', label: 'weeks', isNone: false }],
        }),
      );
      expect(el.querySelectorAll('select option')).toHaveLength(3);
    });

    it('emits null when both parts are emptied', () => {
      render(field({ key: 'd', type: 'NUMBER_UNIT', valueKind: 'NUMBER_UNIT' }), { value: 3, unit: '' });
      type(el.querySelectorAll<HTMLInputElement>('input')[0], '');
      expect(emitted).toEqual([null]);
    });
  });

  describe('choices', () => {
    const severity = field({
      key: 's', type: 'DROPDOWN', valueKind: 'CHOICE', optionsSource: 'FIELD',
      options: [{ key: 'mild', label: 'Mild', isNone: false }, { key: 'severe', label: 'Severe', isNone: false }],
    });

    it('picks one option key from a list', () => {
      render(severity, 'mild');
      const select = input('select') as unknown as HTMLSelectElement;
      expect(select.querySelectorAll('option')).toHaveLength(3);
      type(select, 'severe', 'change');
      type(select, '', 'change');
      expect(emitted).toEqual(['severe', null]);
    });

    it('takes free text when the field has no options', () => {
      render(field({ key: 'c', valueKind: 'CHOICE' }), 'x');
      type(input(), 'free');
      expect(emitted).toEqual(['free']);
    });

    it('picks several options', () => {
      render(
        field({
          key: 'm', type: 'MULTI_SELECT', valueKind: 'MULTI_CHOICE', optionsSource: 'FIELD',
          options: [{ key: 'dm', label: 'DM', isNone: false }, { key: 'htn', label: 'HTN', isNone: false }],
        }),
        ['dm'],
      );
      const picks = Array.from(el.querySelectorAll<HTMLButtonElement>('.pick'));
      expect(picks.map((p) => p.getAttribute('aria-pressed'))).toEqual(['true', 'false']);
      picks[1].click();
      picks[0].click();
      expect(emitted).toEqual([['dm', 'htn'], []]);
    });

    it('takes a comma list when several choices have no options', () => {
      render(field({ key: 'm', valueKind: 'MULTI_CHOICE' }));
      type(input(), 'DM, HTN, ', 'change');
      expect(emitted).toEqual([['DM', 'HTN']]);
    });
  });

  describe('yes or no, date, structured', () => {
    it('toggles yes or no with a switch named by the field', () => {
      render(field({ key: 'b', label: 'Consent to share', type: 'TOGGLE', valueKind: 'BOOLEAN' }), false);
      const sw = el.querySelector<HTMLButtonElement>('[role=switch]')!;
      expect(sw.getAttribute('aria-label')).toBe('Consent to share');
      expect(sw.getAttribute('aria-checked')).toBe('false');
      sw.click();
      expect(emitted).toEqual([true]);
    });

    it('takes a date', () => {
      render(field({ key: 'd', type: 'DATE', valueKind: 'DATE' }), '2026-11-12');
      expect(input().type).toBe('date');
      type(input(), '2026-12-01');
      expect(emitted).toEqual(['2026-12-01']);
    });

    it('takes plain text for a structured value', () => {
      render(field({ key: 's', type: 'BODY_MAP', valueKind: 'STRUCTURED' }), { text: 'left knee' });
      const area = el.querySelector('textarea')!;
      expect(area.value).toBe('left knee');
      type(area, 'right knee');
      type(area, '');
      expect(emitted).toEqual([{ text: 'right knee' }, null]);
    });
  });

  describe('table', () => {
    const table = field({
      key: 'tb', type: 'TABLE', valueKind: 'TABLE',
      columns: [{ key: 'procedure', label: 'Procedure' }, { key: 'year', label: 'Year' }],
    });

    it('edits cells, adds and removes rows', () => {
      render(table, [{ procedure: 'Appendectomy', year: '2019' }]);
      const cells = el.querySelectorAll<HTMLInputElement>('.trow input');
      expect(cells[0].value).toBe('Appendectomy');
      type(cells[1], '2020');
      (el.querySelector('.addrow') as HTMLButtonElement).click();
      (el.querySelector('.trow .xbtn') as HTMLButtonElement).click();
      expect(emitted).toEqual([[{ procedure: 'Appendectomy', year: '2020' }], [{ procedure: 'Appendectomy', year: '2019' }, {}], []]);
    });

    it('uses one Entry column when the library gives none', () => {
      render(field({ key: 'tb', valueKind: 'TABLE' }), [{ entry: 'a' }]);
      expect(el.querySelectorAll('.trow input')).toHaveLength(1);
    });

    it('cannot be edited on a read-only record', () => {
      render(table, [], true);
      expect(el.querySelector('.addrow')).toBeNull();
    });
  });

  describe('not fillable', () => {
    it('shows a display-only field as information', () => {
      render(field({ key: 'i', type: 'REFERENCE', valueKind: 'DISPLAY_ONLY', meta: 'Auto by joint' }));
      expect(el.textContent).toContain('Nothing to fill in');
      expect(el.querySelector('input')).toBeNull();
    });

    it('shows a photo, file or signature disabled with a Later note and stores nothing', () => {
      render(field({ key: 'f', label: 'Files', type: 'FILE', valueKind: 'LATER' }));
      expect(el.querySelector<HTMLButtonElement>('.later button')!.disabled).toBe(true);
      expect(el.querySelector('.later .pill')!.textContent).toBe('Later');
      expect(emitted).toEqual([]);
    });

    it('never fails on a kind it does not know', () => {
      render({ ...field({ key: 'z' }), valueKind: 'HOLOGRAM' as never });
      expect(el.textContent).toContain('Not available yet');
    });
  });

  it('shows an error under the field and marks it invalid', () => {
    render(field({ key: 'n', type: 'NUMBER', valueKind: 'NUMBER' }), 5000, false, 'Enter a number from 0 to 300.');
    expect(el.querySelector('.err-text')!.textContent).toContain('Enter a number from 0 to 300.');
    expect(input().getAttribute('aria-invalid')).toBe('true');
  });
});

describe('FieldRendererComponent Search list', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<FieldRendererComponent>;
  let emitted: unknown[];
  let el: HTMLElement;

  const complaint = field({
    key: 'complaint', label: 'Complaint', type: 'SEARCH_LIST', valueKind: 'ITEM_LIST',
    optionsSource: 'MASTER', master: 'complaints', allowFreeText: true,
  });

  function render(def: EmrFieldDefinition, value: unknown = []): void {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(FieldRendererComponent);
    fixture.componentRef.setInput('field', def);
    fixture.componentRef.setInput('value', value);
    fixture.componentRef.setInput('idPrefix', 'f-test');
    fixture.componentRef.setInput('specialty', 'gm');
    emitted = [];
    fixture.componentInstance.valueChange.subscribe((v) => emitted.push(v));
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  async function search(text: string): Promise<void> {
    vi.useFakeTimers();
    const box = el.querySelector<HTMLInputElement>('input[type=search]')!;
    box.value = text;
    box.dispatchEvent(new Event('input'));
    await vi.advanceTimersByTimeAsync(300);
    vi.useRealTimers();
    fixture.detectChanges();
  }

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  it('offers the doctor quick options and adds one with a tap', () => {
    render(complaint);
    http.expectOne((r) => r.url === '/api/v1/opd/emr/quick-options/complaints').flush({
      master: 'complaints',
      items: [{ itemKey: 'k1', name: 'Fever', icd10Code: null, form: null, favourite: null, useCount: 2, lastUsedAt: null }],
    });
    fixture.detectChanges();
    const chip = el.querySelector<HTMLButtonElement>('.qchip')!;
    expect(chip.textContent).toContain('+ Fever');
    chip.click();
    expect(emitted).toEqual([[{ itemKey: 'k1', text: 'Fever', newToList: false }]]);
  });

  it('searches the master with the text and the department, then adds the pick', async () => {
    render(complaint);
    http.expectOne((r) => r.url === '/api/v1/opd/emr/quick-options/complaints').flush({ master: 'complaints', items: [] });
    await search('fev');
    const req = http.expectOne((r) => r.url === '/api/v1/opd/emr/masters/complaints');
    expect(req.request.params.get('q')).toBe('fev');
    expect(req.request.params.get('specialty')).toBe('gm');
    req.flush({ items: [{ id: 'i1', name: 'Fever', specialtyHint: null }], total: 1, page: 0, pageSize: 20 });
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('.res')!.click();
    expect(emitted).toEqual([[{ itemKey: 'i1', text: 'Fever', newToList: false }]]);
  });

  it('adds typed text as "New to list" when the field allows it', async () => {
    render(complaint);
    http.expectOne((r) => r.url === '/api/v1/opd/emr/quick-options/complaints').flush({ master: 'complaints', items: [] });
    await search('knee click');
    http.expectOne((r) => r.url === '/api/v1/opd/emr/masters/complaints').flush({ items: [], total: 0, page: 0, pageSize: 20 });
    fixture.detectChanges();
    const add = el.querySelector<HTMLButtonElement>('.addbtn')!;
    expect(add.textContent).toContain('knee click');
    add.click();
    expect(emitted).toEqual([[{ itemKey: null, text: 'knee click', newToList: true }]]);
  });

  it('does not offer typed text when the field is closed to the list', async () => {
    render({ ...complaint, allowFreeText: false });
    http.expectOne((r) => r.url === '/api/v1/opd/emr/quick-options/complaints').flush({ master: 'complaints', items: [] });
    await search('zzz');
    http.expectOne((r) => r.url === '/api/v1/opd/emr/masters/complaints').flush({ items: [], total: 0, page: 0, pageSize: 20 });
    fixture.detectChanges();
    expect(el.querySelector('.addbtn')).toBeNull();
    expect(el.textContent).toContain('No match. Pick one from the list.');
  });

  it('shows what is chosen, tags new text, and removes an entry', () => {
    render(complaint, [
      { itemKey: 'i1', text: 'Fever', newToList: false },
      { itemKey: null, text: 'Odd thing', newToList: true },
    ]);
    http.expectOne((r) => r.url === '/api/v1/opd/emr/quick-options/complaints').flush({ master: 'complaints', items: [] });
    fixture.detectChanges();
    expect(el.querySelectorAll('.chosen .chip')).toHaveLength(2);
    expect(el.querySelector('.chosen')!.textContent).toContain('New to list');
    el.querySelector<HTMLButtonElement>('.rm')!.click();
    expect(emitted).toEqual([[{ itemKey: null, text: 'Odd thing', newToList: true }]]);
  });

  it('reads diagnoses from the ICD-10 list and gives the code as the item key', async () => {
    render({ ...complaint, key: 'dx', master: 'diagnoses', allowFreeText: false });
    http.expectOne((r) => r.url === '/api/v1/opd/emr/quick-options/diagnoses').flush({ master: 'diagnoses', items: [] });
    await search('upper');
    http.expectOne((r) => r.url === '/api/v1/opd/diagnoses').flush([{ code: 'J06.9', name: 'Acute upper respiratory infection' }]);
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('.res')!.click();
    expect(emitted).toEqual([[{ itemKey: 'J06.9', text: 'Acute upper respiratory infection', newToList: false }]]);
  });

  it('says so when the search fails and keeps what is chosen', async () => {
    render(complaint, [{ itemKey: 'i1', text: 'Fever', newToList: false }]);
    http.expectOne((r) => r.url === '/api/v1/opd/emr/quick-options/complaints').flush({ master: 'complaints', items: [] });
    await search('cough');
    http.expectOne((r) => r.url === '/api/v1/opd/emr/masters/complaints').error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(el.textContent).toContain('We could not search. Try again.');
    expect(el.querySelectorAll('.chosen .chip')).toHaveLength(1);
  });

  it('searches the active services from the price list', async () => {
    render({ ...complaint, key: 'svc', optionsSource: 'SERVICES', master: null });
    await search('dress');
    http.expectOne((r) => r.url === '/api/v1/opd/services').flush([
      { id: 's1', name: 'Dressing', category: 'Procedure', priceRupees: 100 },
      { id: 's2', name: 'ECG', category: 'Lab', priceRupees: 200 },
    ]);
    fixture.detectChanges();
    expect(el.querySelectorAll('.res')).toHaveLength(1);
    el.querySelector<HTMLButtonElement>('.res')!.click();
    expect(emitted).toEqual([[{ itemKey: 's1', text: 'Dressing', newToList: false }]]);
  });

  it('offers the field options as quick choices when the field has its own list', () => {
    render(
      field({
        key: 'x', type: 'SEARCH_LIST', valueKind: 'ITEM_LIST', optionsSource: 'FIELD',
        options: [{ key: 'a', label: 'Option A', isNone: false }],
      }),
    );
    expect(el.querySelector('.qchip')!.textContent).toContain('Option A');
    expect(el.querySelector('input[type=search]')).toBeNull();
  });

  it('shows no editing controls on a read-only record', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(FieldRendererComponent);
    fixture.componentRef.setInput('field', complaint);
    fixture.componentRef.setInput('value', [{ itemKey: 'i1', text: 'Fever', newToList: false }]);
    fixture.componentRef.setInput('disabled', true);
    fixture.componentRef.setInput('idPrefix', 'f-test');
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('input[type=search]')).toBeNull();
    expect(el.querySelector('.rm')).toBeNull();
    expect(el.textContent).toContain('Fever');
  });

  it('offers the drug allergy classes as chips in the Allergies section (SAH-48-2)', () => {
    render(field({ key: 'drug_allergy', label: 'Drug allergy', type: 'SEARCH_LIST', valueKind: 'ITEM_LIST', allowFreeText: true }));
    http.expectOne('/api/v1/opd/emr/allergy-classes').flush([
      { classKey: 'SULFA', label: 'Sulfa drugs' },
      { classKey: 'PENICILLIN', label: 'Penicillin' },
    ]);
    fixture.detectChanges();
    const chips = Array.from(el.querySelectorAll<HTMLButtonElement>('.qchip'));
    expect(chips.map((c) => c.textContent!.trim())).toEqual(['+ Sulfa drugs', '+ Penicillin']);
    chips[0].click();
    expect(emitted).toEqual([[{ itemKey: 'SULFA', text: 'Sulfa drugs', newToList: false }]]);
  });
});

describe('FieldRendererComponent shared components (ADR-022 D2)', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<FieldRendererComponent>;
  let el: HTMLElement;

  function render(def: EmrFieldDefinition, value: unknown = null): void {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(FieldRendererComponent);
    fixture.componentRef.setInput('field', def);
    fixture.componentRef.setInput('value', value);
    fixture.componentRef.setInput('idPrefix', 'f-test');
    fixture.componentRef.setInput('specialty', 'gm');
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  it('draws an EMR list for a field bound to it, with its own title and no second label', () => {
    render(
      field({ key: 'complaint', label: 'Chief complaints', type: 'SEARCH_LIST', valueKind: 'COMPONENT', required: true, component: { type: 'EMR_LIST', listKind: 'COMPLAINTS' } }),
    );
    http.expectOne((r) => r.url === '/api/v1/opd/emr/quick-options/complaints').flush({ master: 'complaints', items: [] });
    expect(el.querySelector('app-emr-list')).not.toBeNull();
    expect(el.querySelector('.fr > .lbl')).toBeNull();
    expect(el.querySelector('h3')!.textContent).toContain('Chief complaints');
  });

  it('draws the prescription for a medicine field', () => {
    render(field({ key: 'drug', label: 'Prescription (Rx)', type: 'SEARCH_LIST', valueKind: 'COMPONENT', component: { type: 'PRESCRIPTION', listKind: null } }));
    http.expectOne('/api/v1/opd/emr/medicine-favourites').flush([]);
    expect(el.querySelector('app-prescription')).not.toBeNull();
  });

  it('draws the body map for a body map field', () => {
    render(field({ key: 'map', label: 'Local examination', type: 'BODY_MAP', valueKind: 'COMPONENT', component: { type: 'BODY_MAP', listKind: null } }));
    expect(el.querySelector('app-body-map')).not.toBeNull();
  });

  it('draws nothing for a covered sibling field and shows "Not available yet" for an unknown component', () => {
    render(field({ key: 'duration', label: 'Duration', type: 'NUMBER_UNIT', valueKind: 'COVERED' }));
    expect(el.querySelector('input, select, textarea')).toBeNull();
    TestBed.resetTestingModule();
    render(field({ key: 'x', label: 'X', type: 'LIBRARY', valueKind: 'COMPONENT', component: null }));
    expect(el.textContent).toContain('Not available yet');
  });
});
