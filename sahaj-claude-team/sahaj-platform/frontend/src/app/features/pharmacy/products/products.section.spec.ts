import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { By } from '@angular/platform-browser';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import {
  GLOBAL_ADOPTED,
  GLOBAL_NOT_ADOPTED,
  OWN_PRODUCT,
  OWNER_CONTEXT,
  PHARMACIST_CONTEXT,
  productDetail,
  productRow,
} from '../pharmacy.fixtures';
import { PharmacyContext, ProductRow } from '../pharmacy.model';
import { OwnProductFormComponent } from './own-product-form.component';
import { ProductsSection } from './products.section';

const BASE = '/api/v1/pharmacy';
const url = (r: { url: string }) => r.url === `${BASE}/products`;

function page(items: ProductRow[], total = items.length, pageNo = 0) {
  return { items, total, page: pageNo, size: 25 };
}

describe('ProductsSection', () => {
  let fixture: ComponentFixture<ProductsSection>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext, first = page([GLOBAL_ADOPTED, GLOBAL_NOT_ADOPTED, OWN_PRODUCT])) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(ProductsSection);
    fixture.detectChanges();
    const first$ = http.expectOne(url);
    expect(first$.request.params.has('q')).toBe(false);
    expect(first$.request.params.get('page')).toBe('0');
    expect(first$.request.params.get('size')).toBe('25');
    first$.flush(first);
    // The card "Create own product" reads the unit and form lists; a reader of setup has no card.
    http.match(`${BASE}/product-options`).forEach((r) => r.flush({ units: ['Box', 'Vial'], forms: ['injection'] }));
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
  const me = () => fixture.componentInstance as unknown as { query: { setValue(v: string): void }; search(): void };

  function cardForm(): OwnProductFormComponent & { form: OwnProductFormComponent['form'] } {
    return fixture.debugElement.query(By.directive(OwnProductFormComponent)).componentInstance;
  }

  function dialogReturns(value: unknown) {
    return vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(value) } as never);
  }

  afterEach(() => http.verify());

  it('draws the Product master card with the legend chips, the search box and the mockup columns', async () => {
    const el = await open(OWNER_CONTEXT);
    expect(el.querySelector('#pm-h')?.textContent).toBe('Product master');
    expect(el.textContent).toContain('Global · shared by all units');
    expect(el.textContent).toContain('Own · this unit only');
    expect(el.textContent).toContain('Searches global + own products');
    const headers = Array.from(el.querySelectorAll('[data-testid="product-table"] th')).map((h) => h.textContent?.trim());
    expect(headers).toEqual([
      'Source',
      'Product',
      'Salt · strength · form',
      'UOM (buy → issue)',
      'HSN · GST',
      'Flags',
      'In this unit',
      'Actions',
    ]);
  });

  it('draws a global row as in the mockup, with the sample note', async () => {
    const el = await open(OWNER_CONTEXT);
    const row = q(el, 'product-G-000101') as HTMLElement;
    const cells = Array.from(row.querySelectorAll('td')).map((c) => c.textContent?.replace(/\s+/g, ' ').trim());
    expect(cells[0]).toBe('Global');
    expect(cells[1]).toContain('Sample Ceftriaxone 1 g injection');
    expect(cells[1]).toContain('G-000101 · Sample Labs (fake)');
    expect(cells[1]).toContain('Sample · not clinically validated');
    expect(cells[2]).toBe('Ceftriaxone · 1 g · injection');
    expect(cells[3]).toBe('Box of 10 → vial');
    expect(cells[4]).toBe('3004 · 12%');
    expect(cells[5]).toBe('H');
    expect(cells[6]).toBe('Not adopted');
  });

  it('draws an own row with "made in-house", "Kit → kit", "—" for no schedule and Active', async () => {
    const el = await open(OWNER_CONTEXT);
    const cells = Array.from((q(el, 'product-U-TCB-0001') as HTMLElement).querySelectorAll('td')).map((c) =>
      c.textContent?.replace(/\s+/g, ' ').trim(),
    );
    expect(cells[0]).toBe('Own');
    expect(cells[1]).toContain('U-TCB-0001 · made in-house');
    expect(cells[1]).not.toContain('Sample ·');
    expect(cells[3]).toBe('Kit → kit');
    expect(cells[5]).toBe('—');
    expect(cells[6]).toBe('Active');
  });

  it('gives the owner Adopt, Local settings, Edit and Create own product', async () => {
    const el = await open(OWNER_CONTEXT);
    expect(q(el, 'adopt-G-000101')).toBeTruthy();
    expect(q(el, 'adopt-G-000102')).toBeNull();
    expect(q(el, 'local-G-000102')).toBeTruthy();
    expect(q(el, 'edit-U-TCB-0001')).toBeTruthy();
    expect(q(el, 'local-U-TCB-0001')).toBeTruthy();
    expect(q(el, 'create-product')).toBeTruthy();
  });

  it('hides every change button from a reader of setup, who still sees the same list', async () => {
    const el = await open(PHARMACIST_CONTEXT);
    expect(q(el, 'product-G-000101')).toBeTruthy();
    for (const id of ['adopt-G-000101', 'local-G-000102', 'edit-U-TCB-0001', 'create-product']) {
      expect(q(el, id)).toBeNull();
    }
  });

  it('adopts a global product, then reads the page again and says so', async () => {
    const el = await open(OWNER_CONTEXT);
    (q(el, 'adopt-G-000101') as HTMLButtonElement).click();
    const post = http.expectOne(`${BASE}/catalogue-products/cat-1/adopt`);
    expect(post.request.method).toBe('POST');
    post.flush(productDetail(), { status: 201, statusText: 'Created' });
    http
      .expectOne(url)
      .flush(page([{ ...GLOBAL_NOT_ADOPTED, productId: 'p1', inUnit: 'ADOPTED' }, GLOBAL_ADOPTED, OWN_PRODUCT]));
    await settle();
    expect(q(el, 'adopt-G-000101')).toBeNull();
    expect(q(el, 'local-G-000101')).toBeTruthy();
  });

  it('shows the server sentence when adopting is refused (withdrawn product)', async () => {
    const el = await open(OWNER_CONTEXT);
    (q(el, 'adopt-G-000101') as HTMLButtonElement).click();
    http.expectOne(`${BASE}/catalogue-products/cat-1/adopt`).flush(
      {
        type: 'https://sahaj.in/problems/product-withdrawn',
        title: 'Withdrawn',
        status: 409,
        detail: 'This product was withdrawn from the catalogue.',
      },
      { status: 409, statusText: 'Conflict' },
    );
    http.expectOne(url).flush(page([GLOBAL_NOT_ADOPTED]));
    await settle();
    expect(text(el, 'product-action-error')).toContain('This product was withdrawn from the catalogue.');
  });

  it('searches 2 or more characters after a short pause, with q, and shows the first page', async () => {
    const el = await open(OWNER_CONTEXT);
    me().query.setValue('ceftriaxone');
    await new Promise((resolve) => setTimeout(resolve, 320));
    const req = http.expectOne(url);
    expect(req.request.params.get('q')).toBe('ceftriaxone');
    expect(req.request.params.get('page')).toBe('0');
    req.flush(page([GLOBAL_NOT_ADOPTED]));
    await settle();
    expect(el.querySelectorAll('[data-testid="product-table"] tbody tr').length).toBe(1);
  });

  it('sends no q for fewer than 2 characters (first page unfiltered)', async () => {
    await open(OWNER_CONTEXT);
    me().query.setValue('c');
    await new Promise((resolve) => setTimeout(resolve, 320));
    const req = http.expectOne(url);
    expect(req.request.params.has('q')).toBe(false);
    req.flush(page([GLOBAL_NOT_ADOPTED]));
  });

  it('says "No products match. Try the salt name." when a search finds nothing', async () => {
    const el = await open(OWNER_CONTEXT);
    me().query.setValue('zzzz');
    await new Promise((resolve) => setTimeout(resolve, 320));
    http.expectOne(url).flush(page([]));
    await settle();
    expect(text(el, 'products-none-match')).toBe('No products match. Try the salt name.');
  });

  it('says what to do next when there are no products at all, one line for the owner and a shorter one for a reader', async () => {
    const owner = await open(OWNER_CONTEXT, page([]));
    expect(text(owner, 'products-empty')).toBe('No products yet. Adopt a global product or create your own.');
    TestBed.resetTestingModule();
    const reader = await open(PHARMACIST_CONTEXT, page([]));
    expect(text(reader, 'products-empty')).toBe('No products yet.');
  });

  it('pages by 25: shows the range and moves on with Next', async () => {
    const items = Array.from({ length: 25 }, (_, i) => productRow({ code: `G-${i}`, catalogueProductId: `c${i}` }));
    const el = await open(OWNER_CONTEXT, page(items, 61));
    expect(text(el, 'product-range')).toBe('Showing 1–25 of 61');
    expect((q(el, 'products-prev') as HTMLButtonElement).disabled).toBe(true);
    (q(el, 'products-next') as HTMLButtonElement).click();
    const req = http.expectOne(url);
    expect(req.request.params.get('page')).toBe('1');
    req.flush(page(items.slice(0, 25), 61, 1));
    await settle();
    expect(text(el, 'product-range')).toBe('Showing 26–50 of 61');
  });

  it('says "Could not load. Try again." with a retry when the list cannot be read', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(OWNER_CONTEXT);
    fixture = TestBed.createComponent(ProductsSection);
    fixture.detectChanges();
    http.expectOne(url).error(new ProgressEvent('error'));
    http.match(`${BASE}/product-options`).forEach((r) => r.flush({ units: [], forms: [] }));
    await settle();
    expect(text(fixture.nativeElement, 'products-error')).toContain('Could not load. Try again.');
  });

  it('draws Create own product as an inline card after the Product master card, not as a sheet', async () => {
    const el = await open(OWNER_CONTEXT);
    const spy = vi.spyOn(TestBed.inject(MatDialog), 'open');
    const card = q(el, 'create-product') as HTMLElement;
    expect(card.tagName).toBe('SECTION');
    expect(card.querySelector('h2')?.textContent).toBe('Create own product');
    expect(card.getAttribute('aria-labelledby')).toBe('np-h');
    const sections = Array.from(el.querySelectorAll('section'));
    expect(sections.map((s) => s.querySelector('h2')?.textContent)).toEqual(['Product master', 'Create own product']);
    expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('Create own product'))).toBe(false);
    const labels = Array.from(card.querySelectorAll('mat-label')).map((l) => l.textContent?.trim());
    expect(labels.slice(0, 6)).toEqual(['Product name', 'Salt / generic', 'Strength', 'Form', 'Purchase unit', 'Issue unit']);
    expect(labels.slice(6)).toEqual(['Issue units per purchase unit', 'Manufacturer', 'HSN', 'GST %', 'Schedule', 'Barcode']);
    expect(card.textContent).toContain('Check the drug schedule before saving');
    expect(q(card, 'save-product')?.textContent?.trim()).toBe('Create product');
    expect(spy).not.toHaveBeenCalled();
  });

  it('creates the product from the card, reads the list again and clears the card', async () => {
    const el = await open(OWNER_CONTEXT);
    const spy = vi.spyOn(TestBed.inject(MatDialog), 'open');
    const child = cardForm();
    child.form.patchValue({
      name: 'Ceftriaxone 1g injection',
      salt: 'Ceftriaxone',
      strength: '1 g',
      form: 'injection',
      purchaseUnit: 'Box of 10',
      issueUnit: 'vial',
      conversion: '10',
      manufacturer: 'Own Labs',
      hsn: '3004',
      gstRate: '12',
      schedule: 'H',
    });
    (q(el, 'save-product') as HTMLButtonElement).click();
    http.expectOne(`${BASE}/products`).flush(productDetail(), { status: 201, statusText: 'Created' });
    http.expectOne(url).flush(page([OWN_PRODUCT]));
    await settle();
    expect(child.form.getRawValue().name).toBe('');
    expect(el.querySelectorAll('[data-testid="product-table"] tbody tr').length).toBe(1);
    expect(spy).not.toHaveBeenCalled();
  });

  it('shows the product the owner chose to use instead of creating one (search by its code)', async () => {
    await open(OWNER_CONTEXT);
    cardForm().finished.emit({ kind: 'use-own', product: null, code: 'U-TCB-0001' });
    await new Promise((resolve) => setTimeout(resolve, 320));
    const req = http.expectOne(url);
    expect(req.request.params.get('q')).toBe('U-TCB-0001');
    req.flush(page([OWN_PRODUCT]));
  });

  it('opens Edit for an own product and Local settings with the product id, never a catalogue id', async () => {
    const el = await open(OWNER_CONTEXT);
    const spy = dialogReturns(null);
    (q(el, 'edit-U-TCB-0001') as HTMLButtonElement).click();
    expect((spy.mock.calls[0][1] as { data: unknown }).data).toEqual({ mode: 'edit', productId: 'prod-own' });
    (q(el, 'local-G-000102') as HTMLButtonElement).click();
    expect((spy.mock.calls[1][1] as { data: unknown }).data).toEqual({
      productId: 'prod-2',
      productName: 'Sample Ceftriaxone 500 mg injection',
    });
    expect((spy.mock.calls[1][1] as { ariaLabelledBy: string }).ariaLabelledBy).toBe('local-settings-title');
  });

  it('puts nothing of the search or the products in the address bar or in local storage', async () => {
    await open(OWNER_CONTEXT);
    me().query.setValue('ceftriaxone');
    await new Promise((resolve) => setTimeout(resolve, 320));
    http.expectOne(url).flush(page([GLOBAL_NOT_ADOPTED]));
    expect(location.search).toBe('');
    expect(localStorage.length).toBe(0);
  });
});
