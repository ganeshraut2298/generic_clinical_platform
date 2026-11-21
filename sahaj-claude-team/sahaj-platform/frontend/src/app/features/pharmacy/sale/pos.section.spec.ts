import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { PharmacyContext } from '../pharmacy.model';
import { PosSection } from './pos.section';
import { bill, customer, heldBill, quoteLine, saleContext, saleHit, saleQuote } from './sale.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-ward`;

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

describe('PosSection', () => {
  let fixture: ComponentFixture<PosSection>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext = saleContext(), held = [] as unknown[]): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(PosSection);
    fixture.detectChanges();
    http.expectOne(`${S}/held-bills`).flush({ items: held });
    http.expectOne(`${S}/second-signers`).flush({ items: [{ membershipId: 'm-2', name: 'Test Pharmacist Two', roleLabel: 'Pharmacist' }] });
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  interface Internals {
    search(t: string): void;
    add(h: unknown): void;
    setBuyer(m: string): void;
    pickCustomer(c: unknown): void;
    amounts: () => Record<string, string>;
  }
  const me = () => fixture.componentInstance as unknown as Internals;

  /** Adds the sample product and answers the quote the server is asked for. */
  async function addItem(el: HTMLElement, quote = saleQuote()): Promise<void> {
    me().add(saleHit());
    http.expectOne(`${S}/sale/quote`).flush(quote);
    await settle();
    expect(q(el, 'cart-row-0')).toBeTruthy();
  }

  afterEach(() => http.verify());

  it('shows the bar of the mockup and an empty cart', async () => {
    const el = await open();
    expect(text(el, 'pos-bar')).toBe('New sale · Cashier: Test Ward Nurse · Test Ward 3B store');
    expect(q(el, 'cart-empty')).toBeTruthy();
    expect(q(el, 'sum-empty')).toBeTruthy();
    expect(q(el, 'buyer-patient')).toBeNull();
  });

  it('offers the patient pick only when the context lists PATIENT_LINK', async () => {
    const el = await open(saleContext({}, ['INVENTORY', 'PHARMACY', 'POS', 'PATIENT_LINK']));
    expect(q(el, 'buyer-patient')).toBeTruthy();
  });

  it('searches by text and lists the hits, with the Salt search chip and "Out of stock" that cannot be added', async () => {
    const el = await open();
    me().search('cef');
    const req = http.expectOne((r) => r.url === `${S}/sale/products`);
    expect(req.request.params.get('q')).toBe('cef');
    req.flush({
      items: [
        saleHit({ matchedOnSalt: true }),
        saleHit({ product: { ...saleHit().product, productId: 'prod-9', name: 'Sample Other' }, outOfStock: true, availableQty: 0 }),
      ],
    });
    await settle();
    expect(text(el, 'hit-prod-1')).toContain('Salt search');
    expect(text(el, 'hit-prod-9')).toContain('Out of stock');
    expect((q(el, 'hit-prod-9') as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows a hidden stock figure as a dash', async () => {
    const el = await open();
    me().search('cef');
    http.expectOne((r) => r.url === `${S}/sale/products`).flush({ items: [saleHit({ availableQty: null })] });
    await settle();
    expect(text(el, 'hit-prod-1')).toContain('Counting in progress');
  });

  it('says "No product has this barcode." for an unknown barcode', async () => {
    const el = await open();
    const input = q(el, 'pos-search') as HTMLInputElement;
    input.value = '8900000000099';
    input.dispatchEvent(new Event('input'));
    http.expectOne((r) => r.url === `${S}/sale/products` && r.params.get('q') === '8900000000099').flush({ items: [] });
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    http.expectOne((r) => r.url === `${S}/sale/products` && r.params.get('barcode') === '8900000000099').flush({ items: [] });
    await settle();
    expect(text(el, 'pos-search-note')).toBe('No product has this barcode.');
  });

  it('prices the cart by the server after a change and shows the batch, expiry, quantity, rate, GST and amount', async () => {
    const el = await open();
    await addItem(el, saleQuote({ lines: [quoteLine({ expiresInDays: 45 })] }));
    const row = text(el, 'cart-row-0') ?? '';
    expect(row).toContain('TESTB001');
    expect(row).toContain('FEFO');
    expect(row).toContain('Mar 2028');
    expect(row).toContain('Expires in 45 d');
    expect(row).toContain('₹ 120.00');
    expect(row).toContain('12%');
    expect(row).toContain('₹ 240.00');
    expect(text(el, 'sum-items')).toBe('1 / 2');
    expect(text(el, 'sum-mrp')).toBe('₹ 240.00');
    expect(text(el, 'sum-net')).toBe('₹ 240.00');
  });

  it('says "Only n in stock." for a short line, and does not allow the save', async () => {
    const el = await open();
    await addItem(
      el,
      saleQuote({ lines: [quoteLine({ shortBy: 3, availableQty: 1 })], canSave: false, blockers: ['SHORT'] }),
    );
    expect(text(el, 'cart-short-0')).toBe('Only 1 in stock.');
    expect(q(el, 'blocker-SHORT')).toBeTruthy();
    expect((q(el, 'pos-save') as HTMLButtonElement).disabled).toBe(true);
  });

  it('names an earlier expired batch as blocked', async () => {
    const el = await open();
    await addItem(el, saleQuote({ lines: [quoteLine({ expiredBatchBlocked: 'GM2311' })] }));
    expect(text(el, 'cart-row-0')).toContain('GM2311 expired · blocked');
  });

  it('prices again when the quantity changes, and a late answer of an older quote is ignored', async () => {
    const el = await open();
    await addItem(el);
    const qty = q(el, 'cart-qty-0') as HTMLInputElement;
    qty.value = '5';
    qty.dispatchEvent(new Event('input'));
    const first = http.expectOne(`${S}/sale/quote`);
    expect(first.request.body.lines).toEqual([{ productId: 'prod-1', qty: 5 }]);
    qty.value = '6';
    qty.dispatchEvent(new Event('input'));
    const second = http.expectOne(`${S}/sale/quote`);
    second.flush(saleQuote({ quoteVersion: 'q-6' }));
    first.flush(saleQuote({ quoteVersion: 'q-5' }));
    await settle();
    expect((fixture.componentInstance as unknown as { quote(): { quoteVersion: string } }).quote().quoteVersion).toBe('q-6');
  });

  it('sends the bill-level discount in the cart, and says when it needs approval or the limit is not set', async () => {
    const el = await open();
    await addItem(el);
    const d = q(el, 'pos-discount') as HTMLInputElement;
    d.value = '5';
    d.dispatchEvent(new Event('input'));
    const req = http.expectOne(`${S}/sale/quote`);
    expect(req.request.body.discountPercent).toBe('5');
    req.flush(saleQuote({ discount: { approvalNeeded: true, limitPercent: '2', limitNotSet: false, lean: false, approval: null }, canSave: false, blockers: ['DISCOUNT_APPROVAL'] }));
    await settle();
    expect(text(el, 'disc-needed')).toBe('This discount needs approval.');
    d.value = '10';
    d.dispatchEvent(new Event('input'));
    http.expectOne(`${S}/sale/quote`).flush(saleQuote({ discount: { approvalNeeded: true, limitPercent: null, limitNotSet: true, lean: false, approval: null }, canSave: false }));
    await settle();
    expect(text(el, 'discount-not-set')).toBe('The discount limit is not set. Ask your owner.');
  });

  it('checks that the payments add up to the net payable before it saves', async () => {
    const el = await open();
    await addItem(el);
    const cash = q(el, 'pay-CASH') as HTMLInputElement;
    cash.value = '100';
    cash.dispatchEvent(new Event('input'));
    q(el, 'pos-save')?.click();
    await settle();
    expect(text(el, 'pay-error')).toBe('Payments must add up to ₹ 240.00.');
    http.expectNone(`${S}/bills`);
  });

  it('saves the bill once with the quote version, the expected net, the payments and the buyer, then shows the invoice', async () => {
    const el = await open();
    await addItem(el);
    q(el, 'pay-fill-cash')?.click();
    await settle();
    q(el, 'pos-save')?.click();
    const req = http.expectOne(`${S}/bills`);
    expect(req.request.body).toMatchObject({
      quoteVersion: 'q-1',
      expectedNetPaise: 24000,
      buyer: { kind: 'NONE' },
      lines: [{ productId: 'prod-1', qty: 1 }],
      payments: [{ mode: 'CASH', amountPaise: 24000 }],
      heldBillId: null,
      secondSigner: null,
    });
    req.flush(bill());
    http.expectOne(`${S}/held-bills`).flush({ items: [] });
    await settle();
    expect(text(el, 'pos-saved')).toContain('Bill SB/26-27/018422 saved');
    expect(text(el, 'invoice')).toContain('Drug licence TEST-DL-1');
    expect(text(el, 'invoice')).toContain('GSTIN TEST-GSTIN');
    expect(text(el, 'invoice')).toContain('3004');
    expect(text(el, 'invoice')).toContain('Pharmacist: Test Pharmacist One');
    q(el, 'pos-new')?.click();
    await settle();
    expect(q(el, 'pos')).toBeTruthy();
  });

  it('re-prices and says so when the server answers sale-changed', async () => {
    const el = await open();
    await addItem(el);
    q(el, 'pay-fill-cash')?.click();
    q(el, 'pos-save')?.click();
    http.expectOne(`${S}/bills`).flush(
      { type: 'https://sahaj.in/problems/sale-changed', title: 'Changed', status: 409, detail: 'Prices or batches changed. Check the bill and save again.' },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    expect(text(el, 'pos-error')).toBe('Prices or batches changed. Check the bill and save again.');
    http.expectOne(`${S}/sale/quote`).flush(saleQuote({ quoteVersion: 'q-2' }));
  });

  it('replaces the quote with the one in a sale-changed answer and saves again with its version', async () => {
    const el = await open();
    await addItem(el);
    q(el, 'pay-fill-cash')?.click();
    q(el, 'pos-save')?.click();
    http.expectOne(`${S}/bills`).flush(
      { type: 'https://sahaj.in/problems/sale-changed', title: 'Changed', status: 409, detail: 'x', quote: saleQuote({ quoteVersion: 'q-9' }) },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    expect(text(el, 'pos-error')).toBe('Prices or batches changed. Check the bill and save again.');
    http.expectNone(`${S}/sale/quote`);
    q(el, 'pay-fill-cash')?.click();
    q(el, 'pos-save')?.click();
    const again = http.expectOne(`${S}/bills`);
    expect(again.request.body.quoteVersion).toBe('q-9');
  });

  it('offers Udhar only to a named customer with a credit limit above 0', async () => {
    const el = await open();
    expect(q(el, 'pay-UDHAR')).toBeNull();
    me().setBuyer('CUSTOMER');
    me().pickCustomer(customer());
    await settle();
    expect(q(el, 'pay-UDHAR')).toBeTruthy();
    expect(text(el, 'credit-line')).toBe('Credit limit ₹ 5,000.00 · current due ₹ 0.00');
    me().pickCustomer(customer({ credit: { customerId: 'cust-1', creditLimitPaise: 0, duePaise: 0, creditNoteBalancePaise: 0 } }));
    await settle();
    expect(q(el, 'pay-UDHAR')).toBeNull();
  });

  it('needs a typed name for a walk-in buyer before it saves', async () => {
    const el = await open();
    await addItem(el);
    me().setBuyer('WALK_IN');
    http.expectOne(`${S}/sale/quote`).flush(saleQuote());
    await settle();
    expect((q(el, 'pos-save') as HTMLButtonElement).disabled).toBe(true);
    const name = q(el, 'walkin-name') as HTMLInputElement;
    name.value = 'Test Walk-in';
    name.dispatchEvent(new Event('input'));
    http.expectOne(`${S}/sale/quote`).flush(saleQuote());
    await settle();
    expect((q(el, 'pos-save') as HTMLButtonElement).disabled).toBe(false);
  });

  it('asks for a second signer and a PIN when the cart has a Schedule X line, and for a prescription', async () => {
    const el = await open();
    await addItem(el, saleQuote({ needsSecondSigner: true, needsPrescription: true, needsH1PatientDetails: true }));
    expect(q(el, 'pos-x-note')).toBeTruthy();
    expect(q(el, 'rx-name')).toBeTruthy();
    expect(q(el, 'rx-address')).toBeTruthy();
    q(el, 'pay-fill-cash')?.click();
    q(el, 'pos-save')?.click();
    await settle();
    expect(el.textContent).toContain('Choose the second signer');
    http.expectNone(`${S}/bills`);
  });

  it('holds a bill with its cart and clears the screen', async () => {
    const el = await open();
    await addItem(el);
    q(el, 'pos-hold')?.click();
    const req = http.expectOne(`${S}/held-bills`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toMatchObject({ lines: [{ productId: 'prod-1', qty: 1 }], replacesHeldBillId: null });
    req.flush(heldBill());
    http.expectOne(`${S}/held-bills`).flush({ items: [] });
    await settle();
    expect(text(el, 'pos-note')).toBe('Bill held. Resume it from Held bills.');
    expect(q(el, 'cart-empty')).toBeTruthy();
  });

  it('resumes a held bill into the cart, prices it again, and says when the net changed', async () => {
    const el = await open(saleContext(), [{ ...heldBill(), cart: undefined }]);
    q(el, 'resume-held-1')?.click();
    http.expectOne(`${S}/held-bills/held-1`).flush(heldBill());
    http.expectOne(`${S}/sale/quote`).flush(saleQuote({ summary: { ...saleQuote().summary, netPayablePaise: 25000 } }));
    await settle();
    expect(text(el, 'pos-note')).toBe('Prices or batches changed. Check the bill and save again.');
    expect(q(el, 'cart-row-0')).toBeTruthy();
    q(el, 'pay-fill-cash')?.click();
    q(el, 'pos-save')?.click();
    const bill$ = http.expectOne(`${S}/bills`);
    expect(bill$.request.body.heldBillId).toBe('held-1');
    bill$.flush(bill());
    http.expectOne(`${S}/held-bills`).flush({ items: [] });
  });

  it('discards a held bill with its version', async () => {
    const el = await open(saleContext(), [{ ...heldBill(), cart: undefined }]);
    q(el, 'discard-held-1')?.click();
    http.expectOne(`${S}/held-bills/held-1`).flush(heldBill({ version: 3 }));
    const req = http.expectOne(`${S}/held-bills/held-1/discard`);
    expect(req.request.body).toEqual({ version: 3 });
    req.flush(heldBill({ status: 'DISCARDED' }));
    http.expectOne(`${S}/held-bills`).flush({ items: [] });
    await settle();
    expect(text(el, 'pos-note')).toBe('Held bill discarded.');
  });
});
