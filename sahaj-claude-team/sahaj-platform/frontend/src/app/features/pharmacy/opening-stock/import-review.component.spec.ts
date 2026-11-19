import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { OWNER_CONTEXT, PHARMACIST_CONTEXT, openingImport, openingLine } from '../pharmacy.fixtures';
import { OpeningStockImport, PharmacyContext } from '../pharmacy.model';
import { ImportReviewComponent } from './import-review.component';

const BASE = '/api/v1/pharmacy';
const IMPORT_URL = `${BASE}/stores/store-central/opening-stock-imports/imp-1`;

describe('ImportReviewComponent', () => {
  let fixture: ComponentFixture<ImportReviewComponent>;
  let http: HttpTestingController;
  let decided: OpeningStockImport[];

  async function open(
    item: OpeningStockImport,
    context: PharmacyContext = OWNER_CONTEXT,
    lines = { items: [openingLine()], total: 1, page: 0, size: 50 },
  ): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
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
    const req = http.expectOne((r) => r.url === `${IMPORT_URL}/lines`);
    expect(req.request.params.get('withIssues')).toBe(item.status === 'NOT_ACCEPTED' ? 'true' : null);
    req.flush(lines);
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

  it('shows the store, lines, total quantity, total value at purchase rate and the warnings', async () => {
    const el = await open(openingImport());
    expect(el.querySelector('#rv-h')?.textContent).toContain('Test Central Store');
    expect(text(el, 'review-status')).toBe('Waiting for approval');
    expect(text(el, 'total-lines')).toBe('412');
    expect(text(el, 'total-quantity')).toBe('18450');
    expect(text(el, 'total-value')).toBe('₹ 12,50,000.00');
    expect(text(el, 'total-warnings')).toBe('2');
    expect(text(el, 'review-meta')).toContain('Uploaded by Test Pharmacist One on 17 Nov 2026');
  });

  it('lists every line with its rates in rupees, its expiry as 31 Dec 2027 and its value', async () => {
    const el = await open(openingImport());
    const cells = Array.from((q(el, 'line-1') as HTMLElement).querySelectorAll('td')).map((c) =>
      c.textContent?.replace(/\s+/g, ' ').trim(),
    );
    expect(cells).toEqual([
      '1',
      'Sample Ceftriaxone 1 g injectionG-000101',
      'TESTB001',
      '31 Dec 2027',
      '₹ 45.50',
      '₹ 30.25',
      '100',
      '₹ 3,025.00',
      '—',
    ]);
  });

  it('shows each problem with its row, its field and its words, error or warning', async () => {
    const line = openingLine({
      rowNo: 17,
      expiryDate: null,
      issues: [
        { rowNo: 17, field: 'expiryDate', code: 'expiry-past', severity: 'ERROR', message: 'Expiry date must be in the future' },
        { rowNo: 17, field: 'purchaseRate', code: 'rate-above-mrp', severity: 'WARNING', message: 'Purchase rate is above MRP' },
      ],
    });
    const el = await open(openingImport({ status: 'NOT_ACCEPTED', errorCount: 1, warningCount: 1, canApprove: false }), OWNER_CONTEXT, {
      items: [line],
      total: 1,
      page: 0,
      size: 50,
    });
    const issues = Array.from(el.querySelectorAll('[data-testid="line-issue"]')).map((i) => i.textContent?.replace(/\s+/g, ' ').trim());
    expect(issues).toEqual([
      'Error · Expiry date: Expiry date must be in the future',
      'Warning · Purchase rate: Purchase rate is above MRP',
    ]);
    expect(q(el, 'not-accepted-note')?.textContent).toContain('Not accepted');
    expect(q(el, 'decision')).toBeNull();
    expect(text(el, 'total-errors')).toBe('1');
    expect(el.textContent).toContain('Row numbers count the data rows only. The header row and the example row are not counted.');
  });

  it('draws Approve and Reject for an owner who did not upload the file', async () => {
    const el = await open(openingImport());
    expect(q(el, 'approve')).toBeTruthy();
    expect(q(el, 'reject')).toBeTruthy();
    expect(q(el, 'approve-lock')).toBeNull();
  });

  it('hides Approve from the owner who uploaded it and says why, but still allows Reject', async () => {
    const el = await open(openingImport({ canApprove: false, approveLockReason: 'SELF_UPLOADED' }));
    expect(q(el, 'approve')).toBeNull();
    expect(q(el, 'reject')).toBeTruthy();
    expect(text(el, 'approve-lock')).toBe('Another owner must approve this import.');
  });

  it('draws neither button for a pharmacist, who can read and upload but not decide', async () => {
    const el = await open(openingImport({ canApprove: false, approveLockReason: 'NOT_OWNER' }), PHARMACIST_CONTEXT);
    expect(q(el, 'approve')).toBeNull();
    expect(q(el, 'reject')).toBeNull();
    expect(text(el, 'approve-lock')).toBe('Only an owner can approve this import.');
  });

  it('asks for a confirmation with the totals, then approves and tells the list', async () => {
    const el = await open(openingImport());
    const spy = vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of(true) } as never);
    (q(el, 'approve') as HTMLButtonElement).click();
    const data = spy.mock.calls[0][1]?.data as { title: string; body: string };
    expect(data.title).toBe('Approve opening stock for Test Central Store?');
    expect(data.body).toContain('412 lines, total value ₹ 12,50,000.00');
    expect(data.body).toContain('cannot be edited or cancelled');
    const post = http.expectOne(`${IMPORT_URL}/approve`);
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toBeNull();
    const done = openingImport({ status: 'APPROVED', canApprove: false });
    post.flush(done);
    await settle();
    expect(decided).toEqual([done]);
    expect(text(el, 'review-status')).toBe('Approved');
    expect(q(el, 'approve')).toBeNull();
    expect(q(el, 'approved-note')?.textContent).toContain('They cannot be used until they are posted to stock');
  });

  it('approves nothing when the confirmation is cancelled', async () => {
    const el = await open(openingImport());
    vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of(false) } as never);
    (q(el, 'approve') as HTMLButtonElement).click();
    http.expectNone(`${IMPORT_URL}/approve`);
  });

  it('shows the server sentence of a refused self-approval and reads the file again', async () => {
    const el = await open(openingImport());
    vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of(true) } as never);
    (q(el, 'approve') as HTMLButtonElement).click();
    http.expectOne(`${IMPORT_URL}/approve`).flush(
      { type: 'https://sahaj.in/problems/self-approval', title: 'Not allowed', status: 403, detail: 'Another owner must approve this import.' },
      { status: 403, statusText: 'Forbidden' },
    );
    http.expectOne(IMPORT_URL).flush(openingImport({ canApprove: false, approveLockReason: 'SELF_UPLOADED' }));
    await settle();
    expect(text(el, 'decision-error')).toBe('Another owner must approve this import.');
    expect(q(el, 'approve')).toBeNull();
    expect(decided).toEqual([]);
  });

  it('opens the reject sheet and tells the list when the file was rejected', async () => {
    const el = await open(openingImport());
    const done = openingImport({ status: 'REJECTED', rejectReasonKey: 'WRONG_FILE', canApprove: false });
    const spy = vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of(done) } as never);
    (q(el, 'reject') as HTMLButtonElement).click();
    expect((spy.mock.calls[0][1] as { data: unknown }).data).toEqual({
      storeId: 'store-central',
      importId: 'imp-1',
      storeName: 'Test Central Store',
    });
    expect((spy.mock.calls[0][1] as { ariaLabelledBy: string }).ariaLabelledBy).toBe('reject-title');
    await settle();
    expect(decided).toEqual([done]);
    expect(text(el, 'review-status')).toBe('Rejected');
    expect(text(el, 'reject-reason')).toBe('Reason: Wrong file');
  });

  describe('a file in DRAFT (DEC-076)', () => {
    const draft = (over: Partial<OpeningStockImport> = {}) =>
      openingImport({ status: 'DRAFT', canApprove: false, approveLockReason: null, submittedAt: null, ...over });

    it('draws Reject, and not Approve and no lock note, for an owner who may approve at the store', async () => {
      const el = await open(draft());
      expect(text(el, 'review-status')).toBe('Draft');
      expect(q(el, 'decision')).toBeTruthy();
      expect(q(el, 'reject')).toBeTruthy();
      expect(q(el, 'approve')).toBeNull();
      expect(q(el, 'approve-lock')).toBeNull();
    });

    it('draws Reject for the owner who started the draft too', async () => {
      const el = await open(draft({ approveLockReason: 'SELF_UPLOADED' }));
      expect(q(el, 'reject')).toBeTruthy();
      expect(q(el, 'approve-lock')).toBeNull();
    });

    it('draws nothing for a pharmacist, who can only replace their own draft', async () => {
      const el = await open(draft(), PHARMACIST_CONTEXT);
      expect(q(el, 'decision')).toBeNull();
      expect(q(el, 'reject')).toBeNull();
    });

    it('opens the same reject sheet, and shows the file as Rejected after it', async () => {
      const el = await open(draft());
      const done = openingImport({ status: 'REJECTED', rejectReasonKey: 'WRONG_STORE', canApprove: false });
      const spy = vi.spyOn(dialog(), 'open').mockReturnValue({ afterClosed: () => of(done) } as never);
      (q(el, 'reject') as HTMLButtonElement).click();
      expect((spy.mock.calls[0][1] as { data: unknown }).data).toEqual({
        storeId: 'store-central',
        importId: 'imp-1',
        storeName: 'Test Central Store',
      });
      await settle();
      expect(decided).toEqual([done]);
      expect(text(el, 'review-status')).toBe('Rejected');
      expect(q(el, 'decision')).toBeNull();
    });
  });

  it.each(['NOT_ACCEPTED', 'APPROVED', 'REJECTED', 'REPLACED'] as const)(
    'draws no Reject on a file in %s, which the server would answer with import-state',
    async (status) => {
      const el = await open(openingImport({ status, canApprove: false, approveLockReason: null }));
      expect(q(el, 'decision')).toBeNull();
      expect(q(el, 'reject')).toBeNull();
    },
  );

  it('filters the lines to the ones with a problem or warning', async () => {
    const el = await open(openingImport());
    (q(el, 'only-issues')?.querySelector('input') as HTMLInputElement).click();
    const req = http.expectOne((r) => r.url === `${IMPORT_URL}/lines`);
    expect(req.request.params.get('withIssues')).toBe('true');
    req.flush({ items: [], total: 0, page: 0, size: 50 });
    await settle();
    expect(el.textContent).toContain('No lines to show.');
  });

  it('pages the lines by 50', async () => {
    const items = Array.from({ length: 50 }, (_, i) => openingLine({ rowNo: i + 1 }));
    const el = await open(openingImport(), OWNER_CONTEXT, { items, total: 120, page: 0, size: 50 });
    expect(text(el, 'lines-pager')).toContain('Showing 1–50 of 120');
    (q(el, 'lines-next') as HTMLButtonElement).click();
    const req = http.expectOne((r) => r.url === `${IMPORT_URL}/lines`);
    expect(req.request.params.get('page')).toBe('1');
    req.flush({ items, total: 120, page: 1, size: 50 });
    await settle();
    expect(text(el, 'lines-pager')).toContain('Showing 51–100 of 120');
  });

  it('says "Could not load. Try again." with a retry when the file cannot be read', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(OWNER_CONTEXT);
    fixture = TestBed.createComponent(ImportReviewComponent);
    fixture.componentRef.setInput('storeId', 'store-central');
    fixture.componentRef.setInput('importId', 'imp-1');
    fixture.detectChanges();
    http.expectOne(IMPORT_URL).error(new ProgressEvent('error'));
    await settle();
    expect(text(fixture.nativeElement, 'review-error')).toContain('Could not load. Try again.');
  });
});
