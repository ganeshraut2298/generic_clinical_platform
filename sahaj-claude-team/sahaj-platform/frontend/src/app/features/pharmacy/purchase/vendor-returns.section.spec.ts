import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { DebitNoteDialog } from './debit-note.dialog';
import { VendorReturnDialog } from './vendor-return.dialog';
import { VendorReturnsSection } from './vendor-returns.section';
import { BUYER_CONTEXT, debitNote } from './purchase.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-central`;

const PAGE = {
  items: [
    { id: 'dn-1', number: 'DN-26-27/0001', vendorName: 'Test Distributors Pvt. Ltd. (fake)', creditTotalPaise: 70500, status: 'CREATED', createdAt: '2026-11-19T04:30:00Z' },
    { id: 'dn-2', number: null, vendorName: 'Test Distributors Pvt. Ltd. (fake)', creditTotalPaise: 12000, status: 'DRAFT', createdAt: '2026-11-19T05:30:00Z' },
  ],
  total: 2,
  page: 0,
  size: 20,
};

describe('VendorReturnsSection', () => {
  let fixture: ComponentFixture<VendorReturnsSection>;
  let http: HttpTestingController;

  async function open(workingStoreId = 'store-central'): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush({ ...BUYER_CONTEXT, workingStoreId });
    fixture = TestBed.createComponent(VendorReturnsSection);
    fixture.detectChanges();
    if (workingStoreId === 'store-central') {
      http.expectOne((r) => r.url === `${S}/debit-notes`).flush(PAGE);
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

  afterEach(() => http.verify());

  it('shows the Central Store return list with Draft prepared by a receipt, and "+ New return"', async () => {
    const el = await open();
    expect(text(el, 'new-return')).toBe('+ New return');
    expect(el.textContent).toContain('DN-26-27/0001');
    expect(el.textContent).toContain('Draft');
    expect(el.textContent).toContain('₹ 705.00');
  });

  it('shows nothing to do at a store that is not the Central Store', async () => {
    const el = await open('store-ward');
    expect(q(el, 'vr-not-central')).toBeTruthy();
    expect(q(el, 'new-return')).toBeNull();
  });

  it('filters by status through the server', async () => {
    const el = await open();
    q(el, 'dn-filter-DRAFT')?.click();
    const req = http.expectOne((r) => r.url === `${S}/debit-notes`);
    expect(req.request.params.get('status')).toBe('DRAFT');
    req.flush({ items: [], total: 0, page: 0, size: 20 });
    await settle();
    expect(text(el, 'dn-empty')).toBe('No debit notes yet. Choose + New return.');
  });

  it('opens the new return sheet and then the tracking card of the debit note it made', async () => {
    const el = await open();
    const spy = vi.spyOn(fixture.debugElement.injector.get(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(debitNote()) } as never);
    q(el, 'new-return')?.click();
    expect(spy).toHaveBeenCalledWith(VendorReturnDialog, expect.objectContaining({ data: { storeId: 'store-central' } }));
    expect(spy).toHaveBeenLastCalledWith(DebitNoteDialog, expect.objectContaining({ data: { storeId: 'store-central', dnId: 'dn-1' } }));
    http.expectOne((r) => r.url === `${S}/debit-notes`).flush(PAGE);
    await settle();
    expect(text(el, 'vr-note')).toBe('Debit note DN-26-27/0001 created.');
  });

  it('says Could not load with a retry when the list fails', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(BUYER_CONTEXT);
    fixture = TestBed.createComponent(VendorReturnsSection);
    fixture.detectChanges();
    http.expectOne((r) => r.url === `${S}/debit-notes`).flush(null, { status: 500, statusText: 'Server Error' });
    await settle();
    expect(text(fixture.nativeElement, 'dn-list-error')).toContain('Could not load. Try again.');
  });
});
