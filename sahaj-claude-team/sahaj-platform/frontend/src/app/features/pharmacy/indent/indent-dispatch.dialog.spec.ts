import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Indent } from '../pharmacy-indent.model';
import { DISPATCH_CONFIG, DispatchData, IndentDispatchDialog } from './indent-dispatch.dialog';
import { dispatch, indent, indentLine, SCHEDULE_X_PRODUCT, SECOND_PRODUCT } from './indent.fixtures';

const BASE = '/api/v1/pharmacy';
const C = `${BASE}/stores/store-central`;

const PICK = (qty: number | null) => ({
  productId: 'prod-1',
  requested: 18,
  availableQty: 110,
  shortBy: 0,
  batches: [{ batch: { batchId: 'b1', batchNo: 'TESTB001', expiryDate: '2028-03-31' }, quantity: qty, purchaseRate: '70.5000' }],
});

const CS_REASONS = {
  lists: [
    {
      listKey: 'cs-change',
      reasons: [
        { reasonKey: 'cs-change.short-stock', label: 'Short stock', needsNote: false, fixed: true },
        { reasonKey: 'cs-change.other', label: 'Other', needsNote: true, fixed: true },
      ],
    },
  ],
};

const APPROVED: Partial<Indent> = {
  status: 'APPROVED',
  version: 3,
  lines: [
    indentLine({ approvedQty: 20, remainingQty: 20, csStock: 110 }),
    indentLine({ id: 'line-2', product: SECOND_PRODUCT, requestedQty: 10, approvedQty: 10, remainingQty: 10, csStock: 4 }),
  ],
};

