import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { SCHEDULE_X_REF, batchRow, productStock } from '../inventory/inventory.fixtures';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { PharmacyContext } from '../pharmacy.model';
import { ConsumptionSection } from './consumption.section';
import { ReturnsDamageSection } from './returns-damage.section';
import { consumption, damageReport, storeContext, storeReturn } from './stockuse.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-ward`;

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

const RETURN_DAMAGE_REASONS = {
  lists: [
    { listKey: 'store-return', reasons: [{ reasonKey: 'store-return.not-needed', label: 'Not needed (order stopped)', needsNote: false, fixed: false }] },
    { listKey: 'damage', reasons: [{ reasonKey: 'damage.leaking', label: 'Damaged / leaking', needsNote: false, fixed: false }] },
  ],
};

describe('ReturnsDamageSection', () => {
  let fixture: ComponentFixture<ReturnsDamageSection>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext = storeContext()): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(ReturnsDamageSection);
    fixture.detectChanges();
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush(RETURN_DAMAGE_REASONS);
    http.expectOne(`${BASE}/stores/${context.workingStoreId}/second-signers`).flush({ items: [] });
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  interface Internals {
    pickReturn(r: unknown): void;
    pickDamage(r: unknown): void;
    ret: () => { qtyError: string | null; reasonError: string | null };
  }
  const me = () => fixture.componentInstance as unknown as Internals;

  afterEach(() => http.verify());

  it('shows both cards at a ward store, with no photo field', async () => {
    const el = await open();
    expect(q(el, 'return-card')).toBeTruthy();
    expect(q(el, 'damage-card')).toBeTruthy();
    expect(el.textContent).not.toMatch(/photo/i);
  });

  it('does not offer "Return to Central Store" at the Central Store, and only write-off after approval', async () => {
    const ctx = storeContext();
    const el = await open({ ...ctx, stores: [{ ...ctx.stores[0], type: 'CENTRAL' }] });
    expect(q(el, 'return-card')).toBeNull();
    me().pickDamage(batchRow());
    await settle();
    expect(q(el, 'after-write-off')).toBeTruthy();
    expect(q(el, 'after-send')).toBeNull();
  });

  it('checks the quantity and the reason, then sends the return once and says the Central Store must accept', async () => {
    const el = await open();
    me().pickReturn(batchRow({ qty: 10 }));
    await settle();
    q(el, 'return-send')?.click();
    await settle();
    expect(text(el, 'return-qty-error')).toBe('Enter a whole number from 1');
    expect(text(el, 'return-reason-error')).toBe('Choose a reason');
    const qty = q(el, 'return-qty') as HTMLInputElement;
    qty.value = '11';
    qty.dispatchEvent(new Event('input'));
    q(el, 'return-send')?.click();
    await settle();
    expect(text(el, 'return-qty-error')).toBe('Enter 1 to 10');
    qty.value = '3';
    qty.dispatchEvent(new Event('input'));
    q(el, 'return-reason-store-return.not-needed')?.click();
    await settle();
    q(el, 'return-send')?.click();
    const req = http.expectOne(`${S}/store-returns`);
    expect(req.request.body).toMatchObject({ batchId: 'batch-1', quantity: 3, reasonKey: 'store-return.not-needed', secondSigner: null });
    req.flush(storeReturn());
    await settle();
    expect(text(el, 'rd-note')).toContain('RET-0311 sent. Waiting for Central Store.');
  });

  it('shows a hidden batch quantity as a dash and lets the server check the limit', async () => {
    const el = await open();
    me().pickReturn(batchRow({ qty: null }));
    await settle();
    const qty = q(el, 'return-qty') as HTMLInputElement;
    qty.value = '5';
    qty.dispatchEvent(new Event('input'));
    q(el, 'return-reason-store-return.not-needed')?.click();
    await settle();
    q(el, 'return-send')?.click();
    http.expectOne(`${S}/store-returns`).flush(
      { type: 'https://sahaj.in/problems/insufficient-stock', title: 'Not enough stock', status: 409, detail: 'Not enough stock.' },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    expect(text(el, 'return-error')).toBeTruthy();
  });

  it('reports damage with the after-approval choice and says the units are in quarantine', async () => {
    const el = await open();
    me().pickDamage(batchRow({ qty: 10 }));
    await settle();
    const qty = q(el, 'damage-qty') as HTMLInputElement;
    qty.value = '2';
    qty.dispatchEvent(new Event('input'));
    q(el, 'damage-reason-damage.leaking')?.click();
    q(el, 'after-send')?.click();
    await settle();
    q(el, 'damage-submit')?.click();
    const req = http.expectOne(`${S}/damage-reports`);
    expect(req.request.body).toMatchObject({ fromState: 'AVAILABLE', quantity: 2, afterApproval: 'SEND_TO_CS', reasonKey: 'damage.leaking' });
    req.flush(damageReport());
    await settle();
    expect(text(el, 'rd-note')).toContain('DMG-0087 reported. Waiting for HOD.');
    expect(text(el, 'rd-note')).toContain('quarantine');
  });

  it('asks for a second signer on a Schedule X batch', async () => {
    const el = await open();
    me().pickDamage(batchRow({ product: SCHEDULE_X_REF, qty: 4 }));
    await settle();
    const qty = q(el, 'damage-qty') as HTMLInputElement;
    qty.value = '1';
    qty.dispatchEvent(new Event('input'));
    q(el, 'damage-reason-damage.leaking')?.click();
    await settle();
    q(el, 'damage-submit')?.click();
    await settle();
    http.expectNone(`${S}/damage-reports`);
    expect(el.textContent).toContain('Choose the second signer');
  });
});

