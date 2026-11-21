import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { returnableBill, salesReturn } from './compliance.fixtures';
import { saleContext } from './sale.fixtures';
import { SalesReturnSection } from './sales-return.section';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-ward`;

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

const REASONS = {
  lists: [
    {
      listKey: 'return-condition',
      reasons: [
        { reasonKey: 'return-condition.sealed', label: 'Sealed strip', needsNote: false, fixed: true },
        { reasonKey: 'return-condition.damaged', label: 'Damaged', needsNote: false, fixed: true },
      ],
    },
    { listKey: 'sales-return', reasons: [{ reasonKey: 'sales-return.excess', label: 'Excess bought', needsNote: false, fixed: false }] },
  ],
};

describe('SalesReturnSection', () => {
  let fixture: ComponentFixture<SalesReturnSection>;
  let http: HttpTestingController;

  async function open(): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(saleContext({ salesReturn: true }, ['INVENTORY', 'PHARMACY', 'SALES_RETURN']));
    fixture = TestBed.createComponent(SalesReturnSection);
    fixture.detectChanges();
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush(REASONS);
    http.expectOne(`${S}/second-signers`).flush({ items: [] });
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function findBill(el: HTMLElement, bill = returnableBill()): Promise<void> {
    const input = q(el, 'sr-number') as HTMLInputElement;
    input.value = 'sb/26-27/018422';
    input.dispatchEvent(new Event('input'));
    q(el, 'sr-find')?.click();
    http.expectOne((r) => r.url === `${S}/sales-returns/bills`).flush({
      items: [
        {
          billId: bill.billId,
          billNo: bill.billNo,
          savedAt: bill.savedAt,
          buyerLabel: bill.buyerLabel,
          netPayablePaise: bill.netPayablePaise,
          window: bill.window,
        },
      ],
    });
    http.expectOne(`${S}/sales-returns/bills/${bill.billId}`).flush(bill);
    await settle();
  }

  async function type(el: HTMLElement, id: string, value: string): Promise<void> {
    const input = q(el, id) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  }

  function chooseReason(el: HTMLElement): void {
    const reason = q(el, 'sr-reason') as HTMLSelectElement;
    reason.value = 'sales-return.excess';
    reason.dispatchEvent(new Event('change'));
  }

  afterEach(() => http.verify());

  it('finds the bill by number and shows the card, the window chip and the lines of the mockup', async () => {
    const el = await open();
    await findBill(el);
    expect(text(el, 'sr-card')).toContain('Test Customer One · bill of 14 Nov 2026 · ₹ 642.00');
    expect(text(el, 'sr-window')).toBe('Within 30-day return window');
    expect(text(el, 'sr-allowed-0')).toBe('Yes');
    expect(text(el, 'sr-allowed-1')).toBe('Not returnable');
    expect(q(el, 'sr-qty-1')).toBeNull();
    expect(text(el, 'sr-limit')).toBe('Returns above ₹ 1,000.00 need approval.');
  });

  it('says so when no bill matches', async () => {
    const el = await open();
    const input = q(el, 'sr-number') as HTMLInputElement;
    input.value = 'nope';
    input.dispatchEvent(new Event('input'));
    q(el, 'sr-find')?.click();
    http.expectOne((r) => r.url === `${S}/sales-returns/bills`).flush({ items: [] });
    await settle();
    expect(text(el, 'sr-find-error')).toBe('No bill found. Check the number.');
  });

  it('shows "Return window is over" and no return form', async () => {
    const el = await open();
    await findBill(el, returnableBill({ window: { state: 'OVER', days: 30 } }));
    expect(text(el, 'sr-window')).toBe('Return window is over');
    expect(q(el, 'sr-table')).toBeNull();
  });

  it('refuses a quantity above what is left, and asks for a reason, without calling the server', async () => {
    const el = await open();
    await findBill(el);
    await type(el, 'sr-qty-0', '3');
    q(el, 'sr-submit')?.click();
    await settle();
    expect(el.textContent).toContain('You can return at most 2.');
    await type(el, 'sr-qty-0', '1');
    q(el, 'sr-submit')?.click();
    await settle();
    expect(text(el, 'sr-error')).toBe('Choose a reason.');
    http.expectNone(`${S}/sales-returns`);
  });

  it('sends the lines, the condition, the reason and the refund mode, and shows the credit note', async () => {
    const el = await open();
    await findBill(el);
    await type(el, 'sr-qty-0', '2');
    expect(text(el, 'sr-refund-0')).toBe('₹ 30.70');
    expect(text(el, 'sr-submit')).toBe('Refund ₹ 30.70 & print credit note');
    chooseReason(el);
    q(el, 'sr-submit')?.click();
    const req = http.expectOne(`${S}/sales-returns`);
    expect(req.request.body).toMatchObject({
      billId: 'bill-1',
      reasonKey: 'sales-return.excess',
      lines: [{ billLineId: 'bl-1', qty: 2, conditionKey: 'return-condition.sealed' }],
      refunds: [{ mode: 'UPI', amountPaise: 3070 }],
      secondSigner: null,
    });
    req.flush(salesReturn(), { status: 201, statusText: 'Created' });
    await settle();
    expect(text(el, 'sr-result')).toBe('Completed · Credit note CN/26-27/000001 · Refund ₹ 30.70');
  });

  it('offers "Send for approval" above the limit and shows the waiting status', async () => {
    const el = await open();
    await findBill(el, returnableBill({ limitPaise: 1000 }));
    await type(el, 'sr-qty-0', '1');
    expect(text(el, 'sr-submit')).toBe('Send for approval');
    chooseReason(el);
    q(el, 'sr-submit')?.click();
    http
      .expectOne(`${S}/sales-returns`)
      .flush(salesReturn({ status: 'WAITING_APPROVAL', creditNoteNo: null }), { status: 201, statusText: 'Created' });
    await settle();
    expect(text(el, 'sr-result')).toBe('Waiting for approval · Refund ₹ 30.70');
  });
});
