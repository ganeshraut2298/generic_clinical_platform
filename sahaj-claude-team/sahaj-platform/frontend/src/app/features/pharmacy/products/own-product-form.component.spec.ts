import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import type { Mock } from 'vitest';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { productDetail } from '../pharmacy.fixtures';
import { DuplicateCandidate } from '../pharmacy.model';
import { OwnProductFormComponent } from './own-product-form.component';

/** What a test opens: the card (create) or the form of the Edit sheet (edit). */
type ProductFormData = { mode: 'create' } | { mode: 'edit'; productId: string };

const BASE = '/api/v1/pharmacy';

interface Internals {
  form: OwnProductFormComponent['form'];
  save(ignoreDuplicates?: boolean): void;
  checkDuplicates(): void;
  useCandidate(candidate: DuplicateCandidate): void;
  duplicates(): DuplicateCandidate[];
}

const GLOBAL_MATCH: DuplicateCandidate = {
  source: 'GLOBAL',
  catalogueProductId: 'cat-1',
  productId: null,
  code: 'G-000101',
  name: 'Sample Ceftriaxone 1 g injection',
  manufacturer: 'Sample Labs (fake)',
  madeInHouse: false,
  inUnit: 'NOT_ADOPTED',
};

const FILLED = {
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
  schedule: 'H' as const,
};

