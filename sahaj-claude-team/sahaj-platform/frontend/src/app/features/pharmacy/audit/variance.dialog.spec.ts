import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { VarianceReview } from '../pharmacy-stockcount.model';
import { stockAudit, varianceReview } from '../stockuse/stockuse.fixtures';
import { VARIANCE_CONFIG, VarianceDialog } from './variance.dialog';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-ward`;
const A = `${S}/stock-audits/aud-1`;

const REASONS = {
  lists: [
    {
      listKey: 'stock-adjustment',
      reasons: [
        { reasonKey: 'stock-adjustment.consumption-not-entered', label: 'Consumption not entered', needsNote: false, fixed: false },
        { reasonKey: 'stock-adjustment.breakage', label: 'Breakage', needsNote: false, fixed: false },
      ],
    },
  ],
};

describe('VarianceDialog', () => {
  let fixture: ComponentFixture<VarianceDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(review: VarianceReview = varianceReview()): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { storeId: 'store-ward', auditId: 'aud-1' } },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(VarianceDialog);
    fixture.detectChanges();
    http.expectOne(`${A}/variance`).flush(review);
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush(REASONS);
    http.expectOne(`${S}/second-signers`).flush({ items: [] });
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
  const click = async (el: HTMLElement, id: string) => {
    q(el, id)?.click();
    await settle();
  };

  afterEach(() => http.verify());

  it('shows the book quantity, the count and the difference in red for the reviewer', async () => {
    const el = await open();
    expect(VARIANCE_CONFIG.ariaLabelledBy).toBe('variance-title');
    expect(text(el, 'variance-header')).toBe('Test Ward 3B store · audit AUD-0093');
    expect(text(el, 'variance-system-0')).toBe('8');
    expect(text(el, 'variance-diff-0')).toBe('−1');
    expect(q(el, 'variance-diff-0')?.classList.contains('loss')).toBe(true);
    expect(text(el, 'variance-tiles')).toContain('− ₹ 412.00');
  });

  it('needs a reason for every difference before it posts', async () => {
    const el = await open();
    await click(el, 'variance-approve');
    expect(el.textContent).toContain('Choose a reason');
    http.expectNone(`${A}/approve-and-post`);
  });

  it('saves the reasons, then posts once, and says the adjustment is posted', async () => {
    const el = await open();
    await click(el, 'variance-reason-0-stock-adjustment.breakage');
    await click(el, 'variance-approve');
    const saved = http.expectOne(`${A}/variance`);
    expect(saved.request.method).toBe('PUT');
    expect(saved.request.body).toEqual({ version: 5, lines: [{ auditLineId: 'al-1', reasonKey: 'stock-adjustment.breakage' }] });
    saved.flush(varianceReview({ version: 6 }));
    await settle();
    const post = http.expectOne(`${A}/approve-and-post`);
    expect(post.request.body).toMatchObject({ version: 6, secondSigner: null });
    post.flush(stockAudit({ status: 'CLOSED', withVariance: 1 }));
    await settle();
    expect(text(el, 'variance-done')).toContain('Adjustment posted');
    await click(el, 'variance-finish');
    expect(close).toHaveBeenCalledWith({ audit: expect.objectContaining({ status: 'CLOSED' }) });
  });

  it('says the adjustment waits for the owner when the rule sends it up', async () => {
    const el = await open();
    await click(el, 'variance-reason-0-stock-adjustment.breakage');
    await click(el, 'variance-approve');
    http.expectOne(`${A}/variance`).flush(varianceReview({ version: 6 }));
    await settle();
    http
      .expectOne(`${A}/approve-and-post`)
      .flush(
        stockAudit({
          status: 'WAITING_APPROVAL',
          approval: {
            requestId: 'r1',
            requestVersion: 0,
            levelNo: 2,
            status: 'PENDING',
            currentLevel: 2,
            ruleText: 'Above the HOD limit',
            waitingFor: 'OWNER',
            escalated: false,
            needsAnotherApprover: false,
            canDecide: false,
          },
        }),
      );
    await settle();
    expect(text(el, 'variance-done')).toContain('Waiting for owner approval');
  });

  it('shows "Stock moved since the count. Recount." and sends the chosen lines back for a recount', async () => {
    const base = varianceReview();
    const el = await open({ ...base, lines: [{ ...base.lines[0], stockMoved: true }] });
    expect(text(el, 'variance-moved-0')).toBe('Stock moved since the count. Recount.');
    await click(el, 'variance-recount-0');
    await click(el, 'variance-recount');
    const req = http.expectOne(`${A}/recount`);
    expect(req.request.body).toEqual({ version: 5, auditLineIds: ['al-1'] });
    req.flush(stockAudit({ status: 'RECOUNT' }));
    await settle();
    expect(text(el, 'variance-done')).toBe('Sent back to the counter for a recount.');
  });

  it('offers "Post as consumption instead" for a missed consumption and hands the line back', async () => {
    const base = varianceReview();
    const el = await open({ ...base, lines: [{ ...base.lines[0], suggestConsumption: true }] });
    await click(el, 'variance-consumption-0');
    expect(close).toHaveBeenCalledWith({ consumptionLineId: 'al-1' });
  });

  it('asks for a second signer when a Schedule X line differs', async () => {
    const base = varianceReview();
    const el = await open({ ...base, lines: [{ ...base.lines[0], scheduleX: true }] });
    expect(q(el, 'variance-x-note')).toBeTruthy();
    await click(el, 'variance-reason-0-stock-adjustment.breakage');
    await click(el, 'variance-approve');
    expect(el.textContent).toContain('Choose the second signer');
    http.expectNone(`${A}/variance`);
  });
});
