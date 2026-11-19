import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { postingPreview } from '../inventory/inventory.fixtures';
import { OpeningStockPostingPreview } from '../pharmacy-inventory.model';
import { openingImport } from '../pharmacy.fixtures';
import { POST_TO_STOCK_CONFIG, POSTING_TEXT, PostToStockData, PostToStockDialog } from './post-to-stock.dialog';

const BASE = '/api/v1/pharmacy';
const DATA: PostToStockData = { storeId: 'store-central', importId: 'imp-1', storeName: 'Test Central Store' };
const PREVIEW_URL = `${BASE}/stores/store-central/opening-stock-imports/imp-1/posting-preview`;
const POST_URL = `${BASE}/stores/store-central/opening-stock-imports/imp-1/post`;

describe('PostToStockDialog', () => {
  let fixture: ComponentFixture<PostToStockDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(preview: OpeningStockPostingPreview = postingPreview()): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: DATA },
        { provide: MatDialogRef, useValue: { close, disableClose: false } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PostToStockDialog);
    fixture.detectChanges();
    http.expectOne(PREVIEW_URL).flush(preview);
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

  it('names the sheet and shows the lines, quantity, value, uploader and approver', async () => {
    const el = await open();
    expect(POST_TO_STOCK_CONFIG.ariaLabelledBy).toBe('post-title');
    expect(el.querySelector('#post-title')?.textContent).toBe('Post to stock');
    expect(text(el, 'post-lines')).toBe('412');
    expect(text(el, 'post-quantity')).toBe('18450');
    expect(text(el, 'post-value')).toBe('₹ 12,50,000.50');
    expect(el.textContent).toContain('Test Pharmacist One');
    expect(el.textContent).toContain('Test Owner Two');
    expect(q(el, 'confirm-post')).toBeTruthy();
  });

  it('lists the lines that expired since approval and says they go to Quarantine, with the expiry as month and year', async () => {
    const el = await open(
      postingPreview({
        expiredLines: [{ rowNo: 7, productName: 'Sample Cough syrup', batchNo: 'OLD1', expiryDate: '2026-10-31', quantity: 12 }],
      }),
    );
    expect(text(el, 'post-expired')).toContain('1 line has expired since approval');
    expect(text(el, 'post-expired')).toContain('Quarantine');
    expect(text(el, 'post-expired-list')).toContain('Row 7 · Sample Cough syrup · batch OLD1 · expired Oct 2026 · 12');
    expect(q(el, 'confirm-post')).toBeTruthy();
  });

  it('names the lines whose batch exists with another MRP, and draws no Post button', async () => {
    const el = await open(
      postingPreview({
        canPost: false,
        postLockReason: 'MRP_MISMATCH',
        mrpMismatchLines: [{ rowNo: 3, batchNo: 'AB1', fileMrp: '45.5000', batchMrp: '48.0000' }],
      }),
    );
    expect(text(el, 'post-mrp')).toContain('Same batch with a different MRP');
    expect(text(el, 'post-mrp-list')).toContain('Row 3 · batch AB1 · file MRP ₹ 45.50, batch MRP ₹ 48.00');
    expect(q(el, 'confirm-post')).toBeNull();
  });

  it('says the import is already posted and draws no Post button', async () => {
    const el = await open(postingPreview({ canPost: false, postLockReason: 'ALREADY_POSTED' }));
    expect(text(el, 'post-lock')).toBe('This import was already posted.');
    expect(q(el, 'confirm-post')).toBeNull();
  });

  it('posts once with one client key, shows the posting text, disables the button, and closes with the posted import', async () => {
    const el = await open();
    q(el, 'confirm-post')?.click();
    await settle();
    expect(text(el, 'posting-text')).toBe(POSTING_TEXT);
    expect((q(el, 'confirm-post') as HTMLButtonElement).disabled).toBe(true);
    // a second tap while the request runs sends nothing more
    q(el, 'confirm-post')?.click();
    const req = http.expectOne(POST_URL);
    expect(req.request.body.clientKey).toMatch(/^[0-9a-f-]{36}$/);
    req.flush(openingImport({ posted: true, postedAt: '2026-11-19T06:00:00Z' }));
    await settle();
    expect(close).toHaveBeenCalledOnce();
    expect(close.mock.calls[0][0].posted).toBe(true);
  });

  it('shows the server sentence when the import was posted meanwhile, and reads the preview again', async () => {
    const el = await open();
    q(el, 'confirm-post')?.click();
    http.expectOne(POST_URL).flush(
      { type: 'https://sahaj.in/problems/already-posted', title: 'Already posted', status: 409, detail: 'This import was already posted.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(text(el, 'post-error')).toBe('This import was already posted.');
    http.expectOne(PREVIEW_URL).flush(postingPreview({ canPost: false, postLockReason: 'ALREADY_POSTED' }));
    await settle();
    expect(q(el, 'confirm-post')).toBeNull();
    expect(close).not.toHaveBeenCalled();
  });

  it('keeps the same client key for a retry after a lost connection, so the post is a replay', async () => {
    const el = await open();
    q(el, 'confirm-post')?.click();
    const first = http.expectOne(POST_URL);
    const key = first.request.body.clientKey;
    first.flush(null, { status: 0, statusText: 'Unknown Error' });
    await settle();
    expect(text(el, 'post-error')).toBe('Could not save. Check your connection and try again.');
    q(el, 'confirm-post')?.click();
    const second = http.expectOne(POST_URL);
    expect(second.request.body.clientKey).toBe(key);
    second.flush(openingImport({ posted: true }));
  });

  it('shows a load error with a retry when the preview cannot be read', async () => {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: DATA },
        { provide: MatDialogRef, useValue: { close, disableClose: false } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PostToStockDialog);
    fixture.detectChanges();
    http.expectOne(PREVIEW_URL).flush(null, { status: 500, statusText: 'Server Error' });
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    expect(text(el, 'post-load-error')).toContain('Could not load. Try again.');
    expect(q(el, 'confirm-post')).toBeNull();
  });
});
