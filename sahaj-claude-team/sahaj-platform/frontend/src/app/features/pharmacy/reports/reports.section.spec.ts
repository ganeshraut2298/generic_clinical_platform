import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { reportPage, storeContext } from '../stockuse/stockuse.fixtures';
import { ReportsSection } from './reports.section';

const BASE = '/api/v1/pharmacy';

const CATALOGUE = {
  line: 'Every report can be filtered by store, date and product, and downloaded as a CSV file or printed',
  groups: [
    { group: 'STOCK', label: 'Stock', reports: [{ key: 'store-stock', label: 'Store-wise stock' }] },
    { group: 'MOVEMENT', label: 'Movement', reports: [{ key: 'indent-register', label: 'Indent register' }, { key: 'stock-ledger', label: 'Stock ledger (per product)' }] },
    { group: 'PURCHASE', label: 'Purchase', reports: [] },
    { group: 'SALES_COMPLIANCE', label: 'Sales & compliance', reports: [{ key: 'sales-margin', label: 'Sales and margin' }] },
  ],
};

describe('ReportsSection', () => {
  let fixture: ComponentFixture<ReportsSection>;
  let http: HttpTestingController;

  async function open(catalogue = CATALOGUE): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(storeContext({}, ['INVENTORY', 'STOCK_REPORTS']));
    fixture = TestBed.createComponent(ReportsSection);
    fixture.detectChanges();
    http.expectOne(`${BASE}/reports`).flush(catalogue);
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

  afterEach(() => http.verify());

  it('lists the groups the server gives, skips an empty group, and runs the first report with no dates for a "now" report', async () => {
    const run = (): void => {
      const req = http.expectOne((r) => r.url === `${BASE}/reports/store-stock`);
      expect(req.request.params.has('from')).toBe(false);
      expect(req.request.params.get('page')).toBe('0');
      req.flush(reportPage());
    };
    const el = await open();
    run();
    await settle();
    expect(text(el, 'reports-line')).toContain('downloaded as a CSV file or printed');
    expect(q(el, 'report-group-STOCK')).toBeTruthy();
    expect(text(el, 'report-group-SALES_COMPLIANCE')).toContain('Sales & compliance');
    expect(q(el, 'report-group-PURCHASE')).toBeNull();
    expect(q(el, 'report-store-stock')?.getAttribute('aria-pressed')).toBe('true');
  });

  it('shows typed cells: paise as rupees and a hidden quantity as a dash, and the row is still listed', async () => {
    const el = await open();
    http.expectOne((r) => r.url === `${BASE}/reports/store-stock`).flush(reportPage());
    await settle();
    expect(text(el, 'report-row-0')).toContain('8');
    expect(text(el, 'report-row-0')).toContain('₹ 564.00');
    expect(q(el, 'report-row-1')?.textContent?.match(/—/g)).toHaveLength(2);
  });

  it('runs a movement report for the chosen dates', async () => {
    const el = await open();
    http.expectOne((r) => r.url === `${BASE}/reports/store-stock`).flush(reportPage());
    await settle();
    q(el, 'report-indent-register')?.click();
    const req = http.expectOne((r) => r.url === `${BASE}/reports/indent-register`);
    expect(req.request.params.get('from')).toBeTruthy();
    expect(req.request.params.get('to')).toBeTruthy();
    req.flush(reportPage({ reportKey: 'indent-register', rows: [], total: 0 }));
    await settle();
    expect(q(el, 'report-empty')).toBeTruthy();
  });

  it('shows the stock ledger of a product on its own screen', async () => {
    const el = await open();
    http.expectOne((r) => r.url === `${BASE}/reports/store-stock`).flush(reportPage());
    await settle();
    q(el, 'report-stock-ledger')?.click();
    await settle();
    expect(el.querySelector('app-stock-ledger')).toBeTruthy();
    expect(q(el, 'report-run')).toBeNull();
  });

  it('downloads the CSV with the same filters and shows the server sentence when it is too large', async () => {
    const el = await open();
    http.expectOne((r) => r.url === `${BASE}/reports/store-stock`).flush(reportPage());
    await settle();
    q(el, 'report-csv')?.click();
    const req = http.expectOne((r) => r.url === `${BASE}/reports/store-stock/export`);
    expect(req.request.params.has('page')).toBe(false);
    expect(req.request.responseType).toBe('blob');
    const problem = { type: 'https://sahaj.in/problems/export-too-large', title: 'Too large', status: 409, detail: 'Narrow the date range.' };
    req.flush(new Blob([JSON.stringify(problem)], { type: 'application/problem+json' }), { status: 409, statusText: 'Conflict' });
    await new Promise((resolve) => setTimeout(resolve, 20));
    await settle();
    expect(text(el, 'report-csv-error')).toBe('Narrow the date range.');
  });

  it('runs a Sales & compliance report with the dates, the store and the totals the server sends, with no patient column', async () => {
    const el = await open({
      ...CATALOGUE,
      groups: CATALOGUE.groups.map((g) =>
        g.group === 'SALES_COMPLIANCE' ? { ...g, reports: [{ key: 'gst-sales-register', label: 'GST sales register' }] } : g,
      ),
    });
    http.expectOne((r) => r.url === `${BASE}/reports/store-stock`).flush(reportPage());
    await settle();
    q(el, 'report-gst-sales-register')?.click();
    const req = http.expectOne((r) => r.url === `${BASE}/reports/gst-sales-register`);
    expect(req.request.params.has('from')).toBe(true);
    expect(req.request.params.has('to')).toBe(true);
    req.flush(
      reportPage({
        reportKey: 'gst-sales-register',
        title: 'GST sales register',
        columns: [
          { key: 'date', label: 'Date', kind: 'DATE' },
          { key: 'billNo', label: 'Bill number', kind: 'TEXT' },
          { key: 'gstRate', label: 'GST %', kind: 'RATE' },
          { key: 'taxable', label: 'Taxable', kind: 'PAISE' },
        ],
        rows: [['2026-11-20', 'SB/26-27/018422', '12', 21429]],
        totals: [{ label: 'GST 12%', cells: [null, null, null, 21429] }],
        total: 1,
      }),
    );
    await settle();
    expect(text(el, 'report-row-0')).toContain('SB/26-27/018422');
    expect(text(el, 'report-totals')).toContain('GST 12%');
    expect(el.textContent).not.toContain('Patient');
  });

  it('shows no Sales & compliance group to a person the server gives none (a cashier)', async () => {
    const el = await open({ ...CATALOGUE, groups: CATALOGUE.groups.filter((g) => g.group !== 'SALES_COMPLIANCE') });
    http.expectOne((r) => r.url === `${BASE}/reports/store-stock`).flush(reportPage());
    await settle();
    expect(q(el, 'report-group-SALES_COMPLIANCE')).toBeNull();
  });

  it('says when there is no report for the caller', async () => {
    const el = await open({ line: 'x', groups: [] });
    expect(q(el, 'reports-none')).toBeTruthy();
  });
});
