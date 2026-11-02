import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { Bill, BillingCounter } from '../billing.model';
import { BillingCounterPage } from './billing-counter.page';

const BILLING = '/api/v1/billing';
const OPD = '/api/v1/opd';

const SUMMARY = { visitId: 'v1', tokenNo: 7, patientName: 'Asha Patil', doctorName: 'Dr. Rao', totalPaise: 50000, balancePaise: 50000 };

const COUNTER: BillingCounter = { awaiting: [SUMMARY], dues: [], paidToday: [] };

function bill(overrides: Partial<Bill> = {}): Bill {
  return {
    visitId: 'v1',
    tokenNo: 7,
    patientName: 'Asha Patil',
    age: 34,
    sex: 'F',
    doctorName: 'Dr. Rao',
    visitType: 'NEW',
    items: [{ id: 'i1', name: 'Consultation', amountPaise: 50000, qty: 1, removable: false }],
    subtotalPaise: 50000,
    discountPercent: 0,
    discountReason: null,
    discountPaise: 0,
    totalPaise: 50000,
    paidPaise: 0,
    balancePaise: 50000,
    payments: [],
    canCollect: true,
    discountLimitPercent: 10,
    ...overrides,
  };
}

describe('BillingCounterPage', () => {
  let fixture: ComponentFixture<BillingCounterPage>;
  let http: HttpTestingController;

  async function open(role: Role, counter: BillingCounter = COUNTER, loadedBill: Bill = bill()) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(BillingCounterPage);
    fixture.detectChanges();

    http.expectOne(`${BILLING}/counter`).flush(counter);
    http.expectOne(`${OPD}/services`).flush([{ id: 's1', name: 'Dressing', category: 'Procedure', priceRupees: 150, active: true }]);
    fixture.detectChanges();

    if (counter.awaiting[0] || counter.dues[0] || counter.paidToday[0]) {
      http.expectOne(`${BILLING}/bills/v1`).flush(loadedBill);
      http.expectOne(`${BILLING}/counter`).flush(counter);
    }
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  it('shows the totals from the bill', async () => {
    const el = await open('FRONT_DESK');
    expect(el.textContent).toContain('₹500');
    expect(el.querySelector('.grand')!.textContent).toContain('₹500');
  });

  it('keeps the discount reason disabled until a discount above zero is entered', async () => {
    const el = await open('FRONT_DESK');
    const reasonSelect = el.querySelector<HTMLSelectElement>('select[aria-label="Discount reason"]')!;
    expect(reasonSelect.disabled).toBe(true);

    const discInput = el.querySelector<HTMLInputElement>('#disc')!;
    discInput.value = '5';
    discInput.dispatchEvent(new Event('change'));
    const req = http.expectOne(`${BILLING}/bills/v1/discount`);
    expect(req.request.body).toEqual({ percent: 5, reason: null });
    req.flush(bill({ discountPercent: 5, discountPaise: 2500, totalPaise: 47500, balancePaise: 47500 }));
    http.expectOne(`${BILLING}/counter`).flush(COUNTER);
    fixture.detectChanges();

    expect(el.querySelector<HTMLSelectElement>('select[aria-label="Discount reason"]')!.disabled).toBe(false);
    expect(el.textContent).toContain('Your limit: 10%');
  });

  it('collects a cash payment defaulting to the balance and settles the bill', async () => {
    const el = await open('FRONT_DESK');
    const collectButton = Array.from(el.querySelectorAll('button')).find((b) => b.textContent!.includes('Collect'))!;
    collectButton.click();

    const req = http.expectOne(`${BILLING}/bills/v1/payments`);
    expect(req.request.body).toEqual({ mode: 'CASH', amountPaise: 50000, reference: null });
    req.flush(
      bill({
        paidPaise: 50000,
        balancePaise: 0,
        payments: [{ id: 'p1', mode: 'CASH', amountPaise: 50000, recordedByName: 'Pooja', recordedAt: new Date().toISOString() }],
      }),
    );
    http.expectOne(`${BILLING}/counter`).flush({ awaiting: [], dues: [], paidToday: [SUMMARY] });
    fixture.detectChanges();

    expect(el.textContent).toContain('Settled');
  });

  it('lets the owner refund a settled bill', async () => {
    const settled = bill({
      paidPaise: 50000,
      balancePaise: 0,
      payments: [{ id: 'p1', mode: 'CASH', amountPaise: 50000, recordedByName: 'Dr. Meera', recordedAt: new Date().toISOString() }],
    });
    const el = await open('OWNER', { awaiting: [], dues: [], paidToday: [SUMMARY] }, settled);

    const refundButton = Array.from(el.querySelectorAll('button')).find((b) => b.textContent!.includes('Refund'))!;
    refundButton.click();
    fixture.detectChanges();

    const reasonSelect = el.querySelector<HTMLSelectElement>('#rfr')!;
    reasonSelect.value = 'Billing mistake';
    reasonSelect.dispatchEvent(new Event('change'));

    const submit = el.querySelector('.refundbox')!.querySelector<HTMLButtonElement>('button[mat-flat-button]')!;
    submit.click();

    const req = http.expectOne(`${BILLING}/bills/v1/refund`);
    expect(req.request.body).toEqual({ amountPaise: 50000, mode: 'CASH', reason: 'Billing mistake' });
    req.flush(bill({ paidPaise: 0, balancePaise: 50000, payments: [] }));
    http.expectOne(`${BILLING}/counter`).flush(COUNTER);
    fixture.detectChanges();

    expect(el.textContent).not.toContain('Refunds need the owner');
  });

  it('hides the refund button and shows the view-only notice for a role that cannot collect', async () => {
    const readOnly = bill({ canCollect: false });
    const el = await open('NURSE', COUNTER, readOnly);
    expect(el.textContent).toContain('Only the front desk or the owner can take payments');
    expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent!.includes('Collect'))).toBe(false);
  });
});
