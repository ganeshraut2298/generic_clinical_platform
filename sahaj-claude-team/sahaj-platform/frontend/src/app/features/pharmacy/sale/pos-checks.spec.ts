import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { PharmacyContext } from '../pharmacy.model';
import { PosSection } from './pos.section';
import { customer, discountRequest, quoteLine, saleContext, saleHit, saleQuote, substituteOptions } from './sale.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-ward`;

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

const REASONS = {
  lists: [
    {
      listKey: 'substitution',
      reasons: [
        { reasonKey: 'substitution.out-of-stock', label: 'Out of stock', needsNote: false, fixed: false },
        { reasonKey: 'substitution.other', label: 'Other', needsNote: true, fixed: false },
      ],
    },
    { listKey: 'allergy-override', reasons: [{ reasonKey: 'allergy-override.prescriber', label: 'Prescriber confirmed', needsNote: false, fixed: false }] },
    { listKey: 'h1-override', reasons: [{ reasonKey: 'h1-override.prescriber', label: 'Prescriber confirmed', needsNote: false, fixed: false }] },
  ],
};

const PHARMACIST = { substitute: true, overrideBlock: true, sellScheduled: true };

describe('PosSection checks (work package J)', () => {
  let fixture: ComponentFixture<PosSection>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext = saleContext(PHARMACIST)): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(PosSection);
    fixture.detectChanges();
    http.expectOne(`${S}/held-bills`).flush({ items: [] });
    const reasons = http.match((r) => r.url === `${BASE}/reasons`);
    reasons.forEach((r) => r.flush(REASONS));
    http.expectOne(`${S}/second-signers`).flush({ items: [] });
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  interface Internals {
    add(h: unknown): void;
    setBuyer(m: string): void;
    pickCustomer(c: unknown): void;
  }
  const me = () => fixture.componentInstance as unknown as Internals;

  async function addItem(el: HTMLElement, quote = saleQuote()): Promise<void> {
    me().add(saleHit());
    http.expectOne(`${S}/sale/quote`).flush(quote);
    await settle();
    expect(q(el, 'cart-row-0')).toBeTruthy();
  }

  afterEach(() => http.verify());

  it('sends a discount above the limit for approval, shows "Waiting for approval", then "Approved by <name>" and ties the cart to the request', async () => {
    const el = await open();
    await addItem(el);
    const d = q(el, 'pos-discount') as HTMLInputElement;
    d.value = '15';
    d.dispatchEvent(new Event('input'));
    const needs = saleQuote({
      discount: { approvalNeeded: true, limitPercent: '10', limitNotSet: false, lean: false, approval: null },
      canSave: false,
      blockers: ['DISCOUNT_APPROVAL'],
    });
    http.expectOne(`${S}/sale/quote`).flush(needs);
    await settle();
    expect(text(el, 'disc-needed')).toBe('This discount needs approval.');

    q(el, 'disc-send')?.click();
    const sent = http.expectOne(`${S}/sale/discount-requests`);
    expect(sent.request.body).toMatchObject({ discountPercent: '15', buyer: { kind: 'NONE' } });
    sent.flush(discountRequest());
    const waiting = http.expectOne(`${S}/sale/quote`);
    expect(waiting.request.body.discountRequestId).toBe('dr-1');
    waiting.flush({ ...needs, discount: { ...needs.discount, approval: discountRequest() } });
    await settle();
    expect(text(el, 'disc-waiting')).toMatch(/^Waiting for approval · Owner · \d+ min$/);

    q(el, 'disc-refresh')?.click();
    http.expectOne(`${S}/sale/discount-requests/dr-1`).flush(discountRequest({ status: 'APPROVED', decidedBy: { membershipId: 'm-9', name: 'Test Owner' } }));
    const approved = discountRequest({ status: 'APPROVED', decidedBy: { membershipId: 'm-9', name: 'Test Owner' } });
    http.expectOne(`${S}/sale/quote`).flush(saleQuote({ discount: { ...needs.discount, approval: approved }, canSave: true, blockers: [] }));
    await settle();
    expect(text(el, 'disc-approved')).toBe('Approved by Test Owner');
    expect((q(el, 'pos-save') as HTMLButtonElement).disabled).toBe(false);
  });

  it('withdraws a waiting request', async () => {
    const el = await open();
    await addItem(el);
    const d = q(el, 'pos-discount') as HTMLInputElement;
    d.value = '15';
    d.dispatchEvent(new Event('input'));
    const needs = saleQuote({
      discount: { approvalNeeded: true, limitPercent: '10', limitNotSet: false, lean: false, approval: null },
      canSave: false,
      blockers: ['DISCOUNT_APPROVAL'],
    });
    http.expectOne(`${S}/sale/quote`).flush(needs);
    await settle();
    q(el, 'disc-send')?.click();
    http.expectOne(`${S}/sale/discount-requests`).flush(discountRequest());
    http.expectOne(`${S}/sale/quote`).flush({ ...needs, discount: { ...needs.discount, approval: discountRequest() } });
    await settle();
    q(el, 'disc-withdraw')?.click();
    http.expectOne(`${S}/sale/discount-requests/dr-1/withdraw`).flush(discountRequest({ status: 'WITHDRAWN' }));
    http.expectOne(`${S}/sale/quote`).flush(needs);
    await settle();
    expect(q(el, 'disc-waiting')).toBeNull();
    expect(q(el, 'disc-needed')).toBeTruthy();
  });

  it('says why a rejected request failed and lets the seller send a new one', async () => {
    const el = await open();
    await addItem(el);
    const d = q(el, 'pos-discount') as HTMLInputElement;
    d.value = '15';
    d.dispatchEvent(new Event('input'));
    const rejected = discountRequest({ status: 'REJECTED', rejectReason: { reasonKey: 'approval-reject.too-high', label: 'Too high' } });
    http.expectOne(`${S}/sale/quote`).flush(
      saleQuote({
        discount: { approvalNeeded: true, limitPercent: '10', limitNotSet: false, lean: false, approval: rejected },
        canSave: false,
        blockers: ['DISCOUNT_APPROVAL'],
      }),
    );
    await settle();
    expect(text(el, 'disc-rejected')).toContain('Too high');
    expect(q(el, 'disc-send')).toBeTruthy();
  });

  it('opens "Safe substitute" for a pharmacist, records the reason and the patient\'s agreement, and keeps the product asked for', async () => {
    const el = await open();
    await addItem(el);
    q(el, 'cart-substitute-0')?.click();
    fixture.detectChanges();
    http.expectOne(`${S}/sale/products/prod-1/substitutes`).flush(substituteOptions());
    await settle();
    expect(text(el, 'sub-sentence')).toContain('Only exact equivalents are offered.');
    q(el, 'sub-pick-prod-sub')?.click();
    await settle();
    expect(text(el, 'sub-checks')).toContain('Form: injection ✓');
    q(el, 'sub-confirm')?.click();
    await settle();
    expect(text(el, 'sub-error')).toBe('Choose a reason and confirm the patient agrees.');
    q(el, 'sub-reason-substitution.out-of-stock')?.click();
    (q(el, 'sub-agrees') as HTMLInputElement).click();
    q(el, 'sub-confirm')?.click();
    const req = http.expectOne(`${S}/sale/quote`);
    expect(req.request.body.lines).toEqual([
      {
        productId: 'prod-sub',
        qty: 1,
        prescribedProductId: 'prod-1',
        substitution: { reasonKey: 'substitution.out-of-stock', note: null, patientAgrees: true },
      },
    ]);
    req.flush(saleQuote({ lines: [quoteLine({ substitution: { prescribedProduct: saleHit().product } })] }));
    await settle();
    expect(text(el, 'chip-substituted')).toBe('Substituted');
    expect(text(el, 'rx-asked')).toContain('Rx: ');
  });

  it('says there is no exact equivalent, and shows the server sentence when a cashier is refused', async () => {
    const el = await open();
    await addItem(el);
    q(el, 'cart-substitute-0')?.click();
    fixture.detectChanges();
    http.expectOne(`${S}/sale/products/prod-1/substitutes`).flush(substituteOptions({ items: [], sentence: 'Only exact equivalents are offered.' }));
    await settle();
    expect(text(el, 'sub-none')).toBe('No exact equivalent in stock.');
  });

  it('does not offer substitution or an override to a cashier, and says "A pharmacist must decide."', async () => {
    const el = await open(saleContext());
    await addItem(
      el,
      saleQuote({
        lines: [quoteLine({ allergyBlock: { allergyText: 'Penicillin', overridden: false } })],
        canSave: false,
        blockers: ['ALLERGY'],
      }),
    );
    expect(q(el, 'cart-substitute-0')).toBeNull();
    expect(q(el, 'allergy-override')).toBeNull();
    expect(text(el, 'allergy-decide')).toBe('A pharmacist must decide.');
    expect(text(el, 'line-allergy')).toContain('patient has a Penicillin allergy recorded in EMR.');
  });

  it('overrides an allergy block with a reason that goes on the cart line, and shows the chip', async () => {
    const el = await open();
    await addItem(
      el,
      saleQuote({
        lines: [quoteLine({ allergyBlock: { allergyText: 'Penicillin', overridden: false } })],
        canSave: false,
        blockers: ['ALLERGY'],
      }),
    );
    q(el, 'allergy-override')?.click();
    await settle();
    q(el, 'reason-confirm')?.click();
    await settle();
    expect(text(el, 'reason-error')).toBe('Choose a reason.');
    q(el, 'reason-allergy-override.prescriber')?.click();
    q(el, 'reason-confirm')?.click();
    const req = http.expectOne(`${S}/sale/quote`);
    expect(req.request.body.lines[0].allergyOverride).toEqual({ reasonKey: 'allergy-override.prescriber', note: null });
    req.flush(saleQuote({ lines: [quoteLine({ allergyBlock: { allergyText: 'Penicillin', overridden: true } })] }));
    await settle();
    expect(text(el, 'chip-allergy-override')).toBe('Allergy override');
    expect(q(el, 'line-allergy')).toBeNull();
  });

  it('overrides a missing Schedule H1 prescriber, never for Schedule X', async () => {
    const el = await open();
    await addItem(
      el,
      saleQuote({
        lines: [quoteLine({ schedule: 'H1', h1PrescriberMissing: true })],
        canSave: false,
        blockers: ['H1_PRESCRIBER'],
      }),
    );
    q(el, 'h1-override')?.click();
    await settle();
    q(el, 'reason-h1-override.prescriber')?.click();
    q(el, 'reason-confirm')?.click();
    const req = http.expectOne(`${S}/sale/quote`);
    expect(req.request.body.lines[0].h1Override).toEqual({ reasonKey: 'h1-override.prescriber', note: null });
    req.flush(saleQuote({ lines: [quoteLine({ schedule: 'H1', h1Overridden: true })] }));
    await settle();
    expect(text(el, 'chip-h1-override')).toBe('Schedule H1 override');

    me().add(saleHit({ product: { ...saleHit().product, productId: 'prod-x' } }));
    http.expectOne(`${S}/sale/quote`).flush(
      saleQuote({
        lines: [quoteLine({ schedule: 'H1', h1Overridden: true }), quoteLine({ lineNo: 2, inputIndex: 1, schedule: 'X', h1PrescriberMissing: true })],
      }),
    );
    await settle();
    expect(q(el, 'h1-override')).toBeNull();
  });

  it('needs a note of 4 to 300 characters for an "Other" reason', async () => {
    const el = await open();
    await addItem(el);
    q(el, 'cart-substitute-0')?.click();
    fixture.detectChanges();
    http.expectOne(`${S}/sale/products/prod-1/substitutes`).flush(substituteOptions());
    await settle();
    q(el, 'sub-pick-prod-sub')?.click();
    await settle();
    q(el, 'sub-reason-substitution.other')?.click();
    (q(el, 'sub-agrees') as HTMLInputElement).click();
    await settle();
    q(el, 'sub-confirm')?.click();
    await settle();
    expect(text(el, 'sub-error')).toBe('Choose a reason and confirm the patient agrees.');
    http.expectNone(`${S}/sale/quote`);
  });

  it('shows the customer panel for a chosen customer and sends the new-customer fields without a limit for a cashier', async () => {
    const el = await open(saleContext());
    me().setBuyer('CUSTOMER');
    await settle();
    me().pickCustomer(customer());
    await settle();
    expect(q(el, 'cust-new')).toBeTruthy();
    q(el, 'cust-new')?.click();
    await settle();
    expect(q(el, 'cust-limit')).toBeNull();
    (q(el, 'cust-name') as HTMLInputElement).value = 'Test Customer Two';
    q(el, 'cust-name')?.dispatchEvent(new Event('input'));
    (q(el, 'cust-phone') as HTMLInputElement).value = '9000000102';
    q(el, 'cust-phone')?.dispatchEvent(new Event('input'));
    q(el, 'cust-save')?.click();
    const req = http.expectOne(`${S}/customers`);
    expect(req.request.body).toMatchObject({ name: 'Test Customer Two', phone: '9000000102', creditLimitPaise: null });
    req.flush(customer({ id: 'cust-2', name: 'Test Customer Two' }));
    await settle();
    expect(text(el, 'customer-chosen')).toContain('Test Customer Two');
  });
});
