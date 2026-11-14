import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EMR_COMPONENT_CONTEXT, EmrComponentContext } from '../emr-context';
import { EmrListKind } from '../emr.model';
import { EmrListItem, EmrListValue, PrintLanguage } from '../emr-phase3.model';
import { EmrListComponent } from './emr-list.component';

const EMR = '/api/v1/opd/emr';

function item(over: Partial<EmrListItem> = {}): EmrListItem {
  return { id: 'i1', itemKey: 'k1', text: 'Fever', newToList: false, attr1: 'D3', attr2: 'MODERATE', active: true, ...over };
}

function value(kind: EmrListKind, items: EmrListItem[]): EmrListValue {
  return { component: 'EMR_LIST', kind, schemaVersion: 1, items };
}

describe('EmrListComponent (SAH-56)', () => {
  let fixture: ComponentFixture<EmrListComponent>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let emitted: EmrListValue[];
  let language: ReturnType<typeof signal<PrintLanguage>>;
  let setLanguage: ReturnType<typeof vi.fn>;

  interface Options {
    items?: EmrListItem[];
    required?: boolean;
    disabled?: boolean;
    withContext?: boolean;
    quick?: { itemKey: string; name: string; icd10Code: string | null; form: string | null; favourite: boolean | null; useCount: number; lastUsedAt: string | null }[];
    quickFails?: boolean;
  }

  function render(kind: EmrListKind, opts: Options = {}): void {
    language = signal<PrintLanguage>('EN');
    setLanguage = vi.fn();
    const ctx = { printLanguage: language, setPrintLanguage: setLanguage } as unknown as EmrComponentContext;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        ...(opts.withContext ? [{ provide: EMR_COMPONENT_CONTEXT, useValue: ctx }] : []),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EmrListComponent);
    fixture.componentRef.setInput('kind', kind);
    fixture.componentRef.setInput('title', 'Chief complaints');
    fixture.componentRef.setInput('required', opts.required ?? false);
    fixture.componentRef.setInput('value', opts.items ? value(kind, opts.items) : null);
    fixture.componentRef.setInput('disabled', opts.disabled ?? false);
    fixture.componentRef.setInput('specialty', 'gm');
    fixture.componentRef.setInput('inputId', 'el-test');
    emitted = [];
    fixture.componentInstance.valueChange.subscribe((v) => {
      emitted.push(v);
      fixture.componentRef.setInput('value', v);
      fixture.detectChanges();
    });
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
    if (!opts.disabled) {
      if (kind === 'SERVICES') {
        const req = http.expectOne((r) => r.url === '/api/v1/opd/services');
        req.flush([
          { id: 's1', name: 'ECG', category: 'Tests', priceRupees: 300, active: true },
          { id: 's2', name: 'Dressing', category: 'Procedures', priceRupees: 125000, active: true },
        ]);
      } else {
        const master = { COMPLAINTS: 'complaints', DIAGNOSIS: 'diagnoses', INVESTIGATIONS: 'investigations', ADVICE: 'advice' }[kind];
        const req = http.expectOne((r) => r.url === `${EMR}/quick-options/${master}`);
        expect(req.request.params.get('limit')).toBe('7');
        expect(req.request.params.get('specialty')).toBe('gm');
        if (opts.quickFails) {
          req.flush({}, { status: 500, statusText: 'Server Error' });
        } else {
          req.flush({ master, items: opts.quick ?? [] });
        }
      }
      fixture.detectChanges();
    }
  }

  const q = <T extends Element = HTMLElement>(sel: string) => el.querySelector<T>(sel);
  const qa = (sel: string) => Array.from(el.querySelectorAll<HTMLElement>(sel));
  const quick = (id: string, name: string, extra: object = {}) => ({ itemKey: id, name, icd10Code: null, form: null, favourite: null, useCount: 0, lastUsedAt: null, ...extra });

  async function type(text: string): Promise<void> {
    const input = q<HTMLInputElement>('input[type=search]')!;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    vi.advanceTimersByTime(300);
    fixture.detectChanges();
  }

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  describe('header and states', () => {
    it('shows the title with a red star when required, the count and the two views', () => {
      render('COMPLAINTS', { required: true });
      expect(q('h3')!.textContent).toContain('Chief complaints');
      expect(q('.star')).not.toBeNull();
      expect(q('.sr-only')!.textContent).toContain('required');
      expect(q('[data-testid=list-count]')!.textContent).toBe('0');
      expect(qa('[role=tab]').map((t) => t.textContent!.trim())).toEqual(['Chips', 'Table']);
    });

    it('shows the empty text of the kind, in red when the section is required', () => {
      render('COMPLAINTS', { required: true });
      expect(q('[data-testid=list-empty]')!.textContent).toBe('No complaint added yet. Tap a most-used one below or + Add.');
      expect(q('[data-testid=list-empty]')!.classList).toContain('bad');
    });

    it('shows the diagnosis empty text even when the section is optional', () => {
      render('DIAGNOSIS');
      expect(q('[data-testid=list-empty]')!.textContent).toBe('No diagnosis yet. It is needed to complete the visit.');
    });

    it('counts "n active · m disabled" when some items are switched off', () => {
      render('COMPLAINTS', { items: [item(), item({ id: 'i2', text: 'Cough', active: false })] });
      expect(q('[data-testid=list-count]')!.textContent).toBe('1 active · 1 disabled');
    });
  });

  describe('Your most used (SAH-56-1)', () => {
    it('shows up to 7 chips from the quick options and adds one at a tap, then the chip leaves the row', () => {
      render('COMPLAINTS', { quick: [quick('k1', 'Fever', { useCount: 142 }), quick('k2', 'Cough')] });
      expect(qa('[data-act=add-quick]').map((b) => b.getAttribute('aria-label'))).toEqual(['Add Fever', 'Add Cough']);
      expect(qa('[data-act=add-quick]')[0].textContent).toContain('· 142');
      qa('[data-act=add-quick]')[0].click();
      fixture.detectChanges();
      expect(emitted).toHaveLength(1);
      expect(emitted[0].items[0]).toMatchObject({ itemKey: 'k1', text: 'Fever', newToList: false, attr1: 'D3', attr2: 'MODERATE', active: true });
      expect(qa('[data-act=add-quick]').map((b) => b.getAttribute('aria-label'))).toEqual(['Add Cough']);
      expect(q('[data-testid=list-toast]')!.textContent).toContain('Fever added');
    });

    it('takes the item back out when Undo is tapped while the message shows', () => {
      render('COMPLAINTS', { quick: [quick('k1', 'Fever')] });
      qa('[data-act=add-quick]')[0].click();
      fixture.detectChanges();
      q<HTMLButtonElement>('[data-act=undo]')!.click();
      fixture.detectChanges();
      expect(emitted[emitted.length - 1].items).toEqual([]);
      expect(q('[data-testid=list-toast]')).toBeNull();
      expect(qa('[data-act=add-quick]')).toHaveLength(1);
    });

    it('drops the message after four seconds', () => {
      vi.useFakeTimers();
      render('COMPLAINTS', { quick: [quick('k1', 'Fever')] });
      qa('[data-act=add-quick]')[0].click();
      fixture.detectChanges();
      expect(q('[data-testid=list-toast]')).not.toBeNull();
      vi.advanceTimersByTime(4100);
      fixture.detectChanges();
      expect(q('[data-testid=list-toast]')).toBeNull();
    });

    it('offers an item already on the visit no more', () => {
      render('COMPLAINTS', { items: [item({ text: 'fever' })], quick: [quick('k1', 'Fever'), quick('k2', 'Cough')] });
      expect(qa('[data-act=add-quick]').map((b) => b.getAttribute('aria-label'))).toEqual(['Add Cough']);
    });

    it('shows a retry when the list cannot be loaded, and typing and Add still work', async () => {
      vi.useFakeTimers();
      render('COMPLAINTS', { quickFails: true });
      expect(q('.quick .err-text')!.textContent).toContain('We could not load the list.');
      q<HTMLButtonElement>('[data-act=toggle-add]')!.click();
      fixture.detectChanges();
      await type('sore ear');
      http.expectOne((r) => r.url === `${EMR}/masters/complaints`).flush({ items: [], total: 0, page: 0, pageSize: 20 });
      fixture.detectChanges();
      q<HTMLButtonElement>('[data-act=create-new]')!.click();
      fixture.detectChanges();
      expect(emitted[0].items[0].text).toBe('Sore ear');
      q<HTMLButtonElement>('[data-act=retry-quick]')!.click();
      http.expectOne((r) => r.url === `${EMR}/quick-options/complaints`).flush({ master: 'complaints', items: [] });
    });
  });

  describe('search and typed items (SAH-56-1, SAH-56-4)', () => {
    it('searches the master and adds the match that is tapped', async () => {
      vi.useFakeTimers();
      render('COMPLAINTS');
      q<HTMLButtonElement>('[data-act=toggle-add]')!.click();
      fixture.detectChanges();
      expect(q('input[type=search]')!.getAttribute('placeholder')).toBe('Type a complaint, e.g. fever');
      expect(q('.hint')!.textContent).toBe('Showing your most used first. Tap to add; keep tapping to add more.');
      await type('fev');
      const req = http.expectOne((r) => r.url === `${EMR}/masters/complaints`);
      expect(req.request.params.get('q')).toBe('fev');
      req.flush({ items: [{ id: 'm1', name: 'Fever', specialtyHint: 'gm' }], total: 1, page: 0, pageSize: 20 });
      fixture.detectChanges();
      qa('[data-act=add-result]')[0].click();
      fixture.detectChanges();
      expect(emitted[0].items[0]).toMatchObject({ itemKey: 'm1', text: 'Fever', newToList: false });
    });

    it('adds a typed item with the first letter capitalised and the tag "New to list"', async () => {
      vi.useFakeTimers();
      render('COMPLAINTS');
      q<HTMLButtonElement>('[data-act=toggle-add]')!.click();
      fixture.detectChanges();
      await type('ear fullness');
      http.expectOne((r) => r.url === `${EMR}/masters/complaints`).flush({ items: [], total: 0, page: 0, pageSize: 20 });
      fixture.detectChanges();
      expect(q('.hint')!.textContent).toBe("Not in the list? Add it as new. It stays on this patient's record.");
      expect(q('[data-act=create-new]')!.textContent).toContain('+ Add “ear fullness” as new complaint');
      q<HTMLButtonElement>('[data-act=create-new]')!.click();
      fixture.detectChanges();
      expect(emitted[0].items[0]).toMatchObject({ itemKey: null, text: 'Ear fullness', newToList: true });
      expect(q('.chip .pill')!.textContent).toBe('New to list');
      expect(q('[data-testid=list-toast]')!.textContent).toContain('Ear fullness added');
    });

    it('does nothing for empty text and does not add the same text twice', async () => {
      vi.useFakeTimers();
      render('COMPLAINTS', { items: [item()] });
      q<HTMLButtonElement>('[data-act=toggle-add]')!.click();
      fixture.detectChanges();
      const input = q<HTMLInputElement>('input[type=search]')!;
      input.value = '   ';
      input.dispatchEvent(new Event('input'));
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
      fixture.detectChanges();
      expect(emitted).toHaveLength(0);
      await type('FEVER');
      http.expectOne((r) => r.url === `${EMR}/masters/complaints`).flush({ items: [], total: 0, page: 0, pageSize: 20 });
      fixture.detectChanges();
      expect(q('[data-act=create-new]')).toBeNull();
      expect(emitted).toHaveLength(0);
    });

    it('refuses text longer than 200 characters with a reason', async () => {
      vi.useFakeTimers();
      render('COMPLAINTS');
      q<HTMLButtonElement>('[data-act=toggle-add]')!.click();
      fixture.detectChanges();
      await type('x'.repeat(201));
      http.expectOne((r) => r.url === `${EMR}/masters/complaints`).flush({ items: [], total: 0, page: 0, pageSize: 20 });
      fixture.detectChanges();
      q<HTMLButtonElement>('[data-act=create-new]')!.click();
      fixture.detectChanges();
      expect(q('[data-testid=list-notice]')!.textContent).toBe('Use at most 200 characters.');
      expect(emitted).toHaveLength(0);
    });

    it('searches diagnoses by name or code and shows the ICD-10 code beside the name', async () => {
      vi.useFakeTimers();
      render('DIAGNOSIS');
      q<HTMLButtonElement>('[data-act=toggle-add]')!.click();
      fixture.detectChanges();
      await type('i10');
      http.expectOne((r) => r.url === '/api/v1/opd/diagnoses').flush([{ code: 'I10', name: 'Essential hypertension' }]);
      fixture.detectChanges();
      expect(qa('[data-act=add-result]')[0].textContent).toContain('I10');
      qa('[data-act=add-result]')[0].click();
      fixture.detectChanges();
      expect(q('.chip .code')!.textContent).toBe('I10');
    });
  });

  describe('attributes (SAH-56-2)', () => {
    it('shows the duration as a pill with a down arrow and moves to the next value on each press', () => {
      render('COMPLAINTS', { items: [item()] });
      const pill = q<HTMLButtonElement>('.chip [data-act=cycle-attr]')!;
      expect(pill.textContent!.trim()).toBe('3 days ▾');
      expect(pill.getAttribute('aria-label')).toBe('Duration of Fever: 3 days. Press to change.');
      pill.click();
      expect(emitted[0].items[0].attr1).toBe('D5');
    });

    it('goes back to the first value after the last one', () => {
      render('COMPLAINTS', { items: [item({ attr1: 'OVER_M1' })] });
      q<HTMLButtonElement>('.chip [data-act=cycle-attr]')!.click();
      expect(emitted[0].items[0].attr1).toBe('D1');
    });

    it('starts Advice in the visit language and lets the doctor change the print language', () => {
      render('ADVICE', { withContext: true });
      language.set('MR');
      fixture.detectChanges();
      const select = q<HTMLSelectElement>('[data-testid=print-language]')!;
      expect(select.value).toBe('MR');
      select.value = 'HI';
      select.dispatchEvent(new Event('change'));
      expect(setLanguage).toHaveBeenCalledWith('HI');
    });

    it('shows WhatsApp and Print + WhatsApp switched off with the note, and keeps Print', () => {
      render('ADVICE', { items: [item({ attr1: 'EN', attr2: 'PRINT', text: 'Walk daily', itemKey: null, newToList: true })] });
      q<HTMLButtonElement>('[data-act=view-table]')!.click();
      fixture.detectChanges();
      const options = Array.from(q<HTMLSelectElement>('select[aria-label^="Send by"]')!.options);
      expect(options.map((o) => [o.value, o.disabled])).toEqual([['PRINT', false], ['WHATSAPP', true], ['PRINT_WHATSAPP', true]]);
      expect(options[1].textContent).toContain('Needs the WhatsApp service');
    });
  });

  describe('Table view (SAH-56-3)', () => {
    const items = [item(), item({ id: 'i2', text: 'Cough', itemKey: 'k2', addedAt: '2026-11-11T05:34:00Z', addedByName: 'Dr Test' })];

    it('shows the columns and the added on and added by of each row', () => {
      render('COMPLAINTS', { items });
      q<HTMLButtonElement>('[data-act=view-table]')!.click();
      fixture.detectChanges();
      expect(qa('th').map((h) => h.textContent!.trim())).toEqual(['#', 'Complaint', 'Duration', 'Severity', 'Added on', 'Added by', 'Active', 'Remove']);
      const rows = qa('tbody tr');
      expect(rows[0].textContent).toContain('Not saved yet');
      expect(rows[1].textContent).toContain('11 Nov 2026, 11:04 AM');
      expect(rows[1].textContent).toContain('Dr Test');
    });

    it('shows the diagnosis code in the name cell before the name, not as an extra column', () => {
      render('DIAGNOSIS', { items: [item({ text: 'Hypertension', code: 'I10' })] });
      q<HTMLButtonElement>('[data-act=view-table]')!.click();
      fixture.detectChanges();
      expect(qa('th').map((h) => h.textContent!.trim())).not.toContain('Code');
      expect(q('td.s2')!.textContent!.replace(/\s+/g, ' ').trim()).toBe('I10Hypertension');
    });

    it('mutes a row when Active is switched off, counts it as disabled, and restores it when switched on', () => {
      render('COMPLAINTS', { items });
      q<HTMLButtonElement>('[data-act=view-table]')!.click();
      fixture.detectChanges();
      qa('[data-act=toggle-active]')[0].click();
      fixture.detectChanges();
      expect(emitted[0].items[0].active).toBe(false);
      expect(qa('tbody tr')[0].classList).toContain('off');
      expect(q('[data-testid=list-count]')!.textContent).toBe('1 active · 1 disabled');
      expect(q('[data-testid=list-toast]')!.textContent).toContain('Fever disabled. Kept in the record but not printed.');
      qa('[data-act=toggle-active]')[0].click();
      fixture.detectChanges();
      expect(emitted[1].items[0].active).toBe(true);
      expect(q('[data-testid=list-count]')!.textContent).toBe('2');
    });

    it('removes a row with a message and an Undo that brings it back with its attributes', () => {
      render('COMPLAINTS', { items: [item({ attr1: 'W1' })] });
      q<HTMLButtonElement>('[data-act=view-table]')!.click();
      fixture.detectChanges();
      qa('[data-act=remove]')[0].click();
      fixture.detectChanges();
      expect(q('[data-testid=list-toast]')!.textContent).toContain('Fever removed');
      q<HTMLButtonElement>('[data-act=undo]')!.click();
      fixture.detectChanges();
      expect(emitted[emitted.length - 1].items[0]).toMatchObject({ text: 'Fever', attr1: 'W1' });
    });

    it('goes back to chips with the same data', () => {
      render('COMPLAINTS', { items });
      q<HTMLButtonElement>('[data-act=view-table]')!.click();
      fixture.detectChanges();
      q<HTMLButtonElement>('[data-act=view-chips]')!.click();
      fixture.detectChanges();
      expect(qa('.chip .nm').map((n) => n.textContent)).toEqual(['Fever', 'Cough']);
    });
  });

  describe('procedures and services (SAH-48-4)', () => {
    it('shows the clinic tariff as chips with the price in the Indian format', () => {
      render('SERVICES');
      const chips = qa('[data-act=add-quick]').map((b) => b.textContent!.replace(/\s+/g, ' ').trim());
      expect(chips).toEqual(['+ ECG ₹300', '+ Dressing ₹1,25,000']);
    });

    it('adds a service with quantity 1 and Bill today', () => {
      render('SERVICES');
      qa('[data-act=add-quick]')[0].click();
      fixture.detectChanges();
      expect(emitted[0].items[0]).toMatchObject({ itemKey: 's1', text: 'ECG', attr1: '1', attr2: 'BILL_TODAY' });
    });

    it('refuses to remove or change a service on a bill that has a payment', () => {
      render('SERVICES', { items: [item({ text: 'ECG', attr1: '1', attr2: 'BILL_TODAY', billed: 'ON_PAID_BILL' })] });
      q<HTMLButtonElement>('[data-act=remove]')!.click();
      fixture.detectChanges();
      expect(emitted).toHaveLength(0);
      expect(q('[data-testid=list-notice]')!.textContent).toBe('Already billed. Ask billing to adjust.');
      q<HTMLButtonElement>('.chip [data-act=cycle-attr]')!.click();
      expect(emitted).toHaveLength(0);
    });
  });

  describe('read-only', () => {
    it('shows the items without any control to add, change or remove', () => {
      render('COMPLAINTS', { items: [item()], disabled: true });
      expect(q('[data-act=toggle-add]')).toBeNull();
      expect(q('[data-act=remove]')).toBeNull();
      expect(q('.quick')).toBeNull();
      expect(q<HTMLButtonElement>('.chip [data-act=cycle-attr]')!.disabled).toBe(true);
    });
  });
});
