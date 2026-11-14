import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { EMR_COMPONENT_CONTEXT, EmrComponentContext, LiveAllergyAlert } from '../emr-context';
import { EmrMedicineOption, EmrPrescriptionRow, EmrPrescriptionValue } from '../emr-phase3.model';
import { PrescriptionComponent } from './prescription.component';
import { rowFromOption } from './prescription.logic';

const EMR = '/api/v1/opd/emr';

const telmisartan: EmrMedicineOption = { medicineId: 'm1', name: 'Telmisartan 40 mg', form: 'Tablet', favourite: true, allergyClass: null, defaultFrequency: '1-0-0', defaultDays: 30, defaultWhen: 'AFTER_FOOD' };
const cotrimoxazole: EmrMedicineOption = { medicineId: 'm2', name: 'Co-trimoxazole 960 mg', form: 'Tablet', favourite: true, allergyClass: 'SULFA', defaultFrequency: '1-0-1', defaultDays: 5, defaultWhen: 'AFTER_FOOD' };
const metformin: EmrMedicineOption = { medicineId: 'm3', name: 'Metformin 500 mg SR', form: 'Tablet', favourite: false, allergyClass: null, defaultFrequency: '1-0-1', defaultDays: 30, defaultWhen: 'AFTER_FOOD' };

