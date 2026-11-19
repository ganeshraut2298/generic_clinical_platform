import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { productRow } from '../pharmacy.fixtures';
import { PickedProduct, ProductPickerComponent } from './product-picker.component';

const URL = '/api/v1/pharmacy/products';

describe('ProductPickerComponent', () => {
  let fixture: ComponentFixture<ProductPickerComponent>;
  let http: HttpTestingController;
  let picked: PickedProduct[];

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ProductPickerComponent);
    picked = [];
    fixture.componentInstance.picked.subscribe((p) => picked.push(p));
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  async function typeText(text: string): Promise<void> {
    const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('[data-testid="product-search"]')!;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    await new Promise((resolve) => setTimeout(resolve, 350));
    fixture.detectChanges();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const q = (id: string) => (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(`[data-testid="${id}"]`);

  it('does not search under 2 characters', async () => {
    await typeText('c');
    http.expectNone((r) => r.url === URL);
  });

  it('searches the server and offers only products the unit has adopted or owns', async () => {
    await typeText('cef');
    const req = http.expectOne((r) => r.url === URL);
    expect(req.request.params.get('q')).toBe('cef');
    req.flush({
      items: [
        productRow({ code: 'G-1', name: 'Adopted one', productId: 'p1' }),
        productRow({ code: 'G-2', name: 'Not adopted', productId: null, inUnit: 'NOT_ADOPTED' }),
      ],
      total: 2,
      page: 0,
      size: 10,
    });
    await settle();
    expect(q('pick-G-1')).toBeTruthy();
    expect(q('pick-G-2')).toBeNull();
  });

  it('emits the chosen product and clears the list', async () => {
    await typeText('cef');
    http.expectOne((r) => r.url === URL).flush({ items: [productRow({ code: 'G-1', productId: 'p1' })], total: 1, page: 0, size: 10 });
    await settle();
    q('pick-G-1')?.click();
    expect(picked.map((p) => p.productId)).toEqual(['p1']);
    await settle();
    expect(q('product-results')).toBeNull();
  });

  it('says no product was found', async () => {
    await typeText('zzz');
    http.expectOne((r) => r.url === URL).flush({ items: [], total: 0, page: 0, size: 10 });
    await settle();
    expect(q('product-search-empty')?.textContent).toContain('No product found.');
  });

  it('shows "Could not load. Try again." when the search fails', async () => {
    await typeText('cef');
    http.expectOne((r) => r.url === URL).flush(null, { status: 500, statusText: 'Server Error' });
    await settle();
    expect(q('product-search-error')?.textContent).toContain('Could not load. Try again.');
  });
});
