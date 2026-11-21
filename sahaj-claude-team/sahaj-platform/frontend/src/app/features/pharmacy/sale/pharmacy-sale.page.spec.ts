import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { PharmacyContext } from '../pharmacy.model';
import { PharmacySalePage } from './pharmacy-sale.page';
import { commandCentre, opdQueue } from './compliance.fixtures';
import { bill, billPage, saleContext } from './sale.fixtures';
import { blockerText, expiredBlockedText, expiresText, parseRupees, paymentProblem, shortText } from './sale.util';
import { quoteLine } from './sale.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-ward`;

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

describe('PharmacySalePage', () => {
  let fixture: ComponentFixture<PharmacySalePage>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext = saleContext()): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(PharmacySalePage);
    fixture.detectChanges();
    http.expectOne((r) => r.url === `${S}/bills`).flush(billPage());
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('opens on the Command Center with today\'s bills and "+ New bill", and says what is not built', async () => {
    const el = await open();
    expect(text(el, 'bill-SB/26-27/018422')).toContain('Walk-in');
    expect(text(el, 'bill-SB/26-27/018422')).toContain('₹ 240.00');
    expect(text(el, 'bill-SB/26-27/018422')).toContain('Allergy not checked');
    expect(q(el, 'cc-tiles')).toBeNull();
    expect(q(el, 'new-bill')).toBeTruthy();
    expect(q(el, 'sale-section-pos')).toBeTruthy();
    expect(q(el, 'sale-section-dispensing')).toBeNull();
  });

  it('opens POS billing from "+ New bill"', async () => {
    const el = await open();
    q(el, 'new-bill')?.click();
    fixture.detectChanges();
    http.expectOne(`${S}/held-bills`).flush({ items: [] });
    http.expectOne(`${S}/second-signers`).flush({ items: [] });
    await settle();
    expect(q(el, 'pos')).toBeTruthy();
  });

  it('hides POS billing from a person who cannot sell, and shows "Not connected" for the OPD and IPD queues', async () => {
    const ctx = saleContext({ sell: false }, ['INVENTORY', 'PHARMACY', 'OPD_QUEUE', 'IPD_QUEUE']);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(ctx);
    fixture = TestBed.createComponent(PharmacySalePage);
    fixture.detectChanges();
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    expect(q(el, 'sale-section-pos')).toBeNull();
    q(el, 'sale-section-dispensing')?.click();
    fixture.detectChanges();
    http.expectOne(`${S}/opd-queue`).flush(opdQueue());
    await settle();
    expect(text(el, 'dispensing-not-connected')).toContain('Not connected');
    expect(text(el, 'ipd-not-connected')).toContain('Not connected');
  });

  it('shows the Command Center tiles from the server, with the others still showing when one fails', async () => {
    const ctx = saleContext({ sell: true }, ['INVENTORY', 'PHARMACY', 'POS', 'COMMAND_CENTRE']);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(ctx);
    fixture = TestBed.createComponent(PharmacySalePage);
    fixture.detectChanges();
    http
      .expectOne((r) => r.url === `${BASE}/command-centre`)
      .flush(commandCentre({ reorder: { state: 'ERROR' }, grossMargin: null, rxWaiting: null }));
    http.expectOne((r) => r.url === `${S}/bills`).flush(billPage());
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    expect(text(el, 'tile-sales')).toContain('₹ 48,260.00');
    expect(text(el, 'tile-sales')).toContain('142 bills');
    expect(text(el, 'tile-reorder')).toContain('Could not load. Try again.');
    expect(q(el, 'tile-margin')).toBeNull();
    expect(text(el, 'tile-udhar')).toContain('₹ 18,200.00 over 60 days');
    expect(text(el, 'cc-asof')).toMatch(/^As of .* IST$/);
    expect(text(el, 'band-EXPIRED')).toContain('3 batches');
    expect(text(el, 'comp-licence')).toContain('Due in 41 days');
    expect(q(el, 'cc-live-rx-empty')).toBeTruthy();
  });

  it("opens a saved bill from Today's bills and cancels it with a reason and the shelf answer", async () => {
    const el = await open();
    q(el, 'open-SB/26-27/018422')?.click();
    fixture.detectChanges();
    http.expectOne(`${S}/bills/bill-1`).flush(bill());
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush({
      lists: [{ listKey: 'cancel-bill', reasons: [{ reasonKey: 'cancel-bill.wrong-item', label: 'Wrong item', needsNote: false, fixed: false }] }],
    });
    http.expectOne(`${S}/second-signers`).flush({ items: [] });
    await settle();
    expect(text(el, 'bill-no-edit')).toBe('A saved bill cannot be edited. Cancel it and make a new bill.');
    q(el, 'cancel-open')?.click();
    await settle();
    q(el, 'reason-cancel-bill.wrong-item')?.click();
    q(el, 'reason-confirm')?.click();
    await settle();
    q(el, 'shelf-no')?.click();
    await settle();
    q(el, 'cancel-save')?.click();
    const req = http.expectOne(`${S}/bills/bill-1/cancel`);
    expect(req.request.body).toMatchObject({ version: 0, reasonKey: 'cancel-bill.wrong-item', backOnShelf: false });
    req.flush(bill({ status: 'CANCELLED', refundDuePaise: 24000, canCancel: false, version: 1 }));
    http.expectOne((r) => r.url === `${S}/bills`).flush(billPage());
    await settle();
    expect(text(el, 'bill-status')).toBe('Cancelled');
    expect(text(el, 'bill-refund-due')).toContain('Refund due ₹ 240.00');
  });

  it('records the refund of a cancelled bill in one of the modes it was paid in', async () => {
    const el = await open();
    q(el, 'open-SB/26-27/018422')?.click();
    fixture.detectChanges();
    http.expectOne(`${S}/bills/bill-1`).flush(bill({ status: 'CANCELLED', refundDuePaise: 24000, canCancel: false, version: 2 }));
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush({ lists: [] });
    http.expectOne(`${S}/second-signers`).flush({ items: [] });
    await settle();
    q(el, 'refund-save')?.click();
    const req = http.expectOne(`${S}/bills/bill-1/refund`);
    expect(req.request.body).toMatchObject({ version: 2, refunds: [{ mode: 'CASH', amountPaise: 24000 }] });
    req.flush(bill({ status: 'CANCELLED', refundDuePaise: 0, canCancel: false, version: 3 }));
    http.expectOne((r) => r.url === `${S}/bills`).flush(billPage());
    await settle();
    expect(q(el, 'bill-refund-due')).toBeNull();
  });
});

