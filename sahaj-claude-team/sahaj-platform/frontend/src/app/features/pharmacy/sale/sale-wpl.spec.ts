import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { PharmacyContext } from '../pharmacy.model';
import { StoreAccess } from '../pharmacy.model';
import { activityPage, commandCentre, opdQueue, roleReadout, salesReturnPage, waitingReturn } from './compliance.fixtures';
import { CommandCentreSection } from './command-centre.section';
import { ComplianceCardsSection } from './compliance-cards.section';
import { CustomerPanel } from './customer.panel';
import { OpdQueueSection } from './opd-queue.section';
import { ReturnsWaitingSection } from './returns-waiting.section';
import { customer, saleContext } from './sale.fixtures';
import { waitingText } from './discount-approval.card';
import { discountRequest } from './sale.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-ward`;

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

describe('SAH-80 work package L, N and K screens', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<unknown>;

  function start<T>(component: Type<T>, context: PharmacyContext): T {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    const created = TestBed.createComponent(component);
    fixture = created as unknown as ComponentFixture<unknown>;
    return created.componentInstance;
  }

  async function settle(): Promise<HTMLElement> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const acceptor = (extra: Partial<StoreAccess> = {}) => saleContext({ salesReturn: true, acceptSalesReturn: true, ...extra }, ['INVENTORY', 'PHARMACY', 'SALES_RETURN']);

  afterEach(() => http.verify());

  describe('ReturnsWaitingSection', () => {
    it('lists the returns "Waiting for the pharmacist" and shows "Accept return" to a person who may accept', async () => {
      start(ReturnsWaitingSection, acceptor());
      fixture.detectChanges();
      const req = http.expectOne((r) => r.url === `${S}/sales-returns`);
      expect(req.request.params.get('status')).toBe('WAITING_PHARMACIST');
      req.flush(salesReturnPage());
      http.expectOne(`${S}/second-signers`).flush({ items: [] });
      const el = await settle();
      expect(text(el, 'returns-waiting')).toContain('Waiting for the pharmacist');
      expect(text(el, 'rw-row-SB/26-27/018422')).toContain('₹ 30.70');
      expect(q(el, 'rw-open-SB/26-27/018422')?.textContent).toContain('Accept return');
    });

    it('does not show the accept button to a cashier', async () => {
      start(ReturnsWaitingSection, saleContext({ salesReturn: true }, ['INVENTORY', 'PHARMACY', 'SALES_RETURN']));
      fixture.detectChanges();
      http.expectOne((r) => r.url === `${S}/sales-returns`).flush(salesReturnPage());
      const el = await settle();
      expect(q(el, 'rw-open-SB/26-27/018422')).toBeNull();
    });

    it('accepts sealed goods, refuses a damaged line, and sends the refund again without it', async () => {
      start(ReturnsWaitingSection, acceptor());
      fixture.detectChanges();
      http.expectOne((r) => r.url === `${S}/sales-returns`).flush(salesReturnPage());
      http.expectOne(`${S}/second-signers`).flush({ items: [] });
      const el = await settle();
      q(el, 'rw-open-SB/26-27/018422')?.click();
      http.expectOne(`${S}/sales-returns/sr-9`).flush(waitingReturn());
      await settle();
      expect(text(el, 'rw-refund')).toBe('Refund ₹ 30.70 by upi.');
      q(el, 'rw-refuse-rl-2')?.click();
      await settle();
      expect(text(el, 'rw-refund')).toBe('Refund ₹ 20.00 by upi.');
      q(el, 'rw-accept')?.click();
      const req = http.expectOne(`${S}/sales-returns/sr-9/accept`);
      expect(req.request.body).toMatchObject({
        version: 3,
        decisions: [
          { returnLineId: 'rl-1', decision: 'ACCEPT' },
          { returnLineId: 'rl-2', decision: 'REFUSE' },
        ],
        refunds: [{ mode: 'UPI', amountPaise: 2000 }],
        secondSigner: null,
      });
      req.flush(waitingReturn({ status: 'COMPLETED', creditNoteNo: 'CN/26-27/000002', refundTotalPaise: 2000 }));
      http.expectOne((r) => r.url === `${S}/sales-returns`).flush(salesReturnPage({ items: [], total: 0 }));
      await settle();
      expect(text(el, 'rw-done')).toContain('Completed');
      expect(text(el, 'rw-done')).toContain('CN/26-27/000002');
      expect(text(el, 'rw-empty')).toBe('No returns are waiting.');
    });

    it('asks for the second signer on a Schedule X line before it calls the server', async () => {
      start(ReturnsWaitingSection, acceptor());
      fixture.detectChanges();
      http.expectOne((r) => r.url === `${S}/sales-returns`).flush(salesReturnPage());
      http.expectOne(`${S}/second-signers`).flush({ items: [{ membershipId: 'm-2', name: 'Test Pharmacist Two', roleLabel: 'Pharmacist' }] });
      const el = await settle();
      q(el, 'rw-open-SB/26-27/018422')?.click();
      const sr = waitingReturn();
      sr.lines[0] = { ...sr.lines[0], product: { ...sr.lines[0].product, schedule: 'X' } };
      http.expectOne(`${S}/sales-returns/sr-9`).flush(sr);
      await settle();
      expect(q(el, 'rw-x-note')).toBeTruthy();
      q(el, 'rw-accept')?.click();
      await settle();
      http.expectNone(`${S}/sales-returns/sr-9/accept`);
    });

    it('shows a retry when the list cannot be read', async () => {
      start(ReturnsWaitingSection, acceptor());
      fixture.detectChanges();
      http
        .expectOne((r) => r.url === `${S}/sales-returns`)
        .flush({ type: 'https://sahaj.in/problems/database-busy', title: 'Busy', status: 503 }, { status: 503, statusText: 'Unavailable' });
      http.expectOne(`${S}/second-signers`).flush({ items: [] });
      const el = await settle();
      expect(q(el, 'rw-error')).toBeTruthy();
    });
  });

  describe('ComplianceCardsSection', () => {
    it('draws the read-out the server sends, with its rule text and no editor, and the audit log newest first', async () => {
      start(ComplianceCardsSection, saleContext());
      fixture.detectChanges();
      http.expectOne(`${BASE}/compliance/role-readout`).flush(roleReadout());
      http.expectOne((r) => r.url === `${BASE}/activity`).flush(activityPage());
      const el = await settle();
      expect(text(el, 'role-DISCOUNT_ABOVE_LIMIT-PHARMACIST')).toBe('Needs approval above 10%');
      expect(text(el, 'role-CANCEL_SAVED_BILL-PHARMACIST')).toBe('Same day only');
      expect(text(el, 'role-CANCEL_SAVED_BILL-CASHIER')).toBe('Deny');
      expect(text(el, 'role-sentence')).toBe('These are the rules in force. The owner changes them in Inventory setup.');
      expect(text(el, 'log-list')).toContain('Test Pharmacist One (Pharmacist) overrode allergy block on bill SB/26-27/018419');
      expect(text(el, 'audit-log')).toContain('Audit log');
      expect(text(el, 'audit-log')).not.toContain('tamper');
    });

    it('filters the log by type and date and pages it', async () => {
      start(ComplianceCardsSection, saleContext());
      fixture.detectChanges();
      http.expectOne(`${BASE}/compliance/role-readout`).flush(roleReadout());
      http.expectOne((r) => r.url === `${BASE}/activity`).flush(activityPage({ total: 60 }));
      const el = await settle();
      const type = q(el, 'log-type') as HTMLSelectElement;
      type.value = 'BILL_CANCELLED';
      type.dispatchEvent(new Event('change'));
      q(el, 'log-show')?.click();
      const req = http.expectOne((r) => r.url === `${BASE}/activity`);
      expect(req.request.params.get('type')).toBe('BILL_CANCELLED');
      expect(req.request.params.get('page')).toBe('0');
      req.flush(activityPage({ items: [], total: 0 }));
      await settle();
      expect(text(el, 'log-empty')).toBe('No records for this filter.');
    });

    it('refuses a range over 12 months and says so', async () => {
      start(ComplianceCardsSection, saleContext());
      fixture.detectChanges();
      http.expectOne(`${BASE}/compliance/role-readout`).flush(roleReadout());
      http.expectOne((r) => r.url === `${BASE}/activity`).flush(activityPage());
      const el = await settle();
      const from = q(el, 'log-from') as HTMLInputElement;
      from.value = '2024-01-01';
      from.dispatchEvent(new Event('input'));
      q(el, 'log-show')?.click();
      await settle();
      expect(text(el, 'log-range-error')).toBe('Choose at most 12 months.');
    });

    it('shows the retry line when the read-out or the log fails', async () => {
      start(ComplianceCardsSection, saleContext());
      fixture.detectChanges();
      const busy = { type: 'https://sahaj.in/problems/forbidden', title: 'No', status: 403, detail: 'You cannot open this.' };
      http.expectOne(`${BASE}/compliance/role-readout`).flush(busy, { status: 403, statusText: 'Forbidden' });
      http.expectOne((r) => r.url === `${BASE}/activity`).flush(busy, { status: 403, statusText: 'Forbidden' });
      const el = await settle();
      expect(text(el, 'role-error')).toContain('You cannot open this.');
      expect(q(el, 'log-error')).toBeTruthy();
    });
  });

  describe('OpdQueueSection', () => {
    it('lists the queue with counts when the OPD port is connected', async () => {
      start(OpdQueueSection, saleContext({}, ['INVENTORY', 'PHARMACY', 'OPD_QUEUE']));
      fixture.detectChanges();
      http.expectOne(`${S}/opd-queue`).flush(
        opdQueue({
          connected: true,
          total: 1,
          counts: { new: 1, picking: 0, ready: 0, needsAction: 0 },
          items: [{ rxId: 'rx-1', token: 'T-041', patientName: 'Test Patient One', doctorName: 'Dr. Test', itemCount: 4, waitingMinutes: 7, status: 'NEW', readyMinutes: null }],
        }),
      );
      const el = await settle();
      expect(text(el, 'opd-counts')).toContain('New 1');
      expect(text(el, 'opd-row-T-041')).toContain('Test Patient One');
      expect(text(el, 'opd-row-T-041')).toContain('7 min');
      expect(q(el, 'dispensing-not-connected')).toBeNull();
    });

    it('says "Not connected" and lists no rows while the port is not connected, and an empty queue says so', async () => {
      start(OpdQueueSection, saleContext({}, ['INVENTORY', 'PHARMACY', 'OPD_QUEUE']));
      fixture.detectChanges();
      http.expectOne(`${S}/opd-queue`).flush(opdQueue());
      const el = await settle();
      expect(text(el, 'dispensing-not-connected')).toContain('Not connected');
      expect(q(el, 'opd-table')).toBeNull();
    });

    it('says the queue could not be loaded, with a retry, and calls nothing when only the IPD queue is listed', async () => {
      start(OpdQueueSection, saleContext({}, ['INVENTORY', 'PHARMACY', 'OPD_QUEUE']));
      fixture.detectChanges();
      http.expectOne(`${S}/opd-queue`).flush({ type: 'https://sahaj.in/problems/x', title: 'x', status: 500 }, { status: 500, statusText: 'Error' });
      const el = await settle();
      expect(text(el, 'opd-error')).toContain('Prescriptions could not be loaded. Try again.');
    });
  });

  describe('CommandCentreSection', () => {
    it('shows only the sales tile for a cashier, and "+ New bill"', async () => {
      start(CommandCentreSection, saleContext({ receive: false }, ['INVENTORY', 'PHARMACY', 'POS', 'COMMAND_CENTRE']));
      fixture.detectChanges();
      http.expectOne((r) => r.url === `${BASE}/command-centre`).flush(
        commandCentre({ grossMargin: undefined, nearExpiry90: undefined, reorder: undefined, rxWaiting: undefined, udhar: undefined, expiryRisk: undefined, livePrescriptions: undefined, compliance: undefined }),
      );
      const el = await settle();
      expect(text(el, 'tile-sales')).toContain('142 bills · avg ₹ 339.86');
      expect(q(el, 'tile-udhar')).toBeNull();
      expect(q(el, 'cc-compliance')).toBeNull();
      expect(q(el, 'new-bill')).toBeTruthy();
      expect(q(el, 'purchase-entry')).toBeNull();
    });

    it('shows Bill and Issue links on live prescriptions only when the person can open dispensing, and emits', async () => {
      const items = [
        { source: 'OPD' as const, id: 'rx-1', at: '2026-11-21T05:12:00Z', patientName: 'Test Patient', itemCount: 4, availability: 'All in stock', action: 'BILL' as const },
        { source: 'IPD' as const, id: 'rx-2', at: '2026-11-21T05:01:00Z', patientName: 'Ward 3B', itemCount: 14, availability: '2 short', action: 'ISSUE' as const },
      ];
      const cc = start(CommandCentreSection, saleContext({}, ['INVENTORY', 'PHARMACY', 'POS', 'COMMAND_CENTRE', 'OPD_QUEUE']));
      let opened = 0;
      cc.openDispensing.subscribe(() => opened++);
      fixture.detectChanges();
      http.expectOne((r) => r.url === `${BASE}/command-centre`).flush(commandCentre({ livePrescriptions: { state: 'OK', items } }));
      const el = await settle();
      expect(text(el, 'live-rx-open-rx-1')).toBe('Bill');
      expect(text(el, 'live-rx-open-rx-2')).toBe('Issue');
      q(el, 'live-rx-open-rx-1')?.click();
      expect(opened).toBe(1);
    });

    it('draws no live prescription links without the OPD or IPD queue capability', async () => {
      const items = [{ source: 'OPD' as const, id: 'rx-1', at: '2026-11-21T05:12:00Z', patientName: 'Test Patient', itemCount: 4, availability: 'All in stock', action: 'BILL' as const }];
      start(CommandCentreSection, saleContext({}, ['INVENTORY', 'PHARMACY', 'POS', 'COMMAND_CENTRE']));
      fixture.detectChanges();
      http.expectOne((r) => r.url === `${BASE}/command-centre`).flush(commandCentre({ livePrescriptions: { state: 'OK', items } }));
      const el = await settle();
      expect(q(el, 'live-rx-open-rx-1')).toBeNull();
    });

    it('says "No sales yet today." and keeps the other tiles when only the sales tile is empty', async () => {
      start(CommandCentreSection, saleContext({}, ['INVENTORY', 'PHARMACY', 'POS', 'COMMAND_CENTRE']));
      fixture.detectChanges();
      http.expectOne((r) => r.url === `${BASE}/command-centre`).flush(commandCentre({ todaysSales: { state: 'EMPTY' } }));
      const el = await settle();
      expect(text(el, 'tile-sales-empty')).toBe('No sales yet today.');
      expect(text(el, 'tile-margin')).toContain('On landed cost, not PTR');
      expect(text(el, 'tile-rx-sub')).toContain('Not connected');
    });

    it('lets the owner read all selling stores, which leaves storeId out of the call, and refreshes', async () => {
      const ctx = saleContext({}, ['INVENTORY', 'PHARMACY', 'POS', 'COMMAND_CENTRE']);
      ctx.caller = { ...ctx.caller, role: 'OWNER' };
      start(CommandCentreSection, ctx);
      fixture.detectChanges();
      http.expectOne((r) => r.url === `${BASE}/command-centre`).flush(commandCentre());
      const el = await settle();
      const pick = q(el, 'cc-store') as HTMLSelectElement;
      pick.value = 'ALL';
      pick.dispatchEvent(new Event('change'));
      const all = http.expectOne((r) => r.url === `${BASE}/command-centre`);
      expect(all.request.params.has('storeId')).toBe(false);
      all.flush(commandCentre({ store: null }));
      await settle();
      expect(text(el, 'cc-dateline')).toContain('All selling stores');
      q(el, 'cc-refresh')?.click();
      http.expectOne((r) => r.url === `${BASE}/command-centre`).flush(commandCentre({ store: null }));
    });

    it('says "Could not load. Try again." when the whole read fails', async () => {
      start(CommandCentreSection, saleContext({}, ['INVENTORY', 'PHARMACY', 'POS', 'COMMAND_CENTRE']));
      fixture.detectChanges();
      http.expectOne((r) => r.url === `${BASE}/command-centre`).flush({ type: 'https://sahaj.in/problems/x', title: 'x', status: 500 }, { status: 500, statusText: 'Error' });
      const el = await settle();
      expect(q(el, 'cc-error')).toBeTruthy();
    });
  });

  describe('CustomerPanel', () => {
    function panel(due = 5000): CustomerPanel {
      const c = start(CustomerPanel, saleContext());
      (fixture as ComponentFixture<CustomerPanel>).componentRef.setInput(
        'customer',
        customer({ credit: { customerId: 'cust-1', creditLimitPaise: 500000, duePaise: due, creditNoteBalancePaise: 0 } }),
      );
      return c;
    }

    it('receives a payment against udhar, prints the slip and lowers the due', async () => {
      panel();
      let el = await settle();
      q(el, 'cust-pay')?.click();
      el = await settle();
      const amount = q(el, 'pay-amount') as HTMLInputElement;
      amount.value = '60';
      amount.dispatchEvent(new Event('input'));
      q(el, 'pay-save')?.click();
      await settle();
      expect(text(el, 'cust-error')).toBe('The payment is more than the due.');
      http.expectNone(`${S}/customers/cust-1/payments`);
      amount.value = '40';
      amount.dispatchEvent(new Event('input'));
      q(el, 'pay-save')?.click();
      const req = http.expectOne(`${S}/customers/cust-1/payments`);
      expect(req.request.body).toMatchObject({ amountPaise: 4000, mode: 'CASH' });
      req.flush({
        entryId: 'e-1',
        customerId: 'cust-1',
        customerName: 'Test Customer One',
        amountPaise: 4000,
        mode: 'CASH',
        receivedAt: '2026-11-21T05:30:00Z',
        receivedBy: { membershipId: 'm-1', name: 'Test Pharmacist One' },
        store: { storeId: 'store-ward', name: 'Test Ward 3B store', type: 'WARD' },
        dueAfterPaise: 1000,
        printHeader: { templateDocType: 'INVOICE', headerLines: [], footerNote: null, paperSize: 'A4', unitGstin: null, drugLicenceNo: null },
      });
      await settle();
      expect(text(el, 'slip-due')).toBe('Due after this payment ₹ 10.00');
      expect(text(el, 'payment-slip')).toContain('Payment received');
    });

    it('hides "Receive payment" when nothing is due, and lists the account with the over-60-days part', async () => {
      panel(0);
      let el = await settle();
      expect(q(el, 'cust-pay')).toBeNull();
      q(el, 'cust-account')?.click();
      http.expectOne((r) => r.url === `${S}/customers/cust-1/account`).flush({
        total: 1,
        page: 0,
        size: 10,
        credit: { customerId: 'cust-1', creditLimitPaise: 500000, duePaise: 0, over60DaysPaise: 2000, creditNoteBalancePaise: 0 },
        items: [
          { entryId: 'e-1', kind: 'CREDIT_BILL', at: '2026-11-01T05:00:00Z', storeName: 'Test Ward 3B store', reference: 'SB/26-27/018000', amountPaise: 5000, dueAfterPaise: 5000, creditNoteBalanceAfterPaise: 0, by: { membershipId: 'm-1', name: 'Test' } },
        ],
      });
      el = await settle();
      expect(text(el, 'cust-account-table')).toContain('Credit bill');
      expect(text(el, 'cust-over60')).toBe('₹ 20.00 over 60 days');
    });

    it('offers "Save anyway" when the phone is already used', async () => {
      panel();
      let el = await settle();
      q(el, 'cust-new')?.click();
      el = await settle();
      (q(el, 'cust-name') as HTMLInputElement).value = 'Test Customer Two';
      q(el, 'cust-name')?.dispatchEvent(new Event('input'));
      q(el, 'cust-save')?.click();
      http.expectOne(`${S}/customers`).flush(
        { type: 'https://sahaj.in/problems/customer-phone-exists', title: 'Exists', status: 409, detail: 'A customer with this phone already exists. Use them?' },
        { status: 409, statusText: 'Conflict' },
      );
      el = await settle();
      expect(text(el, 'cust-error')).toBe('A customer with this phone already exists. Use them?');
      q(el, 'cust-same-phone')?.click();
      const req = http.expectOne(`${S}/customers`);
      expect(req.request.body.confirmSamePhone).toBe(true);
      req.flush(customer({ id: 'cust-3' }));
      await settle();
    });

    it('checks the name and the phone before it calls the server, and shows the credit limit only to a pharmacist or the owner', async () => {
      panel();
      let el = await settle();
      q(el, 'cust-new')?.click();
      el = await settle();
      expect(q(el, 'cust-limit')).toBeNull();
      q(el, 'cust-save')?.click();
      await settle();
      expect(text(el, 'cust-error')).toBe('Enter the name');
      (q(el, 'cust-name') as HTMLInputElement).value = 'Test Customer Two';
      q(el, 'cust-name')?.dispatchEvent(new Event('input'));
      (q(el, 'cust-phone') as HTMLInputElement).value = '123';
      q(el, 'cust-phone')?.dispatchEvent(new Event('input'));
      q(el, 'cust-save')?.click();
      await settle();
      expect(text(el, 'cust-error')).toBe('Enter a 10-digit phone number');
      http.expectNone(`${S}/customers`);
    });
  });

  describe('waitingText', () => {
    it('writes the role and the age of a waiting request', () => {
      const now = new Date('2026-11-21T05:34:00Z');
      expect(waitingText(discountRequest(), now)).toBe('Waiting for approval · Owner · 4 min');
      expect(waitingText(discountRequest({ waitingFor: 'HOD', raisedAt: null }), now)).toBe('Waiting for approval · HOD');
    });
  });
});