describe('IndentDispatchDialog', () => {
  let fixture: ComponentFixture<IndentDispatchDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(over: Partial<Indent> = APPROVED, data: Partial<DispatchData> = {}): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { storeId: 'store-central', indentId: 'ind-1', canSignScheduleX: false, ...data } },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(IndentDispatchDialog);
    fixture.detectChanges();
    http.expectOne(`${C}/indents/ind-1`).flush(indent(over));
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush(CS_REASONS);
    if (data.canSignScheduleX) {
      http.expectOne(`${C}/second-signers`).flush({ items: [{ membershipId: 'm-2', name: 'Test Pharmacist Two', roleLabel: 'Pharmacist' }] });
    }
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  function answerPicks(el: HTMLElement): void {
    for (const req of http.match((r) => r.url === `${C}/stock/fefo-pick`)) {
      req.flush(PICK(Number(req.request.params.get('quantity'))));
    }
    fixture.detectChanges();
    expect(el).toBeTruthy();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();
  const type = async (el: HTMLElement, id: string, value: string) => {
    const input = q(el, id) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  };

  afterEach(() => {
    http.match((r) => r.url === `${C}/stock/fefo-pick`);
    http.verify();
  });

  it('shows the acting-as line and, per line, the approved, CS stock, Dispatch item "Same" and the FEFO batch as month and year', async () => {
    const el = await open();
    expect(DISPATCH_CONFIG.ariaLabelledBy).toBe('dispatch-title');
    answerPicks(el);
    await settle();
    expect(text(el, 'dispatch-acting-as')).toBe('Acting as Test Ward Nurse · Central Store · can change product and quantity');
    expect(text(el, 'dispatch-stock-0')).toBe('110');
    expect(text(el, 'dispatch-stock-1')).toBe('4');
    expect(text(el, 'dispatch-batch-0')).toBe('TESTB001 · Mar 2028 × 20');
    expect(text(el, 'dispatch-line-0')).toContain('Same');
    expect(text(el, 'dispatch-qty-0')).toBe('');
    expect((q(el, 'dispatch-qty-0') as HTMLInputElement).value).toBe('20');
    expect((q(el, 'dispatch-qty-1') as HTMLInputElement).value).toBe('4');
  });

  it('starts the second line at the stock it has and asks for Back-order or Close balance for the rest', async () => {
    const el = await open();
    answerPicks(el);
    await settle();
    expect(text(el, 'dispatch-balance-1')).toBe('Balance 6');
    expect(q(el, 'dispatch-backorder-1')?.getAttribute('aria-pressed')).toBe('true');
    q(el, 'dispatch-close-1')?.click();
    await settle();
    expect(q(el, 'dispatch-close-1')?.getAttribute('aria-pressed')).toBe('true');
    q(el, 'dispatch-submit')?.click();
    await settle();
    expect(el.textContent).toContain('Choose a reason for the change');
    http.expectNone(`${C}/indents/ind-1/dispatches`);
  });

  it('shows "Counting in progress" for a hidden Central Store stock and sends no number', async () => {
    const el = await open({ ...APPROVED, lines: [indentLine({ approvedQty: 20, remainingQty: 20, csStock: null })] });
    answerPicks(el);
    await settle();
    expect(text(el, 'dispatch-stock-0')).toBe('Counting in progress');
    expect(el.textContent).not.toContain('null');
  });

  it('refuses a quantity above the remaining approved quantity or above the stock without calling the server', async () => {
    const el = await open();
    answerPicks(el);
    await type(el, 'dispatch-qty-0', '25');
    q(el, 'dispatch-submit')?.click();
    await settle();
    expect(el.textContent).toContain('Enter 20 or less');
    await type(el, 'dispatch-qty-1', '9');
    q(el, 'dispatch-submit')?.click();
    await settle();
    expect(el.textContent).toContain('Only 4 in stock at the Central Store');
    http.expectNone(`${C}/indents/ind-1/dispatches`);
  });

  it('disables the dispatch button when every quantity is 0', async () => {
    const el = await open();
    answerPicks(el);
    await type(el, 'dispatch-qty-0', '0');
    await type(el, 'dispatch-qty-1', '0');
    expect((q(el, 'dispatch-submit') as HTMLButtonElement).disabled).toBe(true);
  });

  it('dispatches with a back-order and the reason, then shows the dispatch reference and prints the issue slip', async () => {
    const el = await open();
    answerPicks(el);
    q(el, 'dispatch-reason-1-cs-change.short-stock')?.click();
    await settle();
    q(el, 'dispatch-submit')?.click();
    q(el, 'dispatch-submit')?.click();
    const req = http.expectOne(`${C}/indents/ind-1/dispatches`);
    expect(req.request.body.indentVersion).toBe(3);
    expect(req.request.body.clientKey).toMatch(/[0-9a-f-]{36}/);
    expect(req.request.body.lines).toEqual([
      { indentLineId: 'line-1', dispatchProductId: 'prod-1', quantity: 20, balanceAction: null, changeReasonKey: null, changeNote: null },
      { indentLineId: 'line-2', dispatchProductId: 'prod-2', quantity: 4, balanceAction: 'BACK_ORDER', changeReasonKey: 'cs-change.short-stock', changeNote: null },
    ]);
    req.flush(dispatch({ reference: 'IND-0001 / 1' }), { status: 201, statusText: 'Created' });
    await settle();
    expect(text(el, 'dispatch-done')).toContain('Dispatched as IND-0001 / 1');
    expect(q(el, 'print-issue-slip')).toBeTruthy();
    expect(text(el, 'dispatch-result')).toContain('TESTB001 · Mar 2028');
  });

  it('keeps the quantities and shows the server sentence when stock is no longer there', async () => {
    const el = await open();
    answerPicks(el);
    q(el, 'dispatch-reason-1-cs-change.short-stock')?.click();
    await settle();
    q(el, 'dispatch-submit')?.click();
    http.expectOne(`${C}/indents/ind-1/dispatches`).flush(
      { type: 'https://sahaj.in/problems/insufficient-stock', title: 'Insufficient', status: 409, detail: 'Not enough stock. Nothing was dispatched.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(text(el, 'dispatch-error')).toContain('Not enough stock. Nothing was dispatched.');
    expect((q(el, 'dispatch-qty-0') as HTMLInputElement).value).toBe('20');
    expect(close).not.toHaveBeenCalled();
  });

  it('offers a replacement with its Central Store stock and needs a reason for it', async () => {
    const el = await open();
    answerPicks(el);
    q(el, 'dispatch-change-0')?.click();
    http
      .expectOne(`${C}/indents/ind-1/lines/line-1/replacement-options`)
      .flush({ items: [{ product: SECOND_PRODUCT, csAvailable: 12 }] });
    await settle();
    expect(q(el, 'dispatch-replacement-0')).toBeTruthy();
    (fixture.componentInstance as unknown as { chooseReplacement(i: number, id: string): void }).chooseReplacement(0, 'prod-2');
    answerPicks(el);
    await settle();
    expect(text(el, 'dispatch-stock-0')).toBe('12');
    q(el, 'dispatch-submit')?.click();
    await settle();
    expect(el.textContent).toContain('Choose a reason for the change');
  });

  it('blocks a Schedule X line for a person who is not a pharmacist', async () => {
    const el = await open({
      ...APPROVED,
      lines: [indentLine({ product: SCHEDULE_X_PRODUCT, approvedQty: 5, remainingQty: 5, csStock: 9 })],
    });
    answerPicks(el);
    await settle();
    expect(q(el, 'dispatch-x-blocked')).toBeTruthy();
    q(el, 'dispatch-submit')?.click();
    await settle();
    expect(el.textContent).toContain('Only a pharmacist can dispatch Schedule X');
    http.expectNone(`${C}/indents/ind-1/dispatches`);
  });

  it('asks a pharmacist for the second signer and the PIN, sends them and clears the PIN', async () => {
    const el = await open(
      { ...APPROVED, lines: [indentLine({ product: SCHEDULE_X_PRODUCT, approvedQty: 5, remainingQty: 5, csStock: 9 })] },
      { canSignScheduleX: true },
    );
    answerPicks(el);
    await settle();
    const pin = q(el, 'f-pin') as HTMLInputElement;
    expect(pin.type).toBe('password');
    expect(pin.autocomplete).toBe('off');
    q(el, 'dispatch-submit')?.click();
    await settle();
    expect(el.textContent).toContain('Choose the second signer');
    http.expectNone(`${C}/indents/ind-1/dispatches`);
    (fixture.componentInstance as unknown as { signerId: { set(v: string): void } }).signerId.set('m-2');
    await type(el, 'f-pin', '12');
    q(el, 'dispatch-submit')?.click();
    await settle();
    expect(el.textContent).toContain('Enter a 4-digit PIN.');
    await type(el, 'f-pin', '1234');
    q(el, 'dispatch-submit')?.click();
    const req = http.expectOne(`${C}/indents/ind-1/dispatches`);
    expect(req.request.body.secondSigner).toEqual({ membershipId: 'm-2', pin: '1234' });
    req.flush(
      { type: 'https://sahaj.in/problems/wrong-pin', title: 'Wrong PIN', status: 403, detail: 'The PIN is not right.' },
      { status: 403, statusText: 'Forbidden', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect((q(el, 'f-pin') as HTMLInputElement).value).toBe('');
    expect(text(el, 'dispatch-error')).toContain('The PIN is not right.');
  });
});
