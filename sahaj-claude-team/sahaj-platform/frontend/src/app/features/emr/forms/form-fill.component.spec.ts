import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EmrFormDefinition } from '../emr-phase3.model';
import { FormFieldChange, FormFillComponent } from './form-fill.component';
import { ff, footDefinition } from './form.fixtures';
import { FormValues } from './form.logic';

describe('FormFillComponent (SAH-59-1 to SAH-59-3, SAH-59-5)', () => {
  let fixture: ComponentFixture<FormFillComponent>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let changes: FormFieldChange[];
  let values: FormValues;

  function render(def: EmrFormDefinition = footDefinition(), initial: FormValues = {}, extra: { disabled?: boolean; missing?: string[]; errors?: Record<string, string> } = {}): void {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(FormFillComponent);
    values = initial;
    fixture.componentRef.setInput('definition', def);
    fixture.componentRef.setInput('values', values);
    fixture.componentRef.setInput('idPrefix', 'f-test');
    fixture.componentRef.setInput('disabled', extra.disabled ?? false);
    fixture.componentRef.setInput('requiredMissing', extra.missing ?? []);
    fixture.componentRef.setInput('errors', extra.errors ?? {});
    changes = [];
    fixture.componentInstance.fieldChange.subscribe((c) => {
      changes.push(c);
      values = { ...values, [c.key]: c.value };
      fixture.componentRef.setInput('values', values);
      fixture.detectChanges();
    });
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  const q = <T extends Element = HTMLElement>(sel: string) => el.querySelector<T>(sel);
  const qa = (sel: string) => Array.from(el.querySelectorAll<HTMLElement>(sel));
  const cell = (key: string) => q(`[data-field=${key}]`);
  const press = (key: string, label: string) =>
    Array.from(cell(key)!.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent!.trim().replace('✓ ', '') === label)!;

  afterEach(() => http?.verify());

  it('draws the fields on a six-column grid, by their width', () => {
    render(footDefinition({ fields: [ff({ key: 'a', type: 'SHORT_TEXT', width: 3 }), ff({ key: 'b', type: 'SHORT_TEXT', width: 6 }), ff({ key: 'c', type: 'SHORT_TEXT', width: 2 })] }));
    expect((cell('a') as HTMLElement).style.gridColumn).toContain('span 3');
    expect((cell('b') as HTMLElement).style.gridColumn).toContain('span 6');
    expect((cell('c') as HTMLElement).style.gridColumn).toContain('span 2');
  });

  it('shows the label with a red star, the unit, the code and the help text', () => {
    render();
    const mono = cell('mono')!;
    expect(mono.querySelector('.name')!.textContent).toContain('Monofilament: sites felt');
    expect(mono.querySelector('.star')).not.toBeNull();
    expect(mono.textContent).toContain('of 10');
    expect(mono.textContent).toContain('LOINC 32443-7');
  });

  describe('basic fields', () => {
    it('fills text, long text, date and time and clears them to null', () => {
      render(footDefinition({ fields: [ff({ key: 't', type: 'SHORT_TEXT' }), ff({ key: 'l', type: 'LONG_TEXT' }), ff({ key: 'd', type: 'DATE' }), ff({ key: 'h', type: 'TIME' })] }));
      for (const [key, tag, text] of [['t', 'input', 'abc'], ['l', 'textarea', 'long'], ['d', 'input', '2026-11-12'], ['h', 'input', '10:30']] as const) {
        const input = cell(key)!.querySelector<HTMLInputElement>(tag)!;
        input.value = text;
        input.dispatchEvent(new Event('input'));
      }
      expect(changes.map((c) => c.value)).toEqual(['abc', 'long', '2026-11-12', '10:30']);
      const t = cell('t')!.querySelector<HTMLInputElement>('input')!;
      t.value = '';
      t.dispatchEvent(new Event('input'));
      expect(changes[4]).toEqual({ key: 't', value: null });
    });

    it('stores a number as a number', () => {
      render();
      const input = cell('mono')!.querySelector<HTMLInputElement>('input')!;
      input.value = '9';
      input.dispatchEvent(new Event('input'));
      expect(changes[0]).toEqual({ key: 'mono', value: 9 });
    });

    it('stores Yes / No as a boolean and a second tap clears it', () => {
      render();
      press('ulcer', 'Yes').click();
      press('ulcer', 'No').click();
      press('ulcer', 'No').click();
      expect(changes.map((c) => c.value)).toEqual([true, false, null]);
    });

    it('picks one option for a single choice and several for a multiple choice', () => {
      render();
      press('vib', 'Reduced').click();
      press('vib', 'Absent').click();
      press('pulses', 'DP right').click();
      press('pulses', 'DP left').click();
      press('pulses', 'DP right').click();
      expect(changes.map((c) => c.value)).toEqual(['reduced', 'absent', ['dpr'], ['dpr', 'dpl'], ['dpl']]);
    });

    it('picks from a dropdown', () => {
      render(footDefinition({ fields: [ff({ key: 'dd', type: 'DROPDOWN', options: [{ key: 'a', label: 'A', score: 0 }, { key: 'b', label: 'B', score: 0 }] })] }));
      const select = cell('dd')!.querySelector<HTMLSelectElement>('select')!;
      select.value = 'b';
      select.dispatchEvent(new Event('change'));
      expect(changes[0]).toEqual({ key: 'dd', value: 'b' });
    });

    it('fills the vitals block as one value', () => {
      render(footDefinition({ fields: [ff({ key: 'v', type: 'VITALS_BLOCK' })] }));
      const inputs = cell('v')!.querySelectorAll<HTMLInputElement>('input');
      expect(inputs).toHaveLength(7);
      inputs[0].value = '142';
      inputs[0].dispatchEvent(new Event('input'));
      inputs[1].value = '90';
      inputs[1].dispatchEvent(new Event('input'));
      expect(changes[1].value).toEqual({ bpSystolic: 142, bpDiastolic: 90 });
    });
  });

  describe('show-if (SAH-59-2)', () => {
    it('shows a dependent field only when its source has the value', () => {
      render();
      expect(cell('site')).toBeNull();
      press('now', 'Yes').click();
      expect(cell('site')).not.toBeNull();
      press('now', 'No').click();
      expect(cell('site')).toBeNull();
    });
  });

  describe('scores, bands and ranges (SAH-59-3)', () => {
    it('shows the total blank until a source is answered, then live', () => {
      render();
      expect(q('[data-testid=score-total]')!.textContent).toBe('—');
      press('ulcer', 'Yes').click();
      expect(q('[data-testid=score-total]')!.textContent).toBe('2');
      press('vib', 'Reduced').click();
      expect(q('[data-testid=score-total]')!.textContent).toBe('3');
    });

    it('shows the band with its label and a tone that also has text', () => {
      render();
      press('ulcer', 'No').click();
      const band = q('.calc.band')!;
      expect(q('[data-testid=band-label]')!.textContent).toBe('Low risk: check every year');
      expect(band.classList).toContain('good');
      press('ulcer', 'Yes').click();
      press('vib', 'Reduced').click();
      expect(q('.calc.band')!.classList).toContain('crit');
      expect(q('[data-testid=band-label]')!.textContent).toBe('High: check every 1–3 months, refer');
    });

    it('flags a value inside the limits but outside the normal range as Abnormal, in red, with the word', () => {
      render(footDefinition(), { mono: 6 });
      expect(cell('mono')!.querySelector('[data-testid=abnormal]')!.textContent).toBe('Abnormal');
      expect(cell('mono')!.querySelector('.pill')!.classList).toContain('p-crit');
    });

    it('shows "Outside 0–10" for a value beyond the limits', () => {
      render(footDefinition(), { mono: 14 });
      expect(cell('mono')!.querySelector('.err-text')!.textContent).toBe('Outside 0–10');
    });

    it('marks a required field that is missing, after a save was tried', () => {
      render(footDefinition(), {}, { missing: ['ulcer', 'mono'] });
      expect(cell('ulcer')!.querySelector('.err-text')!.textContent).toBe('Required');
      expect(cell('vib')!.querySelector('.err-text')).toBeNull();
    });

    it('shows a message the server gave for a field', () => {
      render(footDefinition(), { mono: 5 }, { errors: { mono: 'Outside 0–10' } });
      expect(cell('mono')!.querySelector('.err-text')!.textContent).toBe('Outside 0–10');
    });
  });

  describe('layout and "Later" types', () => {
    it('draws a section heading and an info note', () => {
      render(footDefinition({ fields: [ff({ key: 'h', type: 'SECTION_HEADING', label: 'Examination', help: 'Both feet' }), ff({ key: 'n', type: 'INFO_NOTE', label: 'Not fasting: postpone unless emergency.' })] }));
      expect(q('h4')!.textContent).toBe('Examination');
      expect(q('.note')!.textContent).toBe('Not fasting: postpone unless emergency.');
    });

    it('shows signature, photo, file and handwriting disabled with a "Later" note and stores nothing', () => {
      render(footDefinition({ fields: [ff({ key: 's', type: 'SIGNATURE' }), ff({ key: 'p', type: 'PHOTO' }), ff({ key: 'f', type: 'FILE_UPLOAD' }), ff({ key: 'w', type: 'HANDWRITING_PAD' }), ff({ key: 't', type: 'TOOTH_CHART' })] }));
      expect(qa('.later button').every((b) => (b as HTMLButtonElement).disabled)).toBe(true);
      expect(qa('.later .pill').map((p) => p.textContent)).toEqual(['Later', 'Later', 'Later', 'Later', 'Later']);
      qa('.later button')[0].click();
      expect(changes).toHaveLength(0);
    });
  });

  describe('the shared components inside a form', () => {
    it('uses the body map, the medicine list, the tests list and the diagnosis list', () => {
      render(footDefinition({ fields: [ff({ key: 'b', type: 'BODY_MAP', label: 'Injuries' }), ff({ key: 'm', type: 'MEDICINE_LIST', label: 'Medicines' }), ff({ key: 'l', type: 'LAB_TESTS', label: 'Tests needed' }), ff({ key: 'd', type: 'DIAGNOSIS', label: 'Diagnosis' })] }));
      expect(q('app-body-map')).not.toBeNull();
      expect(q('app-prescription')).not.toBeNull();
      expect(qa('app-emr-list').map((e) => e.querySelector('h3')!.textContent!.trim())).toEqual(['Tests needed', 'Diagnosis']);
      http.expectOne('/api/v1/opd/emr/medicine-favourites').flush([]);
      http.expectOne((r) => r.url === '/api/v1/opd/emr/quick-options/investigations').flush({ master: 'investigations', items: [] });
      http.expectOne((r) => r.url === '/api/v1/opd/emr/quick-options/diagnoses').flush({ master: 'diagnoses', items: [] });
    });
  });

  describe('read-only', () => {
    it('switches every control off and sends nothing', () => {
      render(footDefinition(), { ulcer: true }, { disabled: true });
      expect(qa('input, textarea, select, .pick').every((c) => (c as HTMLInputElement).disabled)).toBe(true);
      press('vib', 'Reduced').click();
      expect(changes).toHaveLength(0);
    });
  });
});
