import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { productStock } from '../inventory/inventory.fixtures';
import { INDENT_FORM_CONFIG, IndentFormData, IndentFormDialog } from './indent-form.dialog';
import { indent } from './indent.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-ward`;

interface Internals {
  add(p: { productId: string; code: string; name: string; strength: string; form: string }): void;
  setQty(index: number, value: string): void;
  save(submit: boolean): void;
  discard(): void;
  priority: { set(v: string): void };
}

const PICKED = { productId: 'prod-1', code: 'G-000101', name: 'Sample Ceftriaxone 1 g injection', strength: '1 g', form: 'Injection' };

describe('IndentFormDialog', () => {
  let fixture: ComponentFixture<IndentFormDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  function open(data: Partial<IndentFormData> = {}): HTMLElement {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            storeId: 'store-ward',
            storeName: 'Test Ward 3B store',
            actingAs: 'Acting as Test Ward Nurse · mapped to Test Ward 3B store · can raise indents for Test Ward 3B store',
            toStoreName: null,
            ...data,
          },
        },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(IndentFormDialog);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const me = () => fixture.componentInstance as unknown as Internals;
  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

  async function addProduct(el: HTMLElement): Promise<void> {
    me().add(PICKED);
    http.expectOne((r) => r.url === `${S}/stock/products/prod-1`).flush(productStock());
    await settle();
    expect(q(el, 'indent-line-0')).toBeTruthy();
  }

  afterEach(() => http.verify());

  it('names the sheet title the dialog config points to and shows the acting-as line, From and To', () => {
    const el = open();
    expect(INDENT_FORM_CONFIG.ariaLabelledBy).toBe('indent-form-title');
    expect(el.querySelector('#indent-form-title')?.textContent).toContain('Raise indent');
    expect(text(el, 'indent-acting-as')).toContain('Acting as Test Ward Nurse · mapped to Test Ward 3B store');
    expect(text(el, 'indent-from')).toBe('Test Ward 3B store');
    expect(text(el, 'indent-to')).toBe('—');
    expect(q(el, 'indent-no-lines')?.textContent).toContain('No items yet');
  });

  it('adds a product with this store stock and refuses the same product twice', async () => {
    const el = open();
    await addProduct(el);
    expect(text(el, 'indent-stock-0')).toBe('40');
    me().add(PICKED);
    await settle();
    expect(text(el, 'indent-duplicate')).toBe('Already on this indent');
    http.expectNone(`${S}/stock/products/prod-1`);
  });

  it('shows "Counting in progress" and no number when the store stock is hidden for a blind count', async () => {
    const el = open();
    me().add(PICKED);
    const stock = productStock();
    http.expectOne((r) => r.url === `${S}/stock/products/prod-1`).flush({
      ...stock,
      batches: stock.batches.map((b) => ({ ...b, qty: null })),
    });
    await settle();
    expect(text(el, 'indent-stock-0')).toBe('Counting in progress');
  });

  it('refuses a quantity that is not a whole number from 1 without calling the server', async () => {
    const el = open();
    await addProduct(el);
    me().setQty(0, '0');
    me().save(false);
    await settle();
    expect(el.textContent).toContain('Enter a whole number from 1 to 999999');
    http.expectNone(`${S}/indents`);
  });

  it('asks for at least one item', async () => {
    const el = open();
    me().save(true);
    await settle();
    expect(text(el, 'indent-no-lines')).toBe('Add at least one item');
    http.expectNone(`${S}/indents`);
  });

  it('saves a draft with one client key', async () => {
    const el = open();
    await addProduct(el);
    me().setQty(0, '20');
    me().priority.set('URGENT');
    me().save(false);
    me().save(false);
    const req = http.expectOne(`${S}/indents`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      clientKey: expect.stringMatching(/[0-9a-f-]{36}/),
      priority: 'URGENT',
      lines: [{ productId: 'prod-1', requestedQty: 20 }],
    });
    req.flush(indent({ status: 'DRAFT', number: null }), { status: 201, statusText: 'Created' });
    await settle();
    expect(close).toHaveBeenCalledWith(expect.objectContaining({ status: 'DRAFT' }));
  });

  it('saves and then submits for approval, and keeps the draft when the submit fails', async () => {
    const el = open();
    await addProduct(el);
    me().setQty(0, '20');
    me().save(true);
    http.expectOne(`${S}/indents`).flush(indent({ status: 'DRAFT', number: null, version: 0 }), { status: 201, statusText: 'Created' });
    const submit = http.expectOne(`${S}/indents/ind-1/submit`);
    expect(submit.request.body.version).toBe(0);
    submit.flush(
      { type: 'https://sahaj.in/problems/validation', title: 'Validation', status: 400, detail: 'Check the form.', fieldErrors: [{ field: 'lines[0]', message: 'Choose a product.' }] },
      { status: 400, statusText: 'Bad Request', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(text(el, 'indent-form-error')).toContain('Choose a product.');
    expect(text(el, 'indent-form-error')).toContain('The draft is kept in the list.');
    expect(close).not.toHaveBeenCalled();
    me().save(true);
    const again = http.expectOne(`${S}/indents/ind-1`);
    expect(again.request.method).toBe('PUT');
    again.flush(indent({ status: 'DRAFT', number: null, version: 1 }));
    http.expectOne(`${S}/indents/ind-1/submit`).flush(indent({ status: 'WAITING_APPROVAL' }));
    await settle();
    expect(close).toHaveBeenCalledWith(expect.objectContaining({ status: 'WAITING_APPROVAL' }));
  });

  it('edits a saved draft and can discard it', async () => {
    const draft = indent({ status: 'DRAFT', number: null, version: 2 });
    const el = open({ indent: draft, toStoreName: 'Test Central Store' });
    expect(el.querySelector('#indent-form-title')?.textContent).toContain('Edit draft indent');
    expect(text(el, 'indent-to')).toBe('Test Central Store');
    expect(q(el, 'indent-line-1')).toBeTruthy();
    expect(q(el, 'indent-discard')).toBeTruthy();
    me().discard();
    const req = http.expectOne(`${S}/indents/ind-1/discard`);
    expect(req.request.body).toEqual({ version: 2 });
    req.flush(indent({ status: 'DISCARDED' }));
    await settle();
    expect(close).toHaveBeenCalledWith(expect.objectContaining({ status: 'DISCARDED' }));
  });
});
