import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { StockLedgerPage } from '../pharmacy-inventory.model';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { CENTRAL_REF, INVENTORY_OWNER_CONTEXT, PRODUCT_REF } from './inventory.fixtures';
import { ProductPickerComponent } from './product-picker.component';
import { StockLedgerSection } from './stock-ledger.section';

const BASE = '/api/v1/pharmacy';
const LEDGER = `${BASE}/reports/stock-ledger`;

function page(over: Partial<StockLedgerPage> = {}): StockLedgerPage {
  return {
    items: [
      {
        ledgerId: 'l1',
        at: '2026-11-19T06:30:00Z',
        transaction: 'OPENING_STOCK',
        ref: 'OSI-0001',
        store: CENTRAL_REF,
        state: 'AVAILABLE',
        batch: { batchId: 'b1', batchNo: 'TESTB001', expiryDate: '2028-03-31' },
        qtyIn: 100,
        qtyOut: 0,
        balance: 100,
        user: { membershipId: 'm1', name: 'Test Pharmacist One' },
        secondSigner: null,
        reversesLedgerId: null,
      },
      {
        ledgerId: 'l2',
        at: '2026-11-19T07:00:00Z',
        transaction: 'EXPIRY_QUARANTINE',
        ref: null,
        store: CENTRAL_REF,
        state: 'QUARANTINE',
        batch: { batchId: 'b1', batchNo: 'TESTB001', expiryDate: '2028-03-31' },
        qtyIn: 0,
        qtyOut: 5,
        balance: 0,
        user: null,
        secondSigner: null,
        reversesLedgerId: 'l1',
      },
    ],
    total: 2,
    page: 0,
    size: 50,
    product: PRODUCT_REF,
    stores: [CENTRAL_REF],
    from: '2026-10-20',
    to: '2026-11-19',
    ...over,
  };
}

describe('StockLedgerSection', () => {
  let fixture: ComponentFixture<StockLedgerSection>;
  let http: HttpTestingController;

  async function open(): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(INVENTORY_OWNER_CONTEXT);
    fixture = TestBed.createComponent(StockLedgerSection);
    fixture.detectChanges();
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function pick(): Promise<void> {
    const picker = fixture.debugElement
      .queryAll((d) => d.componentInstance instanceof ProductPickerComponent)
      .map((d) => d.componentInstance as ProductPickerComponent)[0];
    picker.picked.emit({ productId: 'prod-1', code: 'G-000101', name: 'Inj. Ceftriaxone', strength: '1 g', form: 'Injection' });
    await settle();
  }

  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

  afterEach(() => http.verify());

  it('reads the ledger of the chosen product for the default dates and shows one line per movement', async () => {
    const el = await open();
    await pick();
    const req = http.expectOne((r) => r.url === LEDGER);
    expect(req.request.params.get('productId')).toBe('prod-1');
    expect(req.request.params.has('storeId')).toBe(false);
    expect(req.request.params.get('from')).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    req.flush(page());
    await settle();
    expect(text(el, 'ledger-heading')).toBe('Stock ledger · Inj. Ceftriaxone 1 g');
    const heads = Array.from(el.querySelectorAll('th')).map((h) => h.textContent?.trim());
    expect(heads).toEqual(['Time', 'Transaction', 'Reference', 'Store · state', 'Batch', 'In', 'Out', 'Balance', 'User']);
    const first = text(el, 'ledger-l1') ?? '';
    expect(first).toContain('Opening stock');
    expect(first).toContain('OSI-0001');
    expect(first).toContain('Test Central Store · Available');
    expect(first).toContain('Mar 2028');
    expect(first).toContain('Test Pharmacist One');
  });

  it('shows System for a line with no user, a dash for a zero side, and marks a correction', async () => {
    const el = await open();
    await pick();
    http.expectOne((r) => r.url === LEDGER).flush(page());
    await settle();
    const second = text(el, 'ledger-l2') ?? '';
    expect(second).toContain('Expired · quarantined');
    expect(second).toContain('System');
    expect(second).toContain('Corrects an earlier line');
    expect(second).toContain('Quarantine');
  });

  it('refuses a range longer than 12 months before asking the server', async () => {
    const el = await open();
    await pick();
    http.expectOne((r) => r.url === LEDGER).flush(page());
    await settle();
    (fixture.componentInstance as unknown as { form: { patchValue(v: object): void } }).form.patchValue({
      from: '2024-01-01',
      to: '2026-11-19',
    });
    q(el, 'ledger-show')?.click();
    await settle();
    expect(text(el, 'ledger-range-error')).toBe('Choose at most 12 months.');
    http.expectNone((r) => r.url === LEDGER);
  });

  it('says there are no movements in the dates', async () => {
    const el = await open();
    await pick();
    http.expectOne((r) => r.url === LEDGER).flush(page({ items: [], total: 0 }));
    await settle();
    expect(text(el, 'ledger-empty')).toBe('No movements of this product in these dates.');
  });

  it('shows "Could not load. Try again." when the ledger cannot be read', async () => {
    const el = await open();
    await pick();
    http.expectOne((r) => r.url === LEDGER).flush(null, { status: 500, statusText: 'Server Error' });
    await settle();
    expect(text(el, 'ledger-error')).toContain('Could not load. Try again.');
  });
});
