import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { ProductPickerComponent } from './product-picker.component';
import { INVENTORY_OWNER_CONTEXT, byStore } from './inventory.fixtures';
import { StoreStockSection } from './store-stock.section';
import { BatchFocus } from './batch-inventory.section';

const BASE = '/api/v1/pharmacy';

describe('StoreStockSection', () => {
  let fixture: ComponentFixture<StoreStockSection>;
  let http: HttpTestingController;
  let requested: BatchFocus[];

  async function open(): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(INVENTORY_OWNER_CONTEXT);
    fixture = TestBed.createComponent(StoreStockSection);
    requested = [];
    fixture.componentInstance.batchesRequested.subscribe((f) => requested.push(f));
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
    const picker = fixture.debugElement.children
      .flatMap((c) => c.queryAll((d) => d.componentInstance instanceof ProductPickerComponent))
      .map((d) => d.componentInstance as ProductPickerComponent)[0];
    picker.picked.emit({ productId: 'prod-1', code: 'G-000101', name: 'Inj. Ceftriaxone', strength: '1 g', form: 'Injection' });
    await settle();
  }

  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

  afterEach(() => http.verify());

  it('asks for a product first', async () => {
    const el = await open();
    expect(el.textContent).toContain('Pick a product to see its stock in every store.');
    expect(q(el, 'stock-table')).toBeNull();
  });

  it('shows every store the server lists with its states, the status words and the Unit total', async () => {
    const el = await open();
    await pick();
    http.expectOne(`${BASE}/stock/products/prod-1/stores`).flush(byStore());
    await settle();
    expect(text(el, 'stock-heading')).toBe('Inj. Ceftriaxone 1 g · all stores');
    const heads = Array.from(el.querySelectorAll('th')).map((h) => h.textContent?.trim());
    expect(heads).toEqual(['Store', 'Available', 'In transit (in)', 'Pending return', 'Quarantine', 'Reorder level', 'Status']);
    expect(text(el, 'status-store-central')).toBe('OK');
    expect(text(el, 'status-store-ward')).toBe('Indent in transit');
    expect(Array.from(q(el, 'unit-total')!.querySelectorAll('td')).map((c) => c.textContent?.trim())).toEqual([
      'Unit total',
      '118',
      '10',
      '3',
      '2',
      '—',
      '—',
    ]);
    expect(el.textContent).toContain('Only Available stock can be issued, sold or consumed.');
  });

  it('shows Below reorder only where a reorder level is set, and OK where none is set', async () => {
    const el = await open();
    await pick();
    const data = byStore();
    data.rows = [
      { store: data.rows[0].store, available: 0, inTransit: 0, pendingReturn: 0, quarantine: 0, reorderLevel: 4, status: 'BELOW_REORDER' },
      { store: data.rows[1].store, available: 1, inTransit: 0, pendingReturn: 0, quarantine: 0, reorderLevel: null, status: 'BELOW_REORDER' },
    ];
    http.expectOne(`${BASE}/stock/products/prod-1/stores`).flush(data);
    await settle();
    expect(text(el, 'status-store-central')).toBe('Below reorder · raise indent');
    expect(text(el, 'status-store-ward')).toBe('OK');
  });

  it('reads only my store from the store path, shows its name, and no total row', async () => {
    const el = await open();
    await pick();
    http.expectOne(`${BASE}/stock/products/prod-1/stores`).flush(byStore());
    await settle();
    q(el, 'scope-my')?.click();
    http.expectOne(`${BASE}/stores/store-central/stock/products/prod-1/states`).flush(byStore({ rows: [byStore().rows[0]] }));
    await settle();
    expect(text(el, 'stock-heading')).toBe('Inj. Ceftriaxone 1 g · Test Central Store');
    expect(q(el, 'unit-total')).toBeNull();
  });

  it('opens the batches of a store for the product when its name is chosen', async () => {
    const el = await open();
    await pick();
    http.expectOne(`${BASE}/stock/products/prod-1/stores`).flush(byStore());
    await settle();
    q(el, 'open-batches-store-ward')?.click();
    expect(requested).toEqual([{ storeId: 'store-ward', productId: 'prod-1' }]);
  });

  it('says there is no stock of the product yet', async () => {
    const el = await open();
    await pick();
    http.expectOne(`${BASE}/stock/products/prod-1/stores`).flush(byStore({ empty: true }));
    await settle();
    expect(text(el, 'stock-empty')).toBe('No stock of this product yet.');
  });

  it('shows a dash for every quantity the server hides during a blind count and computes nothing', async () => {
    const el = await open();
    await pick();
    const hidden = { available: null, inTransit: null, pendingReturn: null, quarantine: null };
    const base = byStore();
    http.expectOne(`${BASE}/stock/products/prod-1/stores`).flush({
      ...base,
      rows: [{ ...base.rows[0], ...hidden }, base.rows[1]],
      total: { ...base.total, ...hidden },
    });
    await settle();
    const central = Array.from(el.querySelectorAll('tbody tr')[0].querySelectorAll('td')).map((c) => c.textContent?.trim());
    expect(central.slice(1, 5)).toEqual(['—', '—', '—', '—']);
    expect(text(el, 'status-store-central')).toBe('OK');
    const total = Array.from(q(el, 'unit-total')!.querySelectorAll('td')).map((c) => c.textContent?.trim());
    expect(total.slice(1, 5)).toEqual(['—', '—', '—', '—']);
  });

  it('shows "Could not load. Try again." with a retry', async () => {
    const el = await open();
    await pick();
    http.expectOne(`${BASE}/stock/products/prod-1/stores`).flush(null, { status: 500, statusText: 'Server Error' });
    await settle();
    expect(text(el, 'stock-error')).toContain('Could not load. Try again.');
  });
});
