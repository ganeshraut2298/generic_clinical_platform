import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BillingApi } from './billing.api';

const BASE = '/api/v1/billing';

describe('BillingApi', () => {
  let api: BillingApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(BillingApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads the counter lists', () => {
    api.counter().subscribe();
    const req = http.expectOne(`${BASE}/counter`);
    expect(req.request.method).toBe('GET');
    req.flush({ awaiting: [], dues: [], paidToday: [] });
  });

  it('loads a single bill', () => {
    api.getBill('v1').subscribe();
    const req = http.expectOne(`${BASE}/bills/v1`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('adds an item', () => {
    api.addItem('v1', 's1', 2).subscribe();
    const req = http.expectOne(`${BASE}/bills/v1/items`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ serviceId: 's1', qty: 2 });
    req.flush({});
  });

  it('removes an item', () => {
    api.removeItem('v1', 'i1').subscribe();
    const req = http.expectOne(`${BASE}/bills/v1/items/i1`);
    expect(req.request.method).toBe('DELETE');
    req.flush({});
  });

  it('sets the discount', () => {
    api.setDiscount('v1', 10, 'Senior citizen').subscribe();
    const req = http.expectOne(`${BASE}/bills/v1/discount`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ percent: 10, reason: 'Senior citizen' });
    req.flush({});
  });

  it('collects a payment', () => {
    const payment = { mode: 'CASH' as const, amountPaise: 50000 };
    api.collectPayment('v1', payment).subscribe();
    const req = http.expectOne(`${BASE}/bills/v1/payments`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(payment);
    req.flush({});
  });

  it('records a refund', () => {
    const refund = { amountPaise: 20000, mode: 'CASH' as const, reason: 'Billing mistake' };
    api.refund('v1', refund).subscribe();
    const req = http.expectOne(`${BASE}/bills/v1/refund`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(refund);
    req.flush({});
  });

  it('closes the day', () => {
    api.closeDay({ countedCashPaise: 150000, note: 'Short by 50' }).subscribe();
    const req = http.expectOne(`${BASE}/day-closing`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ countedCashPaise: 150000, note: 'Short by 50' });
    req.flush({});
  });
});
