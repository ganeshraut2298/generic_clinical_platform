import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { BillDetailSection } from './bill-detail.section';
import { bill, saleContext } from './sale.fixtures';
import { Bill } from '../pharmacy-sale.model';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-ward`;

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

describe('BillDetailSection', () => {
  let fixture: ComponentFixture<BillDetailSection>;
  let http: HttpTestingController;

  async function open(b: Bill): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(saleContext());
    fixture = TestBed.createComponent(BillDetailSection);
    fixture.componentRef.setInput('billId', b.id);
    fixture.detectChanges();
    http.expectOne(`${S}/bills/${b.id}`).flush(b);
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush({ lists: [] });
    http.expectOne(`${S}/second-signers`).flush({ items: [] });
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  interface Internals {
    backOnShelf: { set(v: boolean): void };
    cancel(b: Bill, reasonKey: string, note: string | null): void;
  }

  afterEach(() => http.verify());

  it('shows exactly the server sentence when a cancel is not allowed (403 cancel-not-allowed)', async () => {
    const b = bill();
    const el = await open(b);
    const me = fixture.componentInstance as unknown as Internals;
    me.backOnShelf.set(true);
    me.cancel(b, 'entry-error', null);
    http.expectOne(`${S}/bills/${b.id}/cancel`).flush(
      {
        type: 'https://sahaj.in/problems/cancel-not-allowed',
        title: 'Not allowed',
        status: 403,
        detail: "A pharmacist can cancel only today's bills.",
      },
      { status: 403, statusText: 'Forbidden', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(text(el, 'bill-error')).toBe("A pharmacist can cancel only today's bills.");
  });

  describe('refund of a cancelled bill', () => {
    const cancelled = () =>
      bill({
        status: 'CANCELLED',
        canCancel: false,
        refundDuePaise: 24000,
        payments: [
          { mode: 'CASH', amountPaise: 10000 },
          { mode: 'UPI', amountPaise: 14000 },
        ],
      });

    it('pre-fills one amount per mode with the original split and sends both', async () => {
      const b = cancelled();
      const el = await open(b);
      expect((q(el, 'refund-amount-CASH') as HTMLInputElement).value).toBe('100');
      expect((q(el, 'refund-amount-UPI') as HTMLInputElement).value).toBe('140');
      expect(q(el, 'refund-mismatch')).toBeNull();
      q(el, 'refund-save')?.click();
      const req = http.expectOne(`${S}/bills/${b.id}/refund`);
      expect(req.request.body.refunds).toEqual([
        { mode: 'CASH', amountPaise: 10000 },
        { mode: 'UPI', amountPaise: 14000 },
      ]);
      req.flush({ ...b, refundDuePaise: 0 });
      await settle();
    });

    it('does not send when the total differs from the due', async () => {
      const b = cancelled();
      const el = await open(b);
      const cash = q(el, 'refund-amount-CASH') as HTMLInputElement;
      cash.value = '50';
      cash.dispatchEvent(new Event('input'));
      await settle();
      expect(text(el, 'refund-mismatch')).toContain('Refunds must add up to');
      q(el, 'refund-save')?.click();
      await settle();
      expect(text(el, 'bill-error')).toContain('Refunds must add up to');
    });
  });
});
