import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { productDetail } from '../pharmacy.fixtures';
import { PRODUCT_FORM_CONFIG, ProductFormDialog } from './product-form.dialog';

const BASE = '/api/v1/pharmacy';

describe('ProductFormDialog (Edit sheet of an own product)', () => {
  let fixture: ComponentFixture<ProductFormDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { mode: 'edit', productId: 'prod-own' } },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ProductFormDialog);
    fixture.detectChanges();
    http.expectOne(`${BASE}/product-options`).flush({ units: ['Box', 'Vial'], forms: ['injection'] });
    http.expectOne(`${BASE}/products/prod-own`).flush(productDetail());
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);

  afterEach(() => http.verify());

  it('is named by its title and shows the same fields as the card, with the code note', async () => {
    const el = await open();
    expect(PRODUCT_FORM_CONFIG.ariaLabelledBy).toBe('product-form-title');
    expect(el.querySelector('#product-form-title')?.textContent).toBe('Edit own product');
    expect(el.textContent).toContain('The code and the source cannot be changed.');
    expect(el.querySelector('form')?.getAttribute('aria-labelledby')).toBe('product-form-title');
    expect(q(el, 'f-name')).toBeTruthy();
    expect(q(el, 'f-barcode')).toBeTruthy();
    expect(q(el, 'save-product')?.textContent?.trim()).toBe('Save changes');
  });

  it('closes with the saved product after the save, and with nothing on Cancel and on the X', async () => {
    const el = await open();
    (q(el, 'save-product') as HTMLButtonElement).click();
    http.expectOne(`${BASE}/products/prod-own`).flush(productDetail());
    expect(close).toHaveBeenCalledWith(expect.objectContaining({ kind: 'saved' }));
    close.mockClear();
    const buttons = Array.from(el.querySelectorAll('button'));
    buttons.find((b) => b.textContent?.trim() === 'Cancel')?.click();
    buttons.find((b) => b.getAttribute('aria-label') === 'Close')?.click();
    expect(close).toHaveBeenCalledTimes(2);
    expect(close).toHaveBeenNthCalledWith(1, null);
    expect(close).toHaveBeenNthCalledWith(2, null);
  });

  it('is a column capped by the screen whose body scrolls, so Save and Cancel stay in reach (QA-78-5)', async () => {
    const el = await open();
    const host = getComputedStyle(fixture.nativeElement as HTMLElement);
    expect(host.display).toBe('flex');
    expect(host.flexDirection).toBe('column');
    expect(host.maxHeight).toMatch(/^92d?vh$/);
    const body = getComputedStyle(el.querySelector('form.sheet-b') as HTMLElement);
    expect(body.overflowY).toBe('auto');
    expect(body.minHeight).toBe('0px');
    expect(getComputedStyle(el.querySelector('.sheet-f') as HTMLElement).flexShrink).toBe('0');
    expect(getComputedStyle(el.querySelector('.sheet-h') as HTMLElement).flexShrink).toBe('0');
  });
});
