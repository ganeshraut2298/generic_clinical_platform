import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { problem } from '../emr/emr.fixtures';
import { NursingSheetComponent } from '../nursing/nursing-sheet.component';
import { TOKEN_B, pageOf, pageRowOf, summaryOf } from '../orders/order-notices.fixtures';
import { OrderNoticesStore } from '../orders/order-notices.store';
import { OrdersToAcknowledgeComponent } from './orders-to-acknowledge.component';

const PAGE = (r: { url: string }) => r.url === '/api/v1/ipd/order-notices';
const SUMMARY = '/api/v1/ipd/order-notices/summary';

describe('OrdersToAcknowledgeComponent (SAH-76-3)', () => {
  let fixture: ComponentFixture<OrdersToAcknowledgeComponent>;
  let http: HttpTestingController;
  let dialog: { open: ReturnType<typeof vi.fn> };

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function open(first: ReturnType<typeof pageOf> | { status: number; body: object } = pageOf()): Promise<void> {
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(true) })) };
    await TestBed.configureTestingModule({
      imports: [OrdersToAcknowledgeComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialog, useValue: dialog },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(OrdersToAcknowledgeComponent);
    fixture.detectChanges();
    http.expectOne(SUMMARY).flush(summaryOf());
    const req = http.expectOne(PAGE);
    if ('status' in first && 'body' in first) {
      req.flush(first.body, { status: first.status, statusText: 'x' });
    } else {
      req.flush(first);
    }
    await settle();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);

  afterEach(() => http.verify());

  it('has the heading "Orders to acknowledge" and a row with the patient, bed, kind, medicine and order', async () => {
    await open();
    expect(squash(q('#sf-ord-h'))).toBe('Orders to acknowledge');
    expect(squash(q('[data-testid=ward-order-patient]'))).toBe('Asha Patil · bed 101-A');
    expect(squash(q('[data-testid=ward-order-drug]'))).toBe('New · Tab. Paracetamol 650 mg');
    expect(squash(q('[data-testid=ward-order-line]'))).toBe(
      '1 tablet · Oral · Once daily · from 6 Nov, 10:00 AM · Until stopped',
    );
    expect(squash(q('[data-testid=ward-order-by]'))).toBe('by Dr. Meera Kulkarni at 11:05 AM');
  });

  it('says "No orders waiting for you." when there are none', async () => {
    await open(pageOf([]));
    expect(squash(q('[data-testid=ward-orders-empty]'))).toBe('No orders waiting for you.');
  });

  it('lists the rows in the order the server sent (STAT first) and shows the STAT line', async () => {
    const stat = { dueBy: '2026-11-06T05:56:00Z', windowMinutes: 15, minutesLeft: 9, minutesOverdue: null };
    await open(
      pageOf([
        pageRowOf({ id: 'n1', priority: 'STAT', stat, drugName: 'Inj. Ondansetron 4 mg' }, 'Ravi Shah', '102-B'),
        pageRowOf({ id: 'n2' }),
      ]),
    );
    expect(Array.from(el().querySelectorAll('[data-notice]')).map((r) => r.getAttribute('data-notice'))).toEqual(['n1', 'n2']);
    expect(squash(q('[data-testid=ward-order-stat]'))).toBe('STAT · give within 15 min · 9 min left');
  });

  it('shows a red strip at the top for each order not acknowledged for too long', async () => {
    await open(pageOf([pageRowOf({ overdue: true, waitingMinutes: 35 }), pageRowOf({ id: 'n2' })]));
    const strips = Array.from(el().querySelectorAll('[data-testid=ward-orders-overdue]'));
    expect(strips).toHaveLength(1);
    const strip = strips[0];
    expect(squash(strip.querySelector('b'))).toBe('Not acknowledged for 35 min');
    expect(squash(strip.querySelector('span'))).toBe('Asha Patil · bed 101-A');
    const list = q('[role=list]')!;
    expect(strip.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(el().textContent).not.toMatch(/alerted|notified/i);
  });

  it('opens the Nursing sheet of that patient on the Orders tab, and reads again when it closes', async () => {
    await open();
    q<HTMLButtonElement>('[data-act=ward-order-open]')!.click();
    await settle();
    expect(dialog.open).toHaveBeenCalledWith(
      NursingSheetComponent,
      expect.objectContaining({ data: { admissionId: 'a1', tab: 'ord' } }),
    );
    http.expectOne(PAGE).flush(pageOf([]));
    http.expectOne(SUMMARY).flush(summaryOf());
    await settle();
    expect(q('[data-testid=ward-orders-empty]')).not.toBeNull();
  });

  it('draws nothing for a role that is refused (403)', async () => {
    await open({ status: 403, body: problem('forbidden', 403, 'You cannot open the order list.') });
    expect(q('[data-testid=orders-to-acknowledge]')).toBeNull();
  });

  it('shows "We could not load the orders. Try again." with a button', async () => {
    await open({ status: 500, body: problem('server-error', 500, 'Try later.') });
    expect(squash(q('[data-testid=ward-orders-error]'))).toContain('Try later.');
    q<HTMLButtonElement>('[data-act=ward-orders-retry]')!.click();
    await settle();
    http.expectOne(PAGE).flush(pageOf());
    await settle();
    expect(q('[data-testid=ward-orders-error]')).toBeNull();
  });

  it('reads again when the counts poll sees another change token, and keeps the rows when that read fails', async () => {
    await open();
    TestBed.inject(OrderNoticesStore).summary.set(summaryOf({ changeToken: TOKEN_B }));
    await settle();
    http.expectOne(PAGE).error(new ProgressEvent('error'));
    await settle();
    expect(el().querySelectorAll('[data-notice]')).toHaveLength(1);
    expect(squash(q('[data-testid=ward-orders-stale]'))).toBe('Could not refresh. Showing data from 11:17 AM.');
  });

  it('shows "Show more orders" while the server sends a cursor and appends the next page', async () => {
    await open(pageOf([pageRowOf()], { nextCursor: 'c1' }));
    q<HTMLButtonElement>('[data-act=ward-orders-more]')!.click();
    await settle();
    const req = http.expectOne((r) => PAGE(r) && r.params.get('cursor') === 'c1');
    req.flush(pageOf([pageRowOf({ id: 'n2' }, 'Ravi Shah', '102-B')]));
    await settle();
    expect(el().querySelectorAll('[data-notice]')).toHaveLength(2);
    expect(q('[data-act=ward-orders-more]')).toBeNull();
  });
});