describe('PrescriptionComponent (SAH-48)', () => {
  let fixture: ComponentFixture<PrescriptionComponent>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let emitted: EmrPrescriptionValue[];
  let alerts: ReturnType<typeof signal<readonly LiveAllergyAlert[]>>;
  let last: ReturnType<typeof signal<{ recordId: string; date: string } | null>>;
  let copyItems: ReturnType<typeof vi.fn>;
  let noteClass: ReturnType<typeof vi.fn>;

  interface Options {
    rows?: EmrPrescriptionRow[];
    required?: boolean;
    disabled?: boolean;
    context?: boolean;
    favourites?: EmrMedicineOption[];
    favouritesFail?: boolean;
  }

  function render(opts: Options = {}): void {
    alerts = signal<readonly LiveAllergyAlert[]>([]);
    last = signal<{ recordId: string; date: string } | null>(null);
    copyItems = vi.fn(() => of({ added: 2, skipped: 0, record: {} }));
    noteClass = vi.fn();
    const ctx = { allergyAlerts: alerts, lastSignedPrescription: last, copyItems, noteMedicineClass: noteClass } as unknown as EmrComponentContext;
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ...(opts.context === false ? [] : [{ provide: EMR_COMPONENT_CONTEXT, useValue: ctx }])],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PrescriptionComponent);
    fixture.componentRef.setInput('title', 'Prescription');
    fixture.componentRef.setInput('required', opts.required ?? true);
    fixture.componentRef.setInput('value', opts.rows ? { component: 'PRESCRIPTION', schemaVersion: 1, rows: opts.rows } : null);
    fixture.componentRef.setInput('disabled', opts.disabled ?? false);
    fixture.componentRef.setInput('inputId', 'rx-test');
    emitted = [];
    fixture.componentInstance.valueChange.subscribe((v) => {
      emitted.push(v);
      fixture.componentRef.setInput('value', v);
      fixture.detectChanges();
    });
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
    if (!opts.disabled) {
      const req = http.expectOne(`${EMR}/medicine-favourites`);
      if (opts.favouritesFail) {
        req.flush({}, { status: 500, statusText: 'Server Error' });
      } else {
        req.flush(opts.favourites ?? [telmisartan, cotrimoxazole]);
      }
      fixture.detectChanges();
    }
  }

  const q = <T extends Element = HTMLElement>(sel: string) => el.querySelector<T>(sel);
  const qa = (sel: string) => Array.from(el.querySelectorAll<HTMLElement>(sel));
  const row = (o: EmrMedicineOption, over: Partial<EmrPrescriptionRow> = {}) => ({ ...rowFromOption(o, 'SEARCH'), ...over });

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

  describe('the section', () => {
    it('shows the title with a red star, the column titles and the empty text', () => {
      render();
      expect(q('h3')!.textContent).toContain('Prescription');
      expect(q('.star')).not.toBeNull();
      expect(qa('th').map((h) => h.textContent!.trim())).toEqual(['Medicine (generic first)', 'How often', 'For how long', 'When', 'Remove']);
      expect(q('[data-testid=rx-empty]')!.textContent!.trim()).toBe('No medicines yet. Use quick add below or repeat the last prescription.');
    });

    it('says the allergy check covers listed classes only', () => {
      render();
      expect(q('[data-testid=safety-line]')!.textContent).toBe('Allergy check covers listed allergy classes only. No alert is not a clearance.');
    });

    it('lists the favourites that are not yet on the prescription and adds one with its saved values', () => {
      render({ rows: [row(telmisartan)] });
      expect(qa('[data-act=add-favourite]').map((b) => b.getAttribute('aria-label'))).toEqual(['Add Co-trimoxazole 960 mg']);
    });

    it('adds a favourite at a tap with its saved frequency, days and when', () => {
      render({ favourites: [telmisartan] });
      qa('[data-act=add-favourite]')[0].click();
      fixture.detectChanges();
      expect(emitted[0].rows[0]).toMatchObject({ medicineId: 'm1', name: 'Telmisartan 40 mg', form: 'Tablet', frequency: '1-0-0', days: 30, when: 'AFTER_FOOD', source: 'FAVOURITE' });
      expect(q('[data-testid=rx-toast]')!.textContent).toContain('Telmisartan 40 mg added');
    });

    it('shows a retry when the favourites cannot be loaded', () => {
      render({ favouritesFail: true });
      expect(q('.quick .err-text')!.textContent).toContain('We could not load your favourites.');
      q<HTMLButtonElement>('[data-act=retry-favourites]')!.click();
      http.expectOne(`${EMR}/medicine-favourites`).flush([telmisartan]);
      fixture.detectChanges();
      expect(qa('[data-act=add-favourite]')).toHaveLength(1);
    });
  });

  describe('search', () => {
    it('searches the medicines and adds the one that is tapped, the generic name first', async () => {
      vi.useFakeTimers();
      render({ favourites: [] });
      await type('tel');
      const req = http.expectOne((r) => r.url === `${EMR}/medicines`);
      expect(req.request.params.get('q')).toBe('tel');
      req.flush({ items: [telmisartan], total: 1, page: 0, pageSize: 20 });
      fixture.detectChanges();
      qa('[data-act=add-result]')[0].click();
      fixture.detectChanges();
      expect(emitted[0].rows[0]).toMatchObject({ name: 'Telmisartan 40 mg', source: 'SEARCH' });
      expect(qa('tbody .nm')[0].textContent).toBe('Telmisartan 40 mg');
      expect(qa('tbody .sub')[0].textContent).toBe('Tablet');
    });

    it('adds a medicine the doctor typed, never allergy-checked, with the default values', async () => {
      vi.useFakeTimers();
      render({ favourites: [] });
      await type('Zinc sulphate');
      http.expectOne((r) => r.url === `${EMR}/medicines`).flush({ items: [], total: 0, page: 0, pageSize: 20 });
      fixture.detectChanges();
      q<HTMLButtonElement>('[data-act=add-typed]')!.click();
      fixture.detectChanges();
      expect(emitted[0].rows[0]).toMatchObject({ medicineId: null, name: 'Zinc sulphate', frequency: '1-0-1', days: 5, when: 'AFTER_FOOD', source: 'TYPED' });
    });

    it('does not add a medicine that is already on the prescription', async () => {
      vi.useFakeTimers();
      render({ rows: [row(telmisartan)], favourites: [] });
      await type('telmisartan 40 mg');
      http.expectOne((r) => r.url === `${EMR}/medicines`).flush({ items: [telmisartan], total: 1, page: 0, pageSize: 20 });
      fixture.detectChanges();
      expect(qa('[data-act=add-result]')).toHaveLength(0);
      expect(q('[data-act=add-typed]')).toBeNull();
    });
  });

  describe('a row (SAH-48-1)', () => {
    it('changes How often to one of the four', () => {
      render({ rows: [row(telmisartan)] });
      const pills = qa('.pill-btn');
      expect(pills.map((p) => p.textContent!.trim())).toEqual(['1-0-0 OD', '1-0-1 BD', '0-0-1 HS', '1-1-1 TDS']);
      expect(pills[0].getAttribute('aria-pressed')).toBe('true');
      pills[3].click();
      expect(emitted[0].rows[0].frequency).toBe('1-1-1');
    });

    it('changes the days by 1 up to 7 and by 7 above, never below 1', () => {
      render({ rows: [row(telmisartan, { days: 7 })] });
      const days = () => emitted[emitted.length - 1].rows[0].days;
      q<HTMLButtonElement>('[aria-label="More days"]')!.click();
      expect(days()).toBe(14);
      q<HTMLButtonElement>('[aria-label="Fewer days"]')!.click();
      expect(days()).toBe(7);
      q<HTMLButtonElement>('[aria-label="Fewer days"]')!.click();
      expect(days()).toBe(6);
    });

    it('switches the minus button off at 1 day', () => {
      render({ rows: [row(telmisartan, { days: 1 })] });
      expect(q<HTMLButtonElement>('[aria-label="Fewer days"]')!.disabled).toBe(true);
    });

    it('changes When to take it', () => {
      render({ rows: [row(telmisartan)] });
      const select = q<HTMLSelectElement>('select')!;
      expect(Array.from(select.options).map((o) => o.textContent!.trim())).toEqual(['After food', 'Before food', 'Bedtime', 'Morning, empty stomach']);
      select.value = 'BEDTIME';
      select.dispatchEvent(new Event('change'));
      expect(emitted[0].rows[0].when).toBe('BEDTIME');
    });

    it('removes a row with a message, and Undo brings the rows back as they were', () => {
      render({ rows: [row(telmisartan, { days: 14 })] });
      q<HTMLButtonElement>('[data-act=remove]')!.click();
      fixture.detectChanges();
      expect(q('[data-testid=rx-toast]')!.textContent).toContain('Telmisartan 40 mg removed');
      q<HTMLButtonElement>('[data-act=undo]')!.click();
      fixture.detectChanges();
      expect(emitted[emitted.length - 1].rows[0]).toMatchObject({ name: 'Telmisartan 40 mg', days: 14 });
    });

    it('clears all with "Prescription cleared" and an Undo', () => {
      render({ rows: [row(telmisartan), row(metformin)] });
      q<HTMLButtonElement>('[data-act=clear-all]')!.click();
      fixture.detectChanges();
      expect(emitted[0].rows).toEqual([]);
      expect(q('[data-testid=rx-toast]')!.textContent).toContain('Prescription cleared');
      q<HTMLButtonElement>('[data-act=undo]')!.click();
      expect(emitted[1].rows).toHaveLength(2);
    });
  });

  describe('Repeat last Rx (SAH-48-1)', () => {
    it('is disabled with "No earlier prescription" when the patient has none', () => {
      render();
      expect(q<HTMLButtonElement>('[data-act=repeat-last]')!.disabled).toBe(true);
      expect(q('[data-testid=no-earlier]')!.textContent).toBe('No earlier prescription');
    });

    it('copies the last signed medicines and names how many were added', () => {
      render();
      last.set({ recordId: 'r0', date: '2026-10-28' });
      fixture.detectChanges();
      q<HTMLButtonElement>('[data-act=repeat-last]')!.click();
      fixture.detectChanges();
      expect(copyItems).toHaveBeenCalledWith({ source: 'LAST_SIGNED_PRESCRIPTION', what: 'MEDICINES' });
      expect(q('[data-testid=rx-toast]')!.textContent).toContain('Last prescription copied (2 medicines)');
    });

    it('says why nothing was added, and shows an error when the copy failed', () => {
      render();
      last.set({ recordId: 'r0', date: '2026-10-28' });
      copyItems.mockReturnValueOnce(of({ added: 0, skipped: 3, record: {} }));
      fixture.detectChanges();
      q<HTMLButtonElement>('[data-act=repeat-last]')!.click();
      fixture.detectChanges();
      expect(q('[data-testid=rx-toast]')!.textContent).toContain('already on the prescription');
      copyItems.mockReturnValueOnce(of(null));
      q<HTMLButtonElement>('[data-act=repeat-last]')!.click();
      fixture.detectChanges();
      expect(q('[data-testid=rx-notice]')!.textContent).toBe('We could not copy the last prescription. Try again.');
    });
  });

  describe('drug-class allergy alert (SAH-48-2)', () => {
    it('warns when a flagged drug is added and tells the screen its class', () => {
      render({ favourites: [cotrimoxazole] });
      qa('[data-act=add-favourite]')[0].click();
      fixture.detectChanges();
      expect(q('[data-testid=rx-toast]')!.textContent).toContain('Warning: Co-trimoxazole 960 mg clashes with sulfa allergy');
      expect(noteClass).toHaveBeenCalledWith('m2', 'SULFA');
      expect(qa('tbody .sub')[0].textContent).toBe('Tablet · contains a sulfa drug');
    });

    it('shows the red alert of the mockup for a row that clashes, with "Remove it" and the confirmation', () => {
      const r = row(cotrimoxazole);
      render({ rows: [r] });
      alerts.set([{ rowId: r.id, medicineName: r.name, allergyClass: 'SULFA', reviewed: false }]);
      fixture.detectChanges();
      const alert = q('[data-testid=allergy-alert]')!;
      expect(alert.getAttribute('role')).toBe('alert');
      expect(alert.textContent).toContain('Co-trimoxazole 960 mg contains a sulfa drug. This patient is allergic to sulfa drugs.');
      expect(alert.textContent).toContain('I have reviewed the alert and want to prescribe anyway');
      expect(q('tbody tr')!.classList).toContain('flag');
    });

    it('words the penicillin alert as "is a penicillin"', () => {
      const r = row(metformin, { name: 'Amoxicillin 500 mg' });
      render({ rows: [r] });
      alerts.set([{ rowId: r.id, medicineName: r.name, allergyClass: 'PENICILLIN', reviewed: false }]);
      fixture.detectChanges();
      expect(q('[data-testid=allergy-alert]')!.textContent).toContain('Amoxicillin 500 mg is a penicillin. This patient is allergic to penicillin.');
    });

    it('removes the flagged drug with the message "Removed. Allergy conflict cleared."', () => {
      const r = row(cotrimoxazole);
      render({ rows: [r, row(telmisartan)] });
      alerts.set([{ rowId: r.id, medicineName: r.name, allergyClass: 'SULFA', reviewed: false }]);
      fixture.detectChanges();
      q<HTMLButtonElement>('[data-act=remove-flagged]')!.click();
      fixture.detectChanges();
      expect(emitted[0].rows.map((x) => x.name)).toEqual(['Telmisartan 40 mg']);
      expect(q('[data-testid=rx-toast]')!.textContent).toContain('Removed. Allergy conflict cleared.');
    });

    it('records the review on the row when "I have reviewed the alert" is ticked, and clears it when unticked', () => {
      const r = row(cotrimoxazole);
      render({ rows: [r] });
      alerts.set([{ rowId: r.id, medicineName: r.name, allergyClass: 'SULFA', reviewed: false }]);
      fixture.detectChanges();
      const box = q<HTMLInputElement>('[data-act=review]')!;
      box.checked = true;
      box.dispatchEvent(new Event('change'));
      expect(emitted[0].rows[0].allergyReview).toEqual({ allergyClass: 'SULFA', confirmed: true });
      box.checked = false;
      box.dispatchEvent(new Event('change'));
      expect(emitted[1].rows[0].allergyReview).toBeNull();
    });

    it('shows no alert for a row that does not clash, and none outside an open record', () => {
      render({ rows: [row(telmisartan)] });
      expect(q('[data-testid=allergy-alert]')).toBeNull();
    });
  });

  describe('outside a record (the builder Preview)', () => {
    it('works without Repeat last Rx and without alerts', () => {
      render({ context: false, rows: [row(telmisartan)] });
      expect(q('[data-act=repeat-last]')).toBeNull();
      expect(q('[data-testid=allergy-alert]')).toBeNull();
      expect(qa('tbody tr')).toHaveLength(1);
    });
  });

  describe('read-only', () => {
    it('shows the rows without any control to add, change or remove', () => {
      render({ rows: [row(telmisartan)], disabled: true });
      expect(q('input[type=search]')).toBeNull();
      expect(q('[data-act=remove]')).toBeNull();
      expect(q('[data-act=clear-all]')).toBeNull();
      expect(q('.quick')).toBeNull();
      expect(qa('.pill-btn').every((b) => (b as HTMLButtonElement).disabled)).toBe(true);
    });
  });
});
