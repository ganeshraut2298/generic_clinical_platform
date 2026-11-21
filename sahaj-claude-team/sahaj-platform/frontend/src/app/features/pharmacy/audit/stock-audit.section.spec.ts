import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { PharmacyContext } from '../pharmacy.model';
import { stockAudit, storeContext } from '../stockuse/stockuse.fixtures';
import { CountSheetDialog } from './count-sheet.dialog';
import { StartCountDialog } from './start-count.dialog';
import { StockAuditSection } from './stock-audit.section';
import { VarianceDialog } from './variance.dialog';

const BASE = '/api/v1/pharmacy';
const URL = `${BASE}/stores/store-ward/stock-audits`;

describe('StockAuditSection', () => {
  let fixture: ComponentFixture<StockAuditSection>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext, items = [stockAudit()]): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(StockAuditSection);
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
  const dialog = () => fixture.debugElement.injector.get(MatDialog);

  afterEach(() => http.verify());

  it('shows the tiles of a blind counter with a dash for the variance and "Counting in progress"', async () => {
    const el = await open(storeContext());
    expect(text(el, 'audit-blind-note')).toContain('the counter does not see the system quantity');
    expect(text(el, 'audit-status-AUD-0093')).toBe('Counting in progress');
    expect(text(el, 'audit-tiles-AUD-0093')).toContain('Items in this count 40');
    expect(text(el, 'audit-tiles-AUD-0093')).toContain('Counted 32');
    expect(text(el, 'tile-variance')).toBe('With variance —');
    expect(text(el, 'tile-value')).toBe('Variance value —');
    expect(q(el, 'open-count-AUD-0093')).toBeTruthy();
    expect(q(el, 'open-variance-AUD-0093')).toBeNull();
  });

  it('gives the variance tiles and the review to a reviewer after the count is submitted', async () => {
    const el = await open(storeContext({ reviewCount: true }), [
      stockAudit({ status: 'WAITING_REVIEW', withVariance: 3, varianceValuePaise: -41200 }),
    ]);
    expect(text(el, 'tile-variance')).toBe('With variance 3');
    expect(text(el, 'tile-value')).toBe('Variance value − ₹ 412.00');
    expect(q(el, 'open-count-AUD-0093')).toBeNull();
    expect(q(el, 'open-variance-AUD-0093')).toBeTruthy();
  });

  it('shows "Start stock count" only with the start capability', async () => {
    const without = await open(storeContext({ startCount: false }));
    expect(q(without, 'start-count')).toBeNull();
  });

  it('starts a count, shows the note and reloads', async () => {
    const el = await open(storeContext());
    const spy = vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of(stockAudit({ number: 'AUD-0094' })) } as never);
    q(el, 'start-count')?.click();
    expect(spy).toHaveBeenCalledWith(StartCountDialog, expect.objectContaining({ data: { storeId: 'store-ward', storeName: 'Test Ward 3B store' } }));
    http.expectOne((r) => r.url === URL).flush({ items: [], total: 0, page: 0, size: 20 });
    await settle();
    expect(text(el, 'audit-note')).toBe('Audit AUD-0094 started. Counting in progress.');
    expect(q(el, 'audit-empty')).toBeTruthy();
  });

  it('opens the count sheet for the counter', async () => {
    const el = await open(storeContext());
    const spy = vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of(null) } as never);
    q(el, 'open-count-AUD-0093')?.click();
    expect(spy).toHaveBeenCalledWith(CountSheetDialog, expect.objectContaining({ data: { storeId: 'store-ward', auditId: 'aud-1' } }));
    http.expectOne((r) => r.url === URL).flush({ items: [], total: 0, page: 0, size: 20 });
  });

  it('opens the variance review and hands "Post as consumption instead" to the page', async () => {
    const el = await open(storeContext({ reviewCount: true }), [stockAudit({ status: 'WAITING_REVIEW', withVariance: 1, varianceValuePaise: -100 })]);
    const spy = vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of({ consumptionLineId: 'al-9' }) } as never);
    const lines: string[] = [];
    fixture.componentInstance.consumptionRequested.subscribe((id) => lines.push(id));
    q(el, 'open-variance-AUD-0093')?.click();
    expect(spy).toHaveBeenCalledWith(VarianceDialog, expect.anything());
    expect(lines).toEqual(['al-9']);
  });

  it('shows an error with a way to try again', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(storeContext());
    fixture = TestBed.createComponent(StockAuditSection);
    fixture.detectChanges();
    http.expectOne((r) => r.url === URL).flush({}, { status: 500, statusText: 'Server error' });
    await settle();
    expect(q(fixture.nativeElement, 'audit-error')).toBeTruthy();
  });
});
