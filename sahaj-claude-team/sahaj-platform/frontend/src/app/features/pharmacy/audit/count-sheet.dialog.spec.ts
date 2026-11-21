import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { SCHEDULE_X_REF } from '../inventory/inventory.fixtures';
import { CountSheetPage } from '../pharmacy-stockcount.model';
import { countLine, countSheet, stockAudit } from '../stockuse/stockuse.fixtures';
import { COUNT_SHEET_CONFIG, CountSheetDialog } from './count-sheet.dialog';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-ward`;
const A = `${S}/stock-audits/aud-1`;

const REASONS = {
  lists: [
    {
      listKey: 'stock-adjustment',
      reasons: [{ reasonKey: 'stock-adjustment.count-mismatch', label: 'Count mismatch', needsNote: false, fixed: false }],
    },
  ],
};

describe('CountSheetDialog', () => {
  let fixture: ComponentFixture<CountSheetDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(sheet: CountSheetPage = countSheet()): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { storeId: 'store-ward', auditId: 'aud-1' } },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CountSheetDialog);
    fixture.detectChanges();
    http.expectOne((r) => r.url === `${A}/count-sheet`).flush(sheet);
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush(REASONS);
    http.expectOne(`${S}/second-signers`).flush({ items: [{ membershipId: 'm-2', name: 'Test Pharmacist Two', roleLabel: 'Pharmacist' }] });
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
  const type = async (el: HTMLElement, id: string, value: string, event = 'input') => {
    const input = q(el, id) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event(event));
    await settle();
  };

  afterEach(() => http.verify());

  it('is blind: it names the rule and shows no system quantity, difference or value', async () => {
    const el = await open();
    expect(COUNT_SHEET_CONFIG.ariaLabelledBy).toBe('count-title');
    expect(text(el, 'count-blind-note')).toContain('the counter does not see the system quantity');
    expect(text(el, 'count-header')).toBe('Test Ward 3B store · audit AUD-0093');
    const table = text(el, 'count-table') ?? '';
    expect(table).not.toMatch(/system|book|difference|variance|₹/i);
    expect(text(el, 'count-tiles')).toContain('Items in this count 2');
  });

  it('saves a counted value as the counter leaves the box, and counts the tile', async () => {
    const el = await open();
    await type(el, 'count-input-0', '7');
    await type(el, 'count-input-0', '7', 'change');
    const req = http.expectOne(`${A}/counts`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ version: 3, lines: [{ auditLineId: 'al-1', counted: 7, notCounted: false }] });
    req.flush(countSheet({ counted: 1, version: 4 }));
    await settle();
    expect(text(el, 'count-tiles')).toContain('Counted 1');
  });

  it('refuses a value that is not a whole number', async () => {
    const el = await open();
    await type(el, 'count-input-0', '7.5');
    await type(el, 'count-input-0', '7.5', 'change');
    http.expectNone(`${A}/counts`);
    expect(el.textContent).toContain('Enter a whole number from 0');
  });

  it('jumps to the row of a scanned barcode and says when the item is not on the sheet', async () => {
    const el = await open();
    await type(el, 'count-scan', '8900000000028');
    q(el, 'count-scan-go')?.click();
    await settle();
    expect(q(el, 'count-line-1')?.classList.contains('hit')).toBe(true);
    await type(el, 'count-scan', '000');
    q(el, 'count-scan-go')?.click();
    await settle();
    expect(text(el, 'count-scan-note')).toBe('This item is not on the count sheet.');
  });

  it('marks a recounted line and needs a reason for a line that is not counted', async () => {
    const el = await open(countSheet({ items: [countLine({ recount: true })], itemsInCount: 1 }));
    expect(q(el, 'count-recount-0')).toBeTruthy();
    q(el, 'count-notcounted-0')?.click();
    await settle();
    q(el, 'count-save')?.click();
    await settle();
    expect(el.textContent).toContain('Choose a reason');
    http.expectNone(`${A}/counts`);
  });

  it('will not submit while a line is neither counted nor marked not counted', async () => {
    const el = await open();
    await type(el, 'count-input-0', '7');
    q(el, 'count-submit')?.click();
    await settle();
    expect(text(el, 'count-error')).toContain('1 line is not counted yet');
    http.expectNone(`${A}/submit`);
  });

  it('saves the changed lines, then submits once and shows that the count waits for review', async () => {
    const el = await open(countSheet({ items: [countLine()], itemsInCount: 1 }));
    await type(el, 'count-input-0', '7');
    q(el, 'count-submit')?.click();
    await settle();
    http.expectOne(`${A}/counts`).flush(countSheet({ counted: 1, version: 4 }));
    await settle();
    const req = http.expectOne(`${A}/submit`);
    expect(req.request.body).toMatchObject({ version: 4, secondSigner: null });
    req.flush(stockAudit({ status: 'WAITING_REVIEW', counted: 1 }));
    await settle();
    expect(text(el, 'count-done')).toContain('waiting for review');
  });

  it('asks for a second signer and PIN when a Schedule X line is in the count, and clears the PIN after the send', async () => {
    const el = await open(countSheet({ items: [countLine({ product: SCHEDULE_X_REF })], itemsInCount: 1 }));
    expect(q(el, 'count-x-note')).toBeTruthy();
    await type(el, 'count-input-0', '2');
    q(el, 'count-submit')?.click();
    await settle();
    expect(el.textContent).toContain('Choose the second signer');
    http.expectNone(`${A}/submit`);
    const c = fixture.componentInstance as unknown as { signerId: { set(v: string): void }; pin: { (): string; set(v: string): void } };
    c.signerId.set('m-2');
    c.pin.set('1234');
    q(el, 'count-submit')?.click();
    await settle();
    http.expectOne(`${A}/counts`).flush(countSheet({ counted: 1, version: 4 }));
    await settle();
    const req = http.expectOne(`${A}/submit`);
    expect(req.request.body.secondSigner).toEqual({ membershipId: 'm-2', pin: '1234' });
    req.flush({ type: 'https://sahaj.in/problems/wrong-pin', title: 'Wrong PIN', status: 403, detail: 'The PIN is not right.' }, { status: 403, statusText: 'Forbidden' });
    await settle();
    expect(c.pin()).toBe('');
  });
});
