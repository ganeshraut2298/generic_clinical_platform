import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { INVENTORY_OWNER_CONTEXT } from '../inventory/inventory.fixtures';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { PHARMACIST_CONTEXT, openingImport, openingLine } from '../pharmacy.fixtures';
import { OpeningStockImport, PharmacyContext } from '../pharmacy.model';
import { ImportReviewComponent } from './import-review.component';
import { PostToStockDialog } from './post-to-stock.dialog';

const BASE = '/api/v1/pharmacy';
const IMPORT_URL = `${BASE}/stores/store-central/opening-stock-imports/imp-1`;

describe('ImportReviewComponent, Post to stock (SAH-79-1)', () => {
  let fixture: ComponentFixture<ImportReviewComponent>;
  let http: HttpTestingController;
  let decided: OpeningStockImport[];

  async function open(item: OpeningStockImport, context: PharmacyContext = INVENTORY_OWNER_CONTEXT): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(ImportReviewComponent);
    fixture.componentRef.setInput('storeId', 'store-central');
    fixture.componentRef.setInput('importId', 'imp-1');
    decided = [];
    fixture.componentInstance.decided.subscribe((d) => decided.push(d));
    fixture.detectChanges();
    http.expectOne(IMPORT_URL).flush(item);
    http.expectOne((r) => r.url === `${IMPORT_URL}/lines`).flush({ items: [openingLine()], total: 1, page: 0, size: 50 });
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

  it('shows "Post to stock" on an approved import that is not posted, for a person who may post at its store', async () => {
    const el = await open(openingImport({ status: 'APPROVED' }));
    expect(text(el, 'review-status')).toBe('Approved');
    expect(q(el, 'post-to-stock')?.textContent?.trim()).toBe('Post to stock');
  });

  it('does not draw the button when the server does not list the posting capability for the store', async () => {
    const el = await open(openingImport({ status: 'APPROVED' }), PHARMACIST_CONTEXT);
    expect(q(el, 'post-to-stock')).toBeNull();
  });

  it('does not draw the button on an import that is waiting for approval', async () => {
    const el = await open(openingImport({ status: 'WAITING_APPROVAL' }));
    expect(q(el, 'post-to-stock')).toBeNull();
  });

  it('shows the Posted state with the date and time and no button on a posted import', async () => {
    const el = await open(
      openingImport({
        status: 'APPROVED',
        posted: true,
        postedAt: '2026-11-19T06:30:00Z',
        postedBy: { membershipId: '00000000-0000-4000-8000-000000000011', name: 'Test Pharmacist One' },
      }),
    );
    expect(text(el, 'review-status')).toBe('Posted');
    expect(text(el, 'posted-note')).toContain('Posted on 19 Nov 2026, 12:00 PM by Test Pharmacist One');
    expect(text(el, 'posted-note')).toContain('A correction is a stock adjustment.');
    expect(q(el, 'post-to-stock')).toBeNull();
    expect(q(el, 'approved-note')).toBeNull();
  });

  it('opens the posting sheet and shows the posted import when it closes with one', async () => {
    const el = await open(openingImport({ status: 'APPROVED' }));
    const posted = openingImport({ status: 'APPROVED', posted: true, postedAt: '2026-11-19T06:30:00Z' });
    const spy = vi.spyOn(fixture.debugElement.injector.get(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(posted) } as never);
    q(el, 'post-to-stock')?.click();
    await settle();
    expect(spy).toHaveBeenCalledWith(PostToStockDialog, expect.objectContaining({ ariaLabelledBy: 'post-title' }));
    expect(text(el, 'review-status')).toBe('Posted');
    expect(decided).toHaveLength(1);
  });

  it('reads the import again when the sheet closes without posting, in case someone else posted it', async () => {
    const el = await open(openingImport({ status: 'APPROVED' }));
    vi.spyOn(fixture.debugElement.injector.get(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(null) } as never);
    q(el, 'post-to-stock')?.click();
    http.expectOne(IMPORT_URL).flush(openingImport({ status: 'APPROVED', posted: true, postedAt: '2026-11-19T06:30:00Z' }));
    await settle();
    expect(text(el, 'review-status')).toBe('Posted');
  });
});
