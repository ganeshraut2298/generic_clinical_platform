import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { OWNER_CONTEXT, PHARMACIST_CONTEXT } from '../pharmacy.fixtures';
import { ELEVEN_LISTS, TEN_SERIES, THREE_TEMPLATES, printTemplate, series } from '../pharmacy-c.fixtures';
import { DocumentSeries, PharmacyContext, PrintTemplate, ReasonLists } from '../pharmacy.model';
import { DocumentsSection } from './documents.section';

const BASE = '/api/v1/pharmacy';

interface Answers {
  series?: DocumentSeries[];
  lists?: ReasonLists;
  templates?: PrintTemplate[];
}

describe('DocumentsSection (Numbering, Reasons, Print templates)', () => {
  let fixture: ComponentFixture<DocumentsSection>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext, answers: Answers = {}): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(DocumentsSection);
    fixture.detectChanges();
    http.expectOne(`${BASE}/document-series`).flush({ items: answers.series ?? TEN_SERIES });
    http.expectOne(`${BASE}/reason-lists`).flush(answers.lists ?? ELEVEN_LISTS);
    http.expectOne(`${BASE}/print-templates`).flush({ items: answers.templates ?? THREE_TEMPLATES });
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();
  const reader = (): PharmacyContext => ({ ...PHARMACIST_CONTEXT, capabilities: ['SETUP_VIEW'] });

  afterEach(() => http.verify());

  describe('Numbering', () => {
    it('renders every series the server returns, ten after SAH-79, not a fixed five', async () => {
      const el = await open(OWNER_CONTEXT);
      expect(el.querySelectorAll('app-series-row')).toHaveLength(10);
      expect(q(el, 'series-DEBIT_NOTE')).not.toBeNull();
      expect(q(el, 'series-STOCK_ADJUSTMENT')).not.toBeNull();
    });

    it('renders five when the server returns five', async () => {
      const el = await open(OWNER_CONTEXT, { series: TEN_SERIES.slice(0, 5) });
      expect(el.querySelectorAll('app-series-row')).toHaveLength(5);
    });

    it('shows the live example of the bill series from its fields', async () => {
      const el = await open(OWNER_CONTEXT);
      expect(text(el, 'series-BILL-example')).toMatch(/^SB\/\d\d-\d\d\/000001$/);
      expect(text(el, 'series-PURCHASE_ORDER-example')).toBe('PO-0001');
    });

    it('shows a series with a number issued this year as read-only, with "Numbers have already been issued this year."', async () => {
      const el = await open(OWNER_CONTEXT, {
        series: [
          series({ docType: 'PURCHASE_ORDER', prefix: 'PO/', numbersIssued: true, saved: true, version: 2 }),
          series({ docType: 'GRN' }),
        ],
      });
      expect(text(el, 'series-PURCHASE_ORDER-locked')).toBe('Numbers have already been issued this year.');
      expect(text(el, 'series-PURCHASE_ORDER-prefix')).toBe('PO/');
      expect(q(el, 'series-PURCHASE_ORDER-save')).toBeNull();
      expect((q(el, 'series-PURCHASE_ORDER') as HTMLElement).querySelector('input, mat-select')).toBeNull();
      // The next series, with no number issued, stays editable.
      expect(q(el, 'series-GRN-locked')).toBeNull();
      expect((q(el, 'series-GRN-prefix') as HTMLInputElement).disabled).toBe(false);
      expect(q(el, 'series-GRN-save')).not.toBeNull();
    });

    it('lets the owner change the prefix and the format of a series with no number issued this year', async () => {
      const el = await open(OWNER_CONTEXT);
      expect((q(el, 'series-PURCHASE_ORDER-prefix') as HTMLInputElement).disabled).toBe(false);
      for (const part of ['fy', 'length', 'restart']) {
        expect(q(el, `series-PURCHASE_ORDER-${part}`)?.classList.contains('mat-mdc-select-disabled')).toBe(false);
      }
    });

    it('shows the lock note to a reader too, as text only', async () => {
      const el = await open(reader(), {
        series: [series({ docType: 'PURCHASE_ORDER', numbersIssued: true, saved: true, version: 2 })],
      });
      expect(text(el, 'series-PURCHASE_ORDER-locked')).toBe('Numbers have already been issued this year.');
      expect(q(el, 'series-PURCHASE_ORDER-save')).toBeNull();
    });

    it('says "Numbers have already been issued this year." on a 409 series-locked, then reads the list again', async () => {
      const el = await open(OWNER_CONTEXT);
      (q(el, 'series-PURCHASE_ORDER-save') as HTMLButtonElement).click();
      http.expectOne(`${BASE}/document-series/PURCHASE_ORDER`).flush(
        { type: 'https://sahaj.in/problems/series-locked', title: 'Conflict', status: 409, detail: 'Numbers have already been issued this year.' },
        { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
      );
      await settle();
      expect(text(el, 'series-PURCHASE_ORDER-error')).toBe('Numbers have already been issued this year.');
      http.expectOne(`${BASE}/document-series`).flush({
        items: TEN_SERIES.map((x) => (x.docType === 'PURCHASE_ORDER' ? { ...x, numbersIssued: true, saved: true, version: 1 } : x)),
      });
      await settle();
      expect(text(el, 'series-PURCHASE_ORDER-locked')).toBe('Numbers have already been issued this year.');
      expect(q(el, 'series-PURCHASE_ORDER-save')).toBeNull();
    });

    it('shows no lock note before the first number', async () => {
      const el = await open(OWNER_CONTEXT);
      expect(q(el, 'series-PURCHASE_ORDER-locked')).toBeNull();
    });

    it('refuses a prefix over 6 characters without calling the server', async () => {
      const el = await open(OWNER_CONTEXT);
      const input = q(el, 'series-PURCHASE_ORDER-prefix') as HTMLInputElement;
      input.value = 'ABCDEFG';
      input.dispatchEvent(new Event('input'));
      (q(el, 'series-PURCHASE_ORDER-save') as HTMLButtonElement).click();
      await settle();
      expect(el.textContent).toContain('Use 1 to 6 letters, digits, - or /');
      http.expectNone(`${BASE}/document-series/PURCHASE_ORDER`);
    });

    it('saves a series with the version read, then reads the list again', async () => {
      const el = await open(OWNER_CONTEXT);
      const input = q(el, 'series-PURCHASE_ORDER-prefix') as HTMLInputElement;
      input.value = 'PO/';
      input.dispatchEvent(new Event('input'));
      (q(el, 'series-PURCHASE_ORDER-save') as HTMLButtonElement).click();
      const request = http.expectOne(`${BASE}/document-series/PURCHASE_ORDER`);
      expect(request.request.method).toBe('PUT');
      expect(request.request.body).toEqual({
        version: 0,
        prefix: 'PO/',
        financialYearPart: false,
        sequenceLength: 4,
        restartsEachFinancialYear: false,
      });
      request.flush(series({ docType: 'PURCHASE_ORDER', prefix: 'PO/', saved: true, version: 1 }));
      http.expectOne(`${BASE}/document-series`).flush({ items: TEN_SERIES });
      await settle();
    });

    it('says "Two document types cannot use the same prefix" on the prefix field', async () => {
      const el = await open(OWNER_CONTEXT);
      (q(el, 'series-PURCHASE_ORDER-save') as HTMLButtonElement).click();
      http.expectOne(`${BASE}/document-series/PURCHASE_ORDER`).flush(
        { type: 'https://sahaj.example/problems/prefix-in-use', title: 'Conflict', status: 409, detail: 'Two document types cannot use the same prefix' },
        { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
      );
      await settle();
      expect(q(el, 'series-PURCHASE_ORDER-prefix-error')?.textContent).toContain(
        'Two document types cannot use the same prefix',
      );
    });

    it('shows a reader the values as text and no field or button', async () => {
      const el = await open(reader());
      expect(text(el, 'series-BILL-prefix')).toBe('SB/');
      expect(text(el, 'series-BILL-example')).toBe('SB/26-27/000001');
      expect(el.querySelector('app-series-row input, app-series-row mat-select, app-series-row button')).toBeNull();
    });
  });

  describe('Reasons', () => {
    it('renders every list the server returns, eleven after SAH-79, as chips', async () => {
      const el = await open(OWNER_CONTEXT);
      expect(el.querySelectorAll('[data-testid^="reason-list-"]')).toHaveLength(11);
      expect(text(el, 'reason-list-damage')).toContain('Cold-chain break');
      expect(text(el, 'reason-list-stock-adjustment')).toContain('Theft or loss');
    });

    it('renders four lists when the server returns four', async () => {
      const el = await open(OWNER_CONTEXT, { lists: { lists: ELEVEN_LISTS.lists.slice(0, 4) } });
      expect(el.querySelectorAll('[data-testid^="reason-list-"]')).toHaveLength(4);
    });

    it('adds a reason and shows the lists the server answers with', async () => {
      const el = await open(OWNER_CONTEXT);
      const input = q(el, 'reason-input-damage') as HTMLInputElement;
      input.value = 'Packaging torn';
      input.dispatchEvent(new Event('input'));
      (q(el, 'reason-add-damage') as HTMLButtonElement).click();
      const request = http.expectOne(`${BASE}/reason-lists/damage/reasons`);
      expect(request.request.body).toEqual({ label: 'Packaging torn' });
      const next: ReasonLists = {
        lists: ELEVEN_LISTS.lists.map((l) =>
          l.listKey === 'damage'
            ? { ...l, reasons: [...l.reasons, { reasonKey: 'damage.own1', label: 'Packaging torn', seeded: false, active: true }] }
            : l,
        ),
      };
      request.flush(next);
      await settle();
      expect(text(el, 'reason-damage-damage.own1')).toContain('Packaging torn');
    });

    it('refuses a blank reason without calling the server', async () => {
      const el = await open(OWNER_CONTEXT);
      (q(el, 'reason-add-damage') as HTMLButtonElement).click();
      await settle();
      expect(el.textContent).toContain('Enter the reason (up to 40 characters)');
      http.expectNone(`${BASE}/reason-lists/damage/reasons`);
    });

    it('says "That reason is already in the list" when the server finds a repeat', async () => {
      const el = await open(OWNER_CONTEXT);
      const input = q(el, 'reason-input-damage') as HTMLInputElement;
      input.value = 'expired';
      input.dispatchEvent(new Event('input'));
      (q(el, 'reason-add-damage') as HTMLButtonElement).click();
      http.expectOne(`${BASE}/reason-lists/damage/reasons`).flush(
        { type: 'https://sahaj.example/problems/reason-exists', title: 'Conflict', status: 409, detail: 'That reason is already in the list' },
        { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
      );
      await settle();
      expect(text(el, 'reason-list-damage')).toContain('That reason is already in the list');
    });

    it('switches a reason off and marks the chip "(off)" in words', async () => {
      const el = await open(OWNER_CONTEXT);
      (q(el, 'reason-toggle-damage-damage.2') as HTMLButtonElement).click();
      const request = http.expectOne(`${BASE}/reason-lists/damage/reasons/damage.2/switch-off`);
      expect(request.request.method).toBe('POST');
      request.flush({
        lists: ELEVEN_LISTS.lists.map((l) =>
          l.listKey === 'damage'
            ? { ...l, reasons: l.reasons.map((r) => (r.reasonKey === 'damage.2' ? { ...r, active: false } : r)) }
            : l,
        ),
      });
      await settle();
      expect(text(el, 'reason-damage-damage.2')).toContain('Expired (off)');
      expect((q(el, 'reason-toggle-damage-damage.2') as HTMLButtonElement).textContent).toContain('Switch on');
    });

    it('shows the server sentence when the last damage reason is kept', async () => {
      const el = await open(OWNER_CONTEXT);
      (q(el, 'reason-toggle-damage-damage.1') as HTMLButtonElement).click();
      http.expectOne(`${BASE}/reason-lists/damage/reasons/damage.1/switch-off`).flush(
        { type: 'https://sahaj.example/problems/last-active-reason', title: 'Conflict', status: 409, detail: 'Keep at least one damage reason.' },
        { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
      );
      await settle();
      expect(text(el, 'reasons-action-error')).toBe('Keep at least one damage reason.');
    });

    it('offers no switch for the fixed reason "Short / not received"', async () => {
      const el = await open(OWNER_CONTEXT);
      expect(q(el, 'reason-receipt-reject-receipt-reject.short')).not.toBeNull();
      expect(q(el, 'reason-toggle-receipt-reject-receipt-reject.short')).toBeNull();
    });

    it('hides the switch by the fixed flag of the reason, not by its key (DEC-078 item 6)', async () => {
      const lists: ReasonLists = {
        lists: [
          {
            listKey: 'return-condition' as never,
            label: 'Return condition',
            reasons: [
              { reasonKey: 'return-condition.sealed', label: 'Sealed', seeded: true, active: true, fixed: true },
              { reasonKey: 'return-condition.open', label: 'Opened', seeded: true, active: true, fixed: false },
              // The old key, without the flag: it is a reason like any other now.
              { reasonKey: 'receipt-reject.short', label: 'Short', seeded: true, active: true },
            ],
          },
        ],
      };
      const el = await open(OWNER_CONTEXT, { lists });
      expect(q(el, 'reason-return-condition-return-condition.sealed')).not.toBeNull();
      expect(q(el, 'reason-toggle-return-condition-return-condition.sealed')).toBeNull();
      expect(q(el, 'reason-toggle-return-condition-return-condition.open')).not.toBeNull();
      expect(q(el, 'reason-toggle-return-condition-receipt-reject.short')).not.toBeNull();
    });

    it('shows the server sentence "This reason is fixed." when a switch-off is refused', async () => {
      const el = await open(OWNER_CONTEXT);
      (q(el, 'reason-toggle-damage-damage.1') as HTMLButtonElement).click();
      http.expectOne(`${BASE}/reason-lists/damage/reasons/damage.1/switch-off`).flush(
        { type: 'https://sahaj.in/problems/last-active-reason', title: 'Conflict', status: 409, detail: 'This reason is fixed.' },
        { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
      );
      await settle();
      expect(text(el, 'reasons-action-error')).toBe('This reason is fixed.');
    });

    it('shows a reader the chips and no input or button', async () => {
      const el = await open(reader());
      expect(text(el, 'reason-list-damage')).toContain('Damaged / leaking');
      expect(q(el, 'reasons')?.querySelector('input, button')).toBeNull();
    });
  });

  describe('Print templates', () => {
    it('shows Invoice, Issue slip and Debit note with paper size and a Sample preview', async () => {
      const el = await open(OWNER_CONTEXT);
      for (const type of ['INVOICE', 'ISSUE_SLIP', 'DEBIT_NOTE']) {
        expect(q(el, `template-${type}`)).not.toBeNull();
        expect(text(el, `template-${type}-preview`)).toContain('Sample');
      }
      expect(text(el, 'template-INVOICE-preview')).toContain('Test Clinic Baner');
    });

    it('lists the fixed fields of the invoice, which cannot be hidden', async () => {
      const el = await open(OWNER_CONTEXT);
      expect(text(el, 'template-INVOICE-fixed')).toBe(
        'Fixed fields, always printed: Unit name, Document number, Date, GSTIN, Drug licence number, HSN, Batch, Expiry, MRP, GST amounts.',
      );
      expect(el.querySelector('[data-testid="template-INVOICE"] mat-checkbox, [data-testid="template-INVOICE"] mat-slide-toggle')).toBeNull();
    });

    it('says "Set GSTIN in Unit settings" and that invoices cannot be printed until it is set', async () => {
      const el = await open(OWNER_CONTEXT);
      const notes = Array.from(el.querySelectorAll('[data-testid="template-INVOICE-missing"]')).map((n) => n.textContent?.trim());
      expect(notes).toEqual(['Set GSTIN in Unit settings', 'Invoices cannot be printed until it is set.']);
      expect(q(el, 'template-ISSUE_SLIP-missing')).toBeNull();
    });

    it('shows no missing note when the invoice can be printed', async () => {
      const el = await open(OWNER_CONTEXT, {
        templates: [printTemplate({ docType: 'INVOICE' }), printTemplate({ docType: 'ISSUE_SLIP' }), printTemplate({ docType: 'DEBIT_NOTE' })],
      });
      expect(q(el, 'template-INVOICE-missing')).toBeNull();
    });

    it('shows the typed header lines and footer in the preview as the owner types', async () => {
      const el = await open(OWNER_CONTEXT);
      const header = q(el, 'template-INVOICE-header-1') as HTMLInputElement;
      header.value = 'Test Pharmacy Baner';
      header.dispatchEvent(new Event('input'));
      const footer = q(el, 'template-INVOICE-footer') as HTMLTextAreaElement;
      footer.value = 'Thank you';
      footer.dispatchEvent(new Event('input'));
      await settle();
      const preview = text(el, 'template-INVOICE-preview');
      expect(preview).toContain('Test Pharmacy Baner');
      expect(preview).toContain('Thank you');
    });

    it('saves a template with the version read, empty lines left out and an empty footer as null', async () => {
      const el = await open(OWNER_CONTEXT);
      const header = q(el, 'template-ISSUE_SLIP-header-2') as HTMLInputElement;
      header.value = 'Pune 411045';
      header.dispatchEvent(new Event('input'));
      (q(el, 'template-ISSUE_SLIP-save') as HTMLButtonElement).click();
      const request = http.expectOne(`${BASE}/print-templates/ISSUE_SLIP`);
      expect(request.request.method).toBe('PUT');
      expect(request.request.body).toEqual({
        version: 0,
        headerLines: ['Pune 411045'],
        footerNote: null,
        paperSize: 'A4',
      });
      request.flush(printTemplate({ docType: 'ISSUE_SLIP', headerLines: ['Pune 411045'], saved: true, version: 1 }));
      http.expectOne(`${BASE}/print-templates`).flush({ items: THREE_TEMPLATES });
      await settle();
    });

    it('refuses a header line over 80 characters without calling the server', async () => {
      const el = await open(OWNER_CONTEXT);
      const header = q(el, 'template-DEBIT_NOTE-header-1') as HTMLInputElement;
      header.removeAttribute('maxlength');
      header.value = 'x'.repeat(81);
      header.dispatchEvent(new Event('input'));
      (q(el, 'template-DEBIT_NOTE-save') as HTMLButtonElement).click();
      await settle();
      expect(el.textContent).toContain('Keep each header line to 80 characters');
      http.expectNone(`${BASE}/print-templates/DEBIT_NOTE`);
    });

    it('shows the standard line when the save fails and keeps what was typed', async () => {
      const el = await open(OWNER_CONTEXT);
      const footer = q(el, 'template-INVOICE-footer') as HTMLTextAreaElement;
      footer.value = 'Thank you';
      footer.dispatchEvent(new Event('input'));
      (q(el, 'template-INVOICE-save') as HTMLButtonElement).click();
      http.expectOne(`${BASE}/print-templates/INVOICE`).error(new ProgressEvent('error'));
      await settle();
      expect(text(el, 'template-INVOICE-error')).toBe('Could not save. Check your connection and try again.');
      expect((q(el, 'template-INVOICE-footer') as HTMLTextAreaElement).value).toBe('Thank you');
    });

    it('opens the browser print view from Print preview, with no request to the server', async () => {
      const el = await open(OWNER_CONTEXT);
      const open$ = vi.spyOn(window, 'open').mockReturnValue(null);
      const url = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:sample');
      (q(el, 'template-INVOICE-print') as HTMLButtonElement).click();
      expect(open$).toHaveBeenCalledOnce();
      expect(url).toHaveBeenCalledOnce();
      http.expectNone((r) => r.method !== 'GET');
      open$.mockRestore();
      url.mockRestore();
    });

    it('shows a reader the saved values as text, the preview and Print preview, and no save', async () => {
      const el = await open(reader());
      expect(q(el, 'template-INVOICE-read')).not.toBeNull();
      expect(q(el, 'template-INVOICE-save')).toBeNull();
      expect(q(el, 'template-INVOICE-header-1')).toBeNull();
      expect(q(el, 'template-INVOICE-preview')).not.toBeNull();
    });
  });
});
