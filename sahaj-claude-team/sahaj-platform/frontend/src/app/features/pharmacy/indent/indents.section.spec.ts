import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { PharmacyContext } from '../pharmacy.model';
import { IndentDispatchDialog } from './indent-dispatch.dialog';
import { IndentDetailDialog } from './indent-detail.dialog';
import { IndentFormDialog } from './indent-form.dialog';
import { IndentsSection } from './indents.section';
import {
  CENTRAL_CONTEXT,
  HOD_CONTEXT,
  WARD_CONTEXT,
  WARD_PHARMACIST_CONTEXT,
  indent,
  indentRow,
} from './indent.fixtures';
import { ReceiptDialog } from './receipt.dialog';

const BASE = '/api/v1/pharmacy';

describe('IndentsSection', () => {
  let fixture: ComponentFixture<IndentsSection>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext, rows = [indentRow()], incoming: unknown[] = []): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(IndentsSection);
    fixture.detectChanges();
    const storeId = context.workingStoreId;
    const list = http.expectOne((r) => r.url === `${BASE}/stores/${storeId}/indents`);
    list.flush({ items: rows, total: rows.length, page: 0, size: 20 });
    if (context.stores[0].access.receive) {
      http.expectOne((r) => r.url === `${BASE}/stores/${storeId}/dispatches`).flush({ items: incoming, total: incoming.length, page: 0, size: 20 });
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

  it('lists the raised indents of the working store with the status chips and "+ New indent"', async () => {
    const el = await open(WARD_CONTEXT, [
      indentRow(),
      indentRow({ id: 'ind-2', number: null, status: 'DRAFT' }),
      indentRow({ id: 'ind-3', number: 'IND-0003', status: 'PARTLY_DISPATCHED', priority: 'URGENT' }),
    ]);
    expect(q(el, 'new-indent')).toBeTruthy();
    expect(q(el, 'indents-table')?.textContent).toContain('To store');
    expect(text(el, 'indent-row-IND-0001')).toContain('Waiting for HOD approval');
    expect(text(el, 'indent-row-IND-0003')).toContain('Urgent');
    expect(text(el, 'indent-open-IND-0001')).toBe('Open');
    expect(text(el, 'indent-open-ind-2')).toBe('Edit draft');
  });

  it('asks for the RAISED view at a ward and the status filter reloads from the first page', async () => {
    const el = await open(WARD_CONTEXT);
    (q(el, 'indent-status-filter') as HTMLSelectElement).value = 'DRAFT';
    q(el, 'indent-status-filter')?.dispatchEvent(new Event('change'));
    const req = http.expectOne((r) => r.url === `${BASE}/stores/store-ward/indents`);
    expect(req.request.params.get('view')).toBe('RAISED');
    expect(req.request.params.get('status')).toBe('DRAFT');
    expect(req.request.params.get('page')).toBe('0');
    req.flush({ items: [], total: 0, page: 0, size: 20 });
    await settle();
    expect(text(el, 'indents-empty')).toContain('No indents yet.');
  });

  it('shows the Central Store the indents to dispatch, with no "+ New indent" and no status filter', async () => {
    const el = await open(CENTRAL_CONTEXT, [indentRow({ status: 'APPROVED', priority: 'URGENT' })]);
    expect(q(el, 'new-indent')).toBeNull();
    expect(q(el, 'indent-status-filter')).toBeNull();
    expect(q(el, 'indents-table')?.textContent).toContain('From store');
    expect(q(el, 'indent-process-IND-0001')).toBeTruthy();
    expect(text(el, 'indent-status')).toBe('Approved · with Central Store');
  });

  it('shows no access sentence to a person with none of the indent capabilities', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush({
      ...WARD_CONTEXT,
      stores: [{ ...WARD_CONTEXT.stores[0], access: { ...WARD_CONTEXT.stores[0].access, raiseIndent: false, receive: false } }],
    });
    fixture = TestBed.createComponent(IndentsSection);
    fixture.detectChanges();
    expect(q(fixture.nativeElement, 'indents-no-access')).toBeTruthy();
  });

  it('opens "+ New indent", then shows the note after a draft is submitted and reloads', async () => {
    const el = await open(WARD_CONTEXT);
    const spy = vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of(indent({ status: 'WAITING_APPROVAL' })) } as never);
    q(el, 'new-indent')?.click();
    expect(spy).toHaveBeenCalledWith(
      IndentFormDialog,
      expect.objectContaining({
        data: expect.objectContaining({
          storeId: 'store-ward',
          storeName: 'Test Ward 3B store',
          actingAs: 'Acting as Test Ward Nurse · mapped to Test Ward 3B store · can raise indents for Test Ward 3B store',
        }),
      }),
    );
    http.expectOne((r) => r.url === `${BASE}/stores/store-ward/indents`).flush({ items: [], total: 0, page: 0, size: 20 });
    await settle();
    expect(text(el, 'indent-note')).toBe('IND-0001 is waiting for HOD approval.');
  });

  it('opens a submitted indent on its detail card and reloads when something changed', async () => {
    const el = await open(HOD_CONTEXT);
    const spy = vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of(true) } as never);
    q(el, 'indent-open-IND-0001')?.click();
    expect(spy).toHaveBeenCalledWith(
      IndentDetailDialog,
      expect.objectContaining({ data: { storeId: 'store-ward', indentId: 'ind-1' } }),
    );
    http.expectOne((r) => r.url === `${BASE}/stores/store-ward/indents`).flush({ items: [], total: 0, page: 0, size: 20 });
    await settle();
    expect(text(el, 'indent-note')).toBe('Your change was saved.');
  });

  it('opens a draft on the form with its saved lines', async () => {
    const el = await open(WARD_CONTEXT, [indentRow({ id: 'ind-2', number: null, status: 'DRAFT' })]);
    const spy = vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of(null) } as never);
    q(el, 'indent-open-ind-2')?.click();
    http.expectOne(`${BASE}/stores/store-ward/indents/ind-2`).flush(indent({ id: 'ind-2', status: 'DRAFT', number: null }));
    expect(spy).toHaveBeenCalledWith(
      IndentFormDialog,
      expect.objectContaining({ data: expect.objectContaining({ toStoreName: 'Test Central Store' }) }),
    );
  });

  it('opens "Process & dispatch" at the Central Store and tells a pharmacist from other people', async () => {
    const el = await open(CENTRAL_CONTEXT, [indentRow({ status: 'APPROVED' })]);
    const spy = vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of(false) } as never);
    q(el, 'indent-process-IND-0001')?.click();
    expect(spy).toHaveBeenCalledWith(
      IndentDispatchDialog,
      expect.objectContaining({ data: { storeId: 'store-central', indentId: 'ind-1', canSignScheduleX: false } }),
    );
  });

  it('lists the dispatches in transit for a person who may receive and opens the receipt', async () => {
    const el = await open(WARD_PHARMACIST_CONTEXT, [], [
      { id: 'disp-1', reference: 'IND-0001 / 1', source: 'INDENT', fromStore: { storeId: 'store-central', name: 'Test Central Store', type: 'CENTRAL' }, itemCount: 2, status: 'PARTLY_RECEIVED', dispatchedAt: '2026-11-19T06:00:00Z' },
    ]);
    expect(text(el, 'dispatch-status')).toBe('Partly received');
    const spy = vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of(true) } as never);
    q(el, 'dispatch-receive-IND-0001 / 1')?.click();
    expect(spy).toHaveBeenCalledWith(
      ReceiptDialog,
      expect.objectContaining({ data: { storeId: 'store-ward', dispatchId: 'disp-1', isPharmacist: true } }),
    );
    http.expectOne((r) => r.url === `${BASE}/stores/store-ward/indents`).flush({ items: [], total: 0, page: 0, size: 20 });
    http.expectOne((r) => r.url === `${BASE}/stores/store-ward/dispatches`).flush({ items: [], total: 0, page: 0, size: 20 });
    await settle();
    expect(text(el, 'indent-note')).toContain('Receipt confirmed');
  });

  it('shows "Could not load. Try again." when the list fails', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(CENTRAL_CONTEXT);
    fixture = TestBed.createComponent(IndentsSection);
    fixture.detectChanges();
    http.expectOne((r) => r.url === `${BASE}/stores/store-central/indents`).flush(null, { status: 500, statusText: 'Server Error' });
    await settle();
    expect(q(fixture.nativeElement, 'indents-error')).toBeTruthy();
  });
});
