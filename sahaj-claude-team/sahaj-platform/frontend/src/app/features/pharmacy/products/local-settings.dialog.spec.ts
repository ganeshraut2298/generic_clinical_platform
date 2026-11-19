import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { productDetail } from '../pharmacy.fixtures';
import { ACTIVE_VENDOR } from '../pharmacy-c.fixtures';
import { ProductDetail } from '../pharmacy.model';
import { LOCAL_SETTINGS_CONFIG, LocalSettingsData, LocalSettingsDialog } from './local-settings.dialog';

const BASE = '/api/v1/pharmacy';
const DATA: LocalSettingsData = { productId: 'prod-own', productName: 'Test dressing kit (OT)' };

interface Internals {
  form: LocalSettingsDialog['form'];
  stores: LocalSettingsDialog['stores'];
  save(): void;
}

describe('LocalSettingsDialog', () => {
  let fixture: ComponentFixture<LocalSettingsDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(detail: ProductDetail = productDetail()): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: DATA },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(LocalSettingsDialog);
    fixture.detectChanges();
    http.expectOne(`${BASE}/products/prod-own`).flush(detail);
    const vendors = http.expectOne((r) => r.url === `${BASE}/vendors`);
    expect(vendors.request.params.get('status')).toBe('ACTIVE');
    vendors.flush({ items: [ACTIVE_VENDOR], total: 1, page: 0, size: 100 });
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

  function problem(status: number, slug: string, detail: string) {
    return [
      { type: `https://sahaj.in/problems/${slug}`, title: slug, status, detail },
      { status, statusText: slug },
    ] as const;
  }

  afterEach(() => http.verify());

  it('offers the active vendors of the unit in Preferred vendor (story 78-8)', async () => {
    await open();
    const choices = (fixture.componentInstance as unknown as { vendorChoices(): { id: string; name: string }[] }).vendorChoices();
    expect(choices.map((v) => v.id)).toEqual([ACTIVE_VENDOR.id]);
  });

  it('names the sheet and shows the product', async () => {
    const el = await open();
    expect(LOCAL_SETTINGS_CONFIG.ariaLabelledBy).toBe('local-settings-title');
    expect(el.querySelector('#local-settings-title')?.textContent).toBe('Local settings');
    expect(el.textContent).toContain('Test dressing kit (OT)');
  });

  it('shows one reorder level and one rack for each active store, in issue units', async () => {
    const el = await open();
    expect(me().stores.length).toBe(2);
    expect((q(el, 'f-reorder-0') as HTMLInputElement).value).toBe('200');
    expect((q(el, 'f-rack-0') as HTMLInputElement).value).toBe('A-3');
    expect((q(el, 'f-reorder-1') as HTMLInputElement).value).toBe('');
    expect(el.textContent).toContain('Test Central Store');
    expect(el.textContent).toContain('In kit');
  });

  it('offers only "None" as the preferred vendor (vendors come in work package C)', async () => {
    const el = await open();
    expect(q(el, 'f-vendor')).toBeTruthy();
    expect(el.textContent).not.toContain('Preferred vendor needs review');
  });

  it('keeps the vendor already chosen and flags it when it needs review', async () => {
    const detail = productDetail();
    detail.localSettings.preferredVendor = { id: 'ven-1', name: 'Test Vendor One', needsReview: true };
    const el = await open(detail);
    expect(me().form.getRawValue().preferredVendorId).toBe('ven-1');
    expect(q(el, 'vendor-review')?.textContent).toContain('Preferred vendor needs review');
  });

  it('refuses a reorder level of -1 or a decimal, and a rack over 30 characters, with the story texts', async () => {
    const el = await open();
    const [first] = me().stores.controls;
    for (const bad of ['-1', '1.5', 'x', '1000000']) {
      first.controls.reorderLevel.setValue(bad);
      expect(first.controls.reorderLevel.invalid).toBe(true);
    }
    first.controls.reorderLevel.setValue('0');
    expect(first.controls.reorderLevel.valid).toBe(true);
    first.controls.reorderLevel.setValue('-1');
    first.controls.rack.setValue('R'.repeat(31));
    me().save();
    await settle();
    expect(el.textContent).toContain('Enter a whole number, 0 or more');
    expect(el.textContent).toContain('Keep the rack to 30 characters');
    http.expectNone(`${BASE}/products/prod-own/local-settings`);
  });

  it('saves every field of the contract with the version, empty values as null, and closes with the product', async () => {
    await open();
    me().stores.controls[0].patchValue({ reorderLevel: '250', rack: ' B-1 ' });
    me().stores.controls[1].patchValue({ reorderLevel: '', rack: '' });
    me().form.patchValue({ alias: ' ', barcode: 'BAR-9', returnable: false, stop: true });
    me().save();
    const put = http.expectOne(`${BASE}/products/prod-own/local-settings`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({
      version: 2,
      storeSettings: [
        { storeId: 'store-central', reorderLevel: 250, rack: 'B-1' },
        { storeId: 'store-opd', reorderLevel: null, rack: null },
      ],
      preferredVendorId: null,
      alias: null,
      barcode: 'BAR-9',
      returnable: false,
      active: false,
    });
    put.flush(productDetail());
    expect(close).toHaveBeenCalledOnce();
  });

  it('disables Save while the request runs', async () => {
    const el = await open();
    me().save();
    me().save();
    await settle();
    expect((q(el, 'save-local') as HTMLButtonElement).disabled).toBe(true);
    http.expectOne(`${BASE}/products/prod-own/local-settings`).flush(productDetail());
  });

  it('says what the server said for a barcode in use, on the barcode field, and keeps what was typed', async () => {
    const el = await open();
    me().form.patchValue({ barcode: 'BAR-9' });
    me().save();
    http
      .expectOne(`${BASE}/products/prod-own/local-settings`)
      .flush(...problem(409, 'barcode-in-use', 'That barcode is already used by Test kit'));
    await settle();
    expect(me().form.controls.barcode.errors).toEqual({ server: 'That barcode is already used by Test kit' });
    expect(el.textContent).toContain('That barcode is already used by Test kit');
    expect(me().form.getRawValue().barcode).toBe('BAR-9');
    expect(close).not.toHaveBeenCalled();
  });

  it('shows the server sentence when the product has stock and keeps the toggle where it was', async () => {
    const el = await open();
    me().form.patchValue({ stop: true });
    me().save();
    http
      .expectOne(`${BASE}/products/prod-own/local-settings`)
      .flush(...problem(409, 'product-has-stock', 'This product has stock. Clear it in Inventory first.'));
    await settle();
    expect(q(el, 'local-error')?.textContent).toContain('This product has stock. Clear it in Inventory first.');
    expect(me().form.getRawValue().stop).toBe(true);
  });

  it('shows the open-import sentence when a product on an opening stock file is switched off (409)', async () => {
    const el = await open();
    me().form.patchValue({ stop: true });
    me().save();
    http
      .expectOne(`${BASE}/products/prod-own/local-settings`)
      .flush(...problem(409, 'open-import', 'Finish or reject the opening stock file of this store first.'));
    await settle();
    expect(q(el, 'local-error')?.textContent).toContain('Finish or reject the opening stock file of this store first.');
    expect(me().form.getRawValue().stop).toBe(true);
    expect(close).not.toHaveBeenCalled();
  });

  it('offers the current values after a 409 settings-changed', async () => {
    const el = await open();
    me().save();
    http
      .expectOne(`${BASE}/products/prod-own/local-settings`)
      .flush(...problem(409, 'settings-changed', 'Someone else saved this a moment ago. Check the current values and save again.'));
    await settle();
    (q(el, 'load-current') as HTMLButtonElement).click();
    const detail = productDetail({ version: 3 });
    detail.localSettings.alias = 'Changed elsewhere';
    http.expectOne(`${BASE}/products/prod-own`).flush(detail);
    expect(me().form.getRawValue().alias).toBe('Changed elsewhere');
  });

  it('says the standard line when the save cannot reach the server', async () => {
    const el = await open();
    me().save();
    http.expectOne(`${BASE}/products/prod-own/local-settings`).error(new ProgressEvent('error'));
    await settle();
    expect(q(el, 'local-error')?.textContent).toContain('Could not save. Check your connection and try again.');
  });

  it('says "There is no active store yet" when the unit has no store', async () => {
    const detail = productDetail();
    detail.localSettings.storeSettings = [];
    const el = await open(detail);
    expect(q(el, 'no-stores')).toBeTruthy();
  });

  it('is a column capped by the screen whose body scrolls, so Save and Cancel stay in reach (QA-78-5)', async () => {
    const el = await open();
    const host = getComputedStyle(fixture.nativeElement as HTMLElement);
    expect(host.display).toBe('flex');
    expect(host.flexDirection).toBe('column');
    expect(host.maxHeight).toMatch(/^92d?vh$/);
    const body = getComputedStyle(el.querySelector('.sheet-b') as HTMLElement);
    expect(body.overflowY).toBe('auto');
    expect(body.minHeight).toBe('0px');
    expect(getComputedStyle(el.querySelector('.sheet-h') as HTMLElement).flexShrink).toBe('0');
    expect(getComputedStyle(el.querySelector('.sheet-f') as HTMLElement).flexShrink).toBe('0');
  });
});