describe('OwnProductFormComponent', () => {
  let fixture: ComponentFixture<OwnProductFormComponent>;
  let http: HttpTestingController;
  let close: Mock<(result: unknown) => void>;
  let cancel: Mock<() => void>;

  function createForm(data: ProductFormData): ComponentFixture<OwnProductFormComponent> {
    const created = TestBed.createComponent(OwnProductFormComponent);
    created.componentRef.setInput('mode', data.mode);
    if (data.mode === 'edit') {
      created.componentRef.setInput('productId', data.productId);
      created.componentRef.setInput('layout', 'sheet');
    }
    created.componentInstance.finished.subscribe((result) => close(result));
    created.componentInstance.cancelled.subscribe(() => cancel());
    return created;
  }

  async function open(data: ProductFormData, detail = productDetail()): Promise<HTMLElement> {
    close = vi.fn();
    cancel = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = createForm(data);
    fixture.detectChanges();
    http.expectOne(`${BASE}/product-options`).flush({ units: ['Box', 'Vial'], forms: ['injection', 'tablet'] });
    if (data.mode === 'edit') {
      http.expectOne(`${BASE}/products/${data.productId}`).flush(detail);
    }
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const me = () => fixture.componentInstance as unknown as Internals;
  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const CREATE: ProductFormData = { mode: 'create' };

  function problem(status: number, slug: string, detail: string, extra: object = {}) {
    return [
      { type: `https://sahaj.in/problems/${slug}`, title: slug, status, detail, ...extra },
      { status, statusText: slug },
    ] as const;
  }

  afterEach(() => http.verify());

  it('lists the fields in the story order and labels the form by the card title', async () => {
    const el = await open(CREATE);
    expect(el.querySelector('form')?.getAttribute('aria-labelledby')).toBe('own-product-h');
    const labels = Array.from(el.querySelectorAll('mat-label')).map((l) => l.textContent?.trim());
    expect(labels).toEqual([
      'Product name',
      'Salt / generic',
      'Strength',
      'Form',
      'Purchase unit',
      'Issue unit',
      'Issue units per purchase unit',
      'Manufacturer',
      'HSN',
      'GST %',
      'Schedule',
      'Barcode',
    ]);
    expect(el.textContent).toContain('Made in-house');
  });

  it('the card has only a Create product button, the sheet also has Cancel', async () => {
    const card = await open(CREATE);
    expect(Array.from(card.querySelectorAll('button')).some((b) => b.textContent?.trim() === 'Cancel')).toBe(false);
    expect(q(card, 'save-product')?.textContent?.trim()).toBe('Create product');
    http.verify();
    TestBed.resetTestingModule();
    const sheet = await open({ mode: 'edit', productId: 'prod-own' });
    const cancelButton = Array.from(sheet.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Cancel');
    cancelButton?.click();
    expect(cancel).toHaveBeenCalledOnce();
  });

  it('clears the card after a created product, so a second product starts empty with a new client key', async () => {
    const el = await open(CREATE);
    me().form.patchValue({ ...FILLED, barcode: 'BAR-1' });
    me().save();
    const first = http.expectOne(`${BASE}/products`);
    const firstKey = (first.request.body as { clientKey: string }).clientKey;
    first.flush(productDetail(), { status: 201, statusText: 'Created' });
    await settle();
    expect(me().form.getRawValue()).toEqual({
      name: '',
      salt: '',
      strength: '',
      form: '',
      purchaseUnit: '',
      issueUnit: '',
      conversion: '',
      manufacturer: '',
      madeInHouse: false,
      hsn: '',
      gstRate: '',
      schedule: '',
      barcode: '',
    });
    expect(me().form.untouched).toBe(true);
    expect(el.querySelector('mat-error')).toBeNull();
    expect(q(el, 'duplicate-warning')).toBeNull();
    me().form.patchValue(FILLED);
    me().save();
    const second = http.expectOne(`${BASE}/products`);
    expect((second.request.body as { clientKey: string }).clientKey).not.toBe(firstKey);
    second.flush(productDetail());
  });

  it('clears the card and enables the manufacturer again after a made in-house product', async () => {
    await open(CREATE);
    me().form.patchValue({ ...FILLED, manufacturer: '' });
    me().form.controls.madeInHouse.setValue(true);
    me().save();
    http.expectOne(`${BASE}/products`).flush(productDetail());
    expect(me().form.controls.manufacturer.enabled).toBe(true);
    expect(me().form.controls.madeInHouse.value).toBe(false);
  });

  it('has no default schedule and says "Choose the schedule" until one is chosen (BR-16)', async () => {
    const el = await open(CREATE);
    expect(me().form.getRawValue().schedule).toBe('');
    me().form.patchValue({ ...FILLED, schedule: '' });
    me().save();
    await settle();
    expect(el.textContent).toContain('Choose the schedule');
    http.expectNone(`${BASE}/products`);
  });

  it('shows the contract texts for empty and invalid fields and sends nothing', async () => {
    const el = await open(CREATE);
    me().save();
    await settle();
    for (const text of [
      'Enter the product name',
      'Enter the salt',
      'Enter the strength',
      'Enter the form',
      'Enter the purchase unit',
      'Enter the issue unit',
      'Enter a whole number, 1 or more',
      'Enter the manufacturer or choose Made in-house',
      'Enter a 4 to 8 digit HSN',
      'Enter a GST % from 0 to 100',
      'Choose the schedule',
    ]) {
      expect(el.textContent).toContain(text);
    }
    http.expectNone(`${BASE}/products`);
  });

  it('refuses a conversion that is not a whole number of 1 or more, an HSN not 4 to 8 digits and a GST % over 100', async () => {
    await open(CREATE);
    const c = me().form.controls;
    for (const bad of ['0', '1.5', '-2', '1e3', '10001', 'x']) {
      c.conversion.setValue(bad);
      expect(c.conversion.invalid).toBe(true);
    }
    for (const good of ['1', '10', '10000']) {
      c.conversion.setValue(good);
      expect(c.conversion.valid).toBe(true);
    }
    for (const bad of ['123', '123456789', '30a4']) {
      c.hsn.setValue(bad);
      expect(c.hsn.invalid).toBe(true);
    }
    c.hsn.setValue('3004');
    expect(c.hsn.valid).toBe(true);
    for (const bad of ['100.5', '101', '12.345', '-1']) {
      c.gstRate.setValue(bad);
      expect(c.gstRate.invalid).toBe(true);
    }
    for (const good of ['0', '12', '2.5', '100']) {
      c.gstRate.setValue(good);
      expect(c.gstRate.valid).toBe(true);
    }
    c.name.setValue('x'.repeat(81));
    expect(c.name.hasError('maxlength')).toBe(true);
  });

  it('needs the manufacturer unless the product is made in-house, then sends none', async () => {
    await open(CREATE);
    me().form.patchValue({ ...FILLED, manufacturer: '' });
    me().save();
    expect(me().form.controls.manufacturer.hasError('manufacturer')).toBe(true);
    http.expectNone(`${BASE}/products`);
    me().form.controls.madeInHouse.setValue(true);
    expect(me().form.controls.manufacturer.disabled).toBe(true);
    me().save();
    const post = http.expectOne(`${BASE}/products`);
    expect(post.request.body).toMatchObject({ manufacturer: null, madeInHouse: true });
    post.flush(productDetail());
  });

  it('creates the product with every contract field and a client key, and closes with it', async () => {
    await open(CREATE);
    me().form.patchValue({ ...FILLED, barcode: 'BAR-1' });
    me().save();
    const post = http.expectOne(`${BASE}/products`);
    expect(post.request.method).toBe('POST');
    const body = post.request.body as Record<string, unknown>;
    expect(body).toMatchObject({
      name: 'Ceftriaxone 1g injection',
      salt: 'Ceftriaxone',
      strength: '1 g',
      form: 'injection',
      purchaseUnit: 'Box of 10',
      issueUnit: 'vial',
      conversion: 10,
      manufacturer: 'Own Labs',
      madeInHouse: false,
      hsn: '3004',
      gstRate: '12',
      schedule: 'H',
      barcode: 'BAR-1',
      ignoreDuplicates: false,
    });
    expect(body['clientKey']).toMatch(/^[0-9a-f-]{36}$/);
    post.flush(productDetail(), { status: 201, statusText: 'Created' });
    expect(close).toHaveBeenCalledWith({ kind: 'created', product: productDetail() });
  });

  it('disables Create while the request runs, so a double tap sends one request', async () => {
    const el = await open(CREATE);
    me().form.patchValue(FILLED);
    me().save();
    me().save();
    await settle();
    expect((q(el, 'save-product') as HTMLButtonElement).disabled).toBe(true);
    http.expectOne(`${BASE}/products`).flush(productDetail());
  });

  it('keeps what was typed and says the standard line when the save cannot reach the server', async () => {
    const el = await open(CREATE);
    me().form.patchValue(FILLED);
    me().save();
    http.expectOne(`${BASE}/products`).error(new ProgressEvent('error'));
    await settle();
    expect(q(el, 'product-form-error')?.textContent).toContain('Could not save. Check your connection and try again.');
    expect(me().form.getRawValue().name).toBe('Ceftriaxone 1g injection');
    expect(close).not.toHaveBeenCalled();
  });

  it('puts a 400 field message on its field', async () => {
    await open(CREATE);
    me().form.patchValue(FILLED);
    me().save();
    http
      .expectOne(`${BASE}/products`)
      .flush(...problem(400, 'validation', 'Check the form', { fieldErrors: [{ field: 'hsn', message: 'Enter a 4 to 8 digit HSN' }] }));
    await settle();
    expect(me().form.controls.hsn.errors).toEqual({ server: 'Enter a 4 to 8 digit HSN' });
  });

  describe('duplicate check', () => {
    async function leaveFields(): Promise<HTMLElement> {
      const el = await open(CREATE);
      me().form.patchValue(FILLED);
      me().checkDuplicates();
      return el;
    }

    it('runs when salt, strength and form are filled and shows the mockup warning with both buttons', async () => {
      const el = await leaveFields();
      const get = http.expectOne((r) => r.url === `${BASE}/products/duplicates`);
      expect(get.request.params.get('salt')).toBe('Ceftriaxone');
      expect(get.request.params.get('strength')).toBe('1 g');
      expect(get.request.params.get('form')).toBe('injection');
      expect(get.request.params.has('excludeProductId')).toBe(false);
      get.flush({ candidates: [GLOBAL_MATCH] });
      await settle();
      expect(q(el, 'duplicate-message')?.textContent).toBe(
        'Possible duplicate: global product "Sample Ceftriaxone 1 g injection" (G-000101, Sample Labs (fake)) has the same salt, strength and form. Use it instead of creating a new one?',
      );
      expect(q(el, 'use-G-000101')?.textContent?.trim()).toBe('Use global product');
      expect(q(el, 'create-anyway')?.textContent?.trim()).toBe('Create anyway');
    });

    it('does not run until all three fields are filled, and not twice for the same values', async () => {
      await open(CREATE);
      me().form.patchValue({ salt: 'Ceftriaxone', strength: '1 g' });
      me().checkDuplicates();
      http.expectNone((r) => r.url === `${BASE}/products/duplicates`);
      me().form.patchValue({ form: 'injection' });
      me().checkDuplicates();
      http.expectOne((r) => r.url === `${BASE}/products/duplicates`).flush({ candidates: [] });
      me().checkDuplicates();
      http.expectNone((r) => r.url === `${BASE}/products/duplicates`);
    });

    it('"Use global product" adopts it, creates nothing and closes with the adopted product', async () => {
      await leaveFields();
      http.expectOne((r) => r.url === `${BASE}/products/duplicates`).flush({ candidates: [GLOBAL_MATCH] });
      me().useCandidate(GLOBAL_MATCH);
      const adopt = http.expectOne(`${BASE}/catalogue-products/cat-1/adopt`);
      expect(adopt.request.method).toBe('POST');
      adopt.flush(productDetail({ source: 'GLOBAL', inUnit: 'ADOPTED' }));
      http.expectNone(`${BASE}/products`);
      expect(close).toHaveBeenCalledWith(expect.objectContaining({ kind: 'adopted' }));
      expect(me().form.getRawValue().name).toBe('');
    });

    it('"Use <own product>" creates nothing and tells the list which product to show', async () => {
      await leaveFields();
      const own: DuplicateCandidate = { ...GLOBAL_MATCH, source: 'OWN', catalogueProductId: null, productId: 'p', code: 'U-TCB-0001', name: 'Test kit' };
      http.expectOne((r) => r.url === `${BASE}/products/duplicates`).flush({ candidates: [own] });
      me().useCandidate(own);
      http.expectNone(`${BASE}/products`);
      expect(close).toHaveBeenCalledWith({ kind: 'use-own', product: null, code: 'U-TCB-0001' });
    });

    it('"Create anyway" saves with ignoreDuplicates true', async () => {
      const el = await leaveFields();
      http.expectOne((r) => r.url === `${BASE}/products/duplicates`).flush({ candidates: [GLOBAL_MATCH] });
      await settle();
      (q(el, 'create-anyway') as HTMLButtonElement).click();
      const post = http.expectOne(`${BASE}/products`);
      expect((post.request.body as { ignoreDuplicates: boolean }).ignoreDuplicates).toBe(true);
      post.flush(productDetail());
    });

    it('shows the warning when the server finds a duplicate at save (the field check was skipped)', async () => {
      const el = await open(CREATE);
      me().form.patchValue(FILLED);
      me().save();
      http
        .expectOne(`${BASE}/products`)
        .flush(...problem(409, 'possible-duplicate', 'A product with the same salt, strength and form exists.', { candidates: [GLOBAL_MATCH] }));
      http.expectOne((r) => r.url === `${BASE}/products/duplicates`).flush({ candidates: [GLOBAL_MATCH] });
      await settle();
      expect(q(el, 'duplicate-warning')).toBeTruthy();
      expect(close).not.toHaveBeenCalled();
    });

    it('drops the warning when salt, strength or form change', async () => {
      const el = await leaveFields();
      http.expectOne((r) => r.url === `${BASE}/products/duplicates`).flush({ candidates: [GLOBAL_MATCH] });
      await settle();
      expect(q(el, 'duplicate-warning')).toBeTruthy();
      me().form.patchValue({ strength: '500 mg' });
      me().checkDuplicates();
      await settle();
      expect(q(el, 'duplicate-warning')).toBeNull();
      http.expectOne((r) => r.url === `${BASE}/products/duplicates`).flush({ candidates: [] });
    });
  });

  it('puts "That barcode is already used by <product>" on the barcode field', async () => {
    await open(CREATE);
    me().form.patchValue({ ...FILLED, barcode: 'BAR-1' });
    me().save();
    http
      .expectOne(`${BASE}/products`)
      .flush(...problem(409, 'barcode-in-use', 'That barcode is already used by Test kit'));
    await settle();
    expect(me().form.controls.barcode.errors).toEqual({ server: 'That barcode is already used by Test kit' });
  });

  describe('edit', () => {
    const EDIT: ProductFormData = { mode: 'edit', productId: 'prod-own' };

    it('loads the product, sends the version and does not send a clientKey', async () => {
      const el = await open(EDIT);
      expect(el.querySelector('form')?.classList.contains('sheet-b')).toBe(true);
      expect(me().form.getRawValue()).toMatchObject({
        name: 'Test dressing kit (OT)',
        conversion: '1',
        madeInHouse: true,
        schedule: 'NONE',
      });
      me().form.patchValue({ name: 'Test dressing kit (theatre)' });
      me().save();
      const put = http.expectOne(`${BASE}/products/prod-own`);
      expect(put.request.method).toBe('PUT');
      expect(put.request.body).toMatchObject({ version: 2, name: 'Test dressing kit (theatre)', ignoreDuplicates: false });
      expect(Object.keys(put.request.body as object)).not.toContain('clientKey');
      put.flush(productDetail());
      expect(close).toHaveBeenCalledWith(expect.objectContaining({ kind: 'saved' }));
    });

    it('locks the units and the conversion when the product has stock, with the note', async () => {
      const el = await open(EDIT, productDetail({ unitsLocked: true, hasStock: true }));
      expect(me().form.controls.purchaseUnit.disabled).toBe(true);
      expect(me().form.controls.issueUnit.disabled).toBe(true);
      expect(me().form.controls.conversion.disabled).toBe(true);
      expect(q(el, 'units-locked-note')?.textContent).toContain('Locked because this product has stock.');
      me().save();
      const put = http.expectOne(`${BASE}/products/prod-own`);
      expect(put.request.body).toMatchObject({ purchaseUnit: 'Kit', issueUnit: 'kit', conversion: 1 });
      put.flush(productDetail());
    });

    it('asks for a confirmation before a changed schedule is saved, and saves nothing on cancel', async () => {
      await open(EDIT);
      const open$ = vi
        .spyOn(fixture.debugElement.injector.get(MatDialog), 'open')
        .mockReturnValue({ afterClosed: () => of(false) } as never);
      me().form.patchValue({ schedule: 'H1' });
      me().save();
      expect(open$).toHaveBeenCalledOnce();
      const data = open$.mock.calls[0][1]?.data as { body: string };
      expect(data.body).toBe('This changes how the product is sold and recorded. Check the schedule.');
      http.expectNone(`${BASE}/products/prod-own`);
    });

    it('saves the changed schedule once it is confirmed', async () => {
      await open(EDIT);
      vi.spyOn(fixture.debugElement.injector.get(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(true) } as never);
      me().form.patchValue({ schedule: 'H1' });
      me().save();
      http.expectOne(`${BASE}/products/prod-own`).flush(productDetail());
    });

    it('does not ask when the schedule is unchanged', async () => {
      await open(EDIT);
      const open$ = vi.spyOn(fixture.debugElement.injector.get(MatDialog), 'open');
      me().save();
      expect(open$).not.toHaveBeenCalled();
      http.expectOne(`${BASE}/products/prod-own`).flush(productDetail());
    });

    it('says someone else saved and offers the current values on a 409 settings-changed', async () => {
      const el = await open(EDIT);
      me().save();
      http
        .expectOne(`${BASE}/products/prod-own`)
        .flush(...problem(409, 'settings-changed', 'Someone else saved this a moment ago. Check the current values and save again.'));
      await settle();
      expect(q(el, 'product-form-error')?.textContent).toContain('Someone else saved this a moment ago');
      (q(el, 'load-current') as HTMLButtonElement).click();
      http.expectOne(`${BASE}/products/prod-own`).flush(productDetail({ version: 3, name: 'Changed elsewhere' }));
      expect(me().form.getRawValue().name).toBe('Changed elsewhere');
    });

    it('shows the open-import sentence when the units of a product on an opening stock file change (409)', async () => {
      const el = await open(EDIT);
      me().form.patchValue({ purchaseUnit: 'Box' });
      me().save();
      http
        .expectOne(`${BASE}/products/prod-own`)
        .flush(...problem(409, 'open-import', 'Finish or reject the opening stock file of this store first.'));
      await settle();
      expect(q(el, 'product-form-error')?.textContent).toContain('Finish or reject the opening stock file of this store first.');
      expect(close).not.toHaveBeenCalled();
    });

    it('checks for duplicates leaving the product itself out', async () => {
      await open(EDIT);
      me().form.patchValue({ salt: 'Other salt' });
      me().checkDuplicates();
      const get = http.expectOne((r) => r.url === `${BASE}/products/duplicates`);
      expect(get.request.params.get('excludeProductId')).toBe('prod-own');
      get.flush({ candidates: [] });
    });

    it('says "Could not load. Try again." when the product cannot be read', async () => {
      close = vi.fn();
      cancel = vi.fn();
      TestBed.configureTestingModule({
        providers: [
          provideHttpClient(withInterceptors([problemDetailInterceptor])),
          provideHttpClientTesting(),
        ],
      });
      http = TestBed.inject(HttpTestingController);
      fixture = createForm(EDIT);
      fixture.detectChanges();
      http.expectOne(`${BASE}/product-options`).flush({ units: [], forms: [] });
      http.expectOne(`${BASE}/products/prod-own`).error(new ProgressEvent('error'));
      await settle();
      expect(q(fixture.nativeElement, 'product-load-error')?.textContent).toContain('Could not load. Try again.');
      expect((q(fixture.nativeElement, 'save-product') as HTMLButtonElement).disabled).toBe(true);
    });
  });
});
