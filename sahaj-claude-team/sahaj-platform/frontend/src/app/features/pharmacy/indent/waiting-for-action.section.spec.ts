import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { PharmacyContext } from '../pharmacy.model';
import { CENTRAL_CONTEXT, WARD_CONTEXT, returnItem, shortage } from './indent.fixtures';
import { ReturnItemDialog } from './return-item.dialog';
import { ShortageSettleDialog } from './shortage-settle.dialog';
import { WaitingForActionSection } from './waiting-for-action.section';

const BASE = '/api/v1/pharmacy';

describe('WaitingForActionSection', () => {
  let fixture: ComponentFixture<WaitingForActionSection>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext, items = [returnItem()], shortages = [shortage()]): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(WaitingForActionSection);
    fixture.detectChanges();
    const storeId = context.workingStoreId;
    const list = http.expectOne((r) => r.url === `${BASE}/stores/${storeId}/waiting-for-action`);
    list.flush({ items, total: items.length, page: 0, size: 20 });
    if (context.stores[0].access.acceptReturn) {
      http.expectOne((r) => r.url === `${BASE}/stores/${storeId}/shortages`).flush({ items: shortages, total: shortages.length, page: 0, size: 20 });
    }
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

  it('lists the items waiting at the Central Store with type, item, quantity, reason and status', async () => {
    const el = await open(CENTRAL_CONTEXT);
    expect(text(el, 'waiting-row-IND-0001 / 1')).toContain('Indent rejection');
    expect(text(el, 'waiting-row-IND-0001 / 1')).toContain('Sample Ceftriaxone 1 g injection');
    expect(text(el, 'waiting-row-IND-0001 / 1')).toContain('TESTB001 · Mar 2028');
    expect(text(el, 'waiting-row-IND-0001 / 1')).toContain('Damaged in transit');
    expect(text(el, 'return-row-status')).toBe('Waiting for Central Store');
    expect(text(el, 'waiting-open-IND-0001 / 1')).toBe('Review');
  });

  it('asks for the ACCEPTING side at the Central Store', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(CENTRAL_CONTEXT);
    fixture = TestBed.createComponent(WaitingForActionSection);
    fixture.detectChanges();
    const req = http.expectOne((r) => r.url === `${BASE}/stores/store-central/waiting-for-action`);
    expect(req.request.params.get('side')).toBe('ACCEPTING');
    req.flush({ items: [], total: 0, page: 0, size: 20 });
    http.expectOne((r) => r.url === `${BASE}/stores/store-central/shortages`).flush({ items: [], total: 0, page: 0, size: 20 });
    await settle();
    expect(text(fixture.nativeElement, 'waiting-empty')).toBe(
      'Nothing was rejected. All items were accepted, so nothing goes back to Central Store.',
    );
    expect(text(fixture.nativeElement, 'shortages-empty')).toBe('No shortage is waiting.');
  });

  it('keeps a shortage out of the returns and shows it as "Short · under investigation" with Settle', async () => {
    const el = await open(CENTRAL_CONTEXT);
    expect(el.querySelector('[data-testid="waiting-table"]')?.textContent).not.toContain('Short ·');
    expect(text(el, 'shortage-row-status')).toBe('Short · under investigation');
    expect(text(el, 'shortage-open-IND-0001 / 1')).toBe('Settle shortage of 3');
  });

  it('offers no Settle on a shortage that is already settled', async () => {
    const el = await open(CENTRAL_CONTEXT, [returnItem()], [shortage({ status: 'FOUND' })]);
    expect(q(el, 'shortage-open-IND-0001 / 1')).toBeNull();
    expect(text(el, 'shortage-row-status')).toBe('Found in Central Store');
  });

  it('opens the item and the shortage and reloads after a change', async () => {
    const el = await open(CENTRAL_CONTEXT);
    const spy = vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of(true) } as never);
    q(el, 'waiting-open-IND-0001 / 1')?.click();
    expect(spy).toHaveBeenCalledWith(
      ReturnItemDialog,
      expect.objectContaining({ data: { storeId: 'store-central', itemId: 'ret-1', side: 'ACCEPTING', canSignScheduleX: false } }),
    );
    http.expectOne((r) => r.url === `${BASE}/stores/store-central/waiting-for-action`).flush({ items: [], total: 0, page: 0, size: 20 });
    http.expectOne((r) => r.url === `${BASE}/stores/store-central/shortages`).flush({ items: [], total: 0, page: 0, size: 20 });
    await settle();
    q(el, 'shortage-open-IND-0001 / 1');
    expect(text(el, 'waiting-note')).toBe('Saved.');
  });

  it('opens the shortage sheet', async () => {
    const el = await open(CENTRAL_CONTEXT);
    const spy = vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of(false) } as never);
    q(el, 'shortage-open-IND-0001 / 1')?.click();
    expect(spy).toHaveBeenCalledWith(ShortageSettleDialog, expect.objectContaining({ data: expect.objectContaining({ storeId: 'store-central' }) }));
  });

  it('shows a store the items it sent back (RETURNING side) and no shortages card', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(WARD_CONTEXT);
    fixture = TestBed.createComponent(WaitingForActionSection);
    fixture.detectChanges();
    const req = http.expectOne((r) => r.url === `${BASE}/stores/store-ward/waiting-for-action`);
    expect(req.request.params.get('side')).toBe('RETURNING');
    req.flush({ items: [returnItem({ status: 'DISPUTED' })], total: 1, page: 0, size: 20 });
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Items you sent back');
    expect(q(el, 'shortages-table')).toBeNull();
    expect(text(el, 'return-row-status')).toBe('Disputed');
  });
});