describe('sale.util', () => {
  it('reads rupees typed by a person as paise, or refuses', () => {
    expect(parseRupees('532')).toBe(53200);
    expect(parseRupees('532.5')).toBe(53250);
    expect(parseRupees('532.555')).toBeNull();
    expect(parseRupees('abc')).toBeNull();
  });

  it('says when the payments do not add up to the net payable', () => {
    expect(paymentProblem(53200, [{ mode: 'CASH', amountPaise: 53200 }])).toBeNull();
    expect(paymentProblem(53200, [{ mode: 'CASH', amountPaise: 50000 }])).toBe('Payments must add up to ₹ 532.00.');
    expect(paymentProblem(53200, [])).toBe('Payments must add up to ₹ 532.00.');
  });

  it('writes the line notes of the contract', () => {
    expect(shortText(quoteLine({ shortBy: 2, availableQty: 4 }))).toBe('Only 4 in stock.');
    expect(shortText(quoteLine({ shortBy: 2, availableQty: null }))).toBe('Not enough stock.');
    expect(shortText(quoteLine())).toBeNull();
    expect(expiresText(quoteLine({ expiresInDays: 12 }))).toBe('Expires in 12 d');
    expect(expiredBlockedText(quoteLine({ expiredBatchBlocked: 'GM2311' }))).toBe('GM2311 expired · blocked');
    expect(blockerText('PHARMACIST')).toBe('A pharmacist must complete this bill.');
  });
});
