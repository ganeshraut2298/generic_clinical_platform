import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { INVENTORY_OWNER_CONTEXT, adjustment } from './inventory.fixtures';
import { StockAdjustmentsSection } from './stock-adjustments.section';
import { StockAdjustmentDialog } from './stock-adjustment.dialog';

const BASE = '/api/v1/pharmacy';
const URL = `${BASE}/stores/store-central/stock-adjustments`;

describe('StockAdjustmentsSection', () => {
  let fixture: ComponentFixture<StockAdjustmentsSection>;
  let http: HttpTestingController;

  async function open(items = [adjustment()]): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(INVENTORY_OWNER_CONTEXT);
    fixture = TestBed.createComponent(StockAdjustmentsSection);
    fixture.detectChanges();
    http.expectOne((r) => r.url === URL).flush({ items, total: items.length, page: 0, size: 20 });
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

  afterEach(() => http.verify());

  it('lists adjustments with book to physical, the difference with its value, the reason and the status', async () => {
    const el = await open();
    const row = text(el, 'adj-ADJ-0001') ?? '';
    expect(row).toContain('Sample Ceftriaxone 1 g injection · TESTB001');
    expect(row).toContain('40 → 38');
    expect(row).toContain('−2 · ₹ 141.00');
    expect(row).toContain('Count mismatch');
    expect(row).toContain('Waiting for approval');
  });

  it('shows the History card of an adjustment with user, store, time, quantities and reason', async () => {
    const el = await open();
    expect(q(el, 'history-rows-ADJ-0001')).toBeNull();
    q(el, 'history-ADJ-0001')?.click();
    await settle();
    expect(text(el, 'history-rows-ADJ-0001')).toContain('Raised · Test Pharmacist One');
    expect(text(el, 'history-rows-ADJ-0001')).toContain('Test Central Store');
    expect(text(el, 'history-rows-ADJ-0001')).toContain('40 → 38');
    expect(text(el, 'history-rows-ADJ-0001')).toContain('Count mismatch');
  });

  it('shows the reason a rejected adjustment was rejected for', async () => {
    const el = await open([
      adjustment({
        status: 'REJECTED',
        approval: {
          requestId: 'r',
          requestVersion: 1,
          levelNo: null,
          status: 'REJECTED',
          currentLevel: null,
          ruleText: 'x',
          waitingFor: null,
          escalated: false,
          needsAnotherApprover: false,
          canDecide: false,
          rejectReason: { reasonKey: 'approval-reject.quantity-too-high', label: 'Quantity too high' },
        },
      }),
    ]);
    expect(text(el, 'reject-reason')).toBe('Quantity too high');
  });

  it('asks the server for a status and says when there are none', async () => {
    const el = await open();
    q(el, 'adj-filter-POSTED')?.click();
    const req = http.expectOne((r) => r.url === URL);
    expect(req.request.params.get('status')).toBe('POSTED');
    req.flush({ items: [], total: 0, page: 0, size: 20 });
    await settle();
    expect(text(el, 'adjust-empty')).toBe('No stock adjustments yet.');
  });

  it('opens the form and shows the result line, reading the list again', async () => {
    const el = await open();
    const spy = vi
      .spyOn(fixture.debugElement.injector.get(MatDialog), 'open')
      .mockReturnValue({ afterClosed: () => of(adjustment({ number: 'ADJ-0002', status: 'POSTED' })) } as never);
    q(el, 'new-adjustment')?.click();
    expect(spy).toHaveBeenCalledWith(StockAdjustmentDialog, expect.objectContaining({ ariaLabelledBy: 'adjust-title' }));
    http.expectOne((r) => r.url === URL).flush({ items: [], total: 0, page: 0, size: 20 });
    await settle();
    expect(text(el, 'adjust-note')).toBe('ADJ-0002 posted.');
  });

  it('shows "Could not load. Try again." when the list fails', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(INVENTORY_OWNER_CONTEXT);
    fixture = TestBed.createComponent(StockAdjustmentsSection);
    fixture.detectChanges();
    http.expectOne((r) => r.url === URL).flush(null, { status: 500, statusText: 'Server Error' });
    await settle();
    expect(text(fixture.nativeElement, 'adjust-list-error')).toContain('Could not load. Try again.');
  });
});