describe('ConsumptionSection', () => {
  let fixture: ComponentFixture<ConsumptionSection>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext = storeContext()): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(ConsumptionSection);
    fixture.detectChanges();
    http.expectOne((r) => r.url === `${S}/consumptions`).flush({ items: [consumption()], total: 1, page: 0, size: 20 });
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush({
      lists: [{ listKey: 'consumption-purpose', reasons: [{ reasonKey: 'consumption-purpose.ward-general-use', label: 'Ward general use', needsNote: false, fixed: false }] }],
    });
    http.expectOne(`${S}/second-signers`).flush({ items: [] });
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  interface Internals { addLine(p: unknown): void; setType(t: string): void }
  const me = () => fixture.componentInstance as unknown as Internals;
  const PICKED = { productId: 'prod-1', code: 'G-1', name: 'Sample Ceftriaxone 1 g injection', strength: '1 g', form: 'Injection' };

  afterEach(() => http.verify());

  it('hides the Patient-wise tab unless the context lists CONSUMPTION_PATIENT', async () => {
    const el = await open();
    expect(q(el, 'type-patient')).toBeNull();
    expect(q(el, 'type-general')).toBeTruthy();
    expect(text(el, 'consumption-acting')).toBe('Acting as Test Ward Nurse · mapped to Test Ward 3B store');
  });

  it('shows the Patient-wise tab when the context lists CONSUMPTION_PATIENT, and lists the entries of today', async () => {
    const el = await open(storeContext({}, ['INVENTORY', 'CONSUMPTION_PATIENT']));
    expect(q(el, 'type-patient')).toBeTruthy();
    expect(text(el, 'consumption-list')).toContain('General');
    fixture.destroy();
  });

  it('shows "Not connected" when the server answers integration-off for the patients', async () => {
    const el = await open(storeContext({}, ['INVENTORY', 'CONSUMPTION_PATIENT']));
    me().setType('PATIENT');
    http.expectOne(`${BASE}/consumption/ordered-by-options`).flush({ items: [] });
    http.expectOne((r) => r.url === `${S}/consumption/admitted-patients`).flush(
      { type: 'https://sahaj.in/problems/integration-off', title: 'Not used in Standalone mode', status: 409, detail: 'This works only when OPD and IPD are connected.' },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    expect(text(el, 'patient-off')).toContain('Not connected');
  });

  it('needs a purpose, then saves a general entry FEFO with the quantity, and reloads the list', async () => {
    const el = await open();
    me().addLine(PICKED);
    http.expectOne(`${S}/stock/products/prod-1`).flush(productStock({ batches: [batchRow({ qty: 8 })] }));
    await settle();
    expect(text(el, 'cons-available-0')).toBe('8');
    const qty = q(el, 'cons-qty-0') as HTMLInputElement;
    qty.value = '9';
    qty.dispatchEvent(new Event('input'));
    q(el, 'consumption-save')?.click();
    await settle();
    expect(el.textContent).toContain('Only 8 available');
    qty.value = '4';
    qty.dispatchEvent(new Event('input'));
    q(el, 'consumption-save')?.click();
    await settle();
    expect(text(el, 'consumption-error')).toBe('Choose the purpose.');
    q(el, 'purpose-consumption-purpose.ward-general-use')?.click();
    await settle();
    q(el, 'consumption-save')?.click();
    const req = http.expectOne(`${S}/consumptions`);
    expect(req.request.body).toMatchObject({
      type: 'GENERAL',
      purposeKey: 'consumption-purpose.ward-general-use',
      lines: [{ productId: 'prod-1', quantity: 4 }],
      admissionId: null,
      orderedBy: null,
    });
    req.flush(consumption());
    http.expectOne((r) => r.url === `${S}/consumptions`).flush({ items: [consumption()], total: 1, page: 0, size: 20 });
    await settle();
    expect(text(el, 'consumption-note')).toBe('Consumption saved. Cost booked to Test Ward 3B store.');
  });

  it('shows a dash for a hidden ward quantity and leaves the limit to the server', async () => {
    const el = await open();
    me().addLine(PICKED);
    http.expectOne(`${S}/stock/products/prod-1`).flush(productStock({ batches: [batchRow({ qty: null })] }));
    await settle();
    expect(text(el, 'cons-available-0')).toBe('—');
  });
});
