import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { DebitNote } from '../pharmacy-buying.model';
import { PharmacyContext } from '../pharmacy.model';
import { DEBIT_NOTE_CONFIG, DebitNoteDialog } from './debit-note.dialog';
import { BUYER_CONTEXT, BUYER_NO_X_CONTEXT, debitNote } from './purchase.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-central`;

describe('DebitNoteDialog', () => {
  let fixture: ComponentFixture<DebitNoteDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(dn: DebitNote, context: PharmacyContext = BUYER_CONTEXT): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { storeId: 'store-central', dnId: 'dn-1' } },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(DebitNoteDialog);
    fixture.detectChanges();
    http.expectOne(`${S}/debit-notes/dn-1`).flush(dn);
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
  const type = async (el: HTMLElement, id: string, value: string) => {
    const input = q(el, id) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  };

  afterEach(() => http.verify());

  it('shows the title with the credit and the GST reversal, the lines and the five steps with who and when', async () => {
    const el = await open(debitNote());
    expect(DEBIT_NOTE_CONFIG.ariaLabelledBy).toBe('dn-title');
    expect(text(el, 'dn-title')).toContain('Debit note DN-26-27/0001 · 1 line · ₹ 705.00 + GST reversal ₹ 84.60');
    expect(text(el, 'dn-status')).toBe('Created');
    expect(el.textContent).toContain('Changed from policy');
    const steps = Array.from(el.querySelectorAll('[data-testid="dn-steps"] li')).map((l) => l.textContent?.replace(/\s+/g, ' ').trim());
    expect(steps[0]).toContain('Created · Test Owner');
    expect(steps.map((s) => s?.split(' · ')[0])).toEqual([
      'Created',
      'Picked & packed',
      'Handed to vendor rep',
      'Credit note received',
      'Adjusted in payable',
    ]);
  });

  it('marks picked and packed, then asks for the representative name at the hand-over', async () => {
    const el = await open(debitNote());
    q(el, 'dn-picked')?.click();
    const picked = http.expectOne(`${S}/debit-notes/dn-1/picked`);
    expect(picked.request.body).toEqual({ version: 2 });
    picked.flush(debitNote({ status: 'PICKED', version: 3 }));
    await settle();
    expect(text(el, 'dn-status')).toBe('Picked and packed');
    q(el, 'dn-hand-over')?.click();
    await settle();
    expect((q(el, 'dn-panel-confirm') as HTMLButtonElement).disabled).toBe(true);
    await type(el, 'f-dn-rep', 'Test Representative');
    q(el, 'dn-panel-confirm')?.click();
    const handOver = http.expectOne(`${S}/debit-notes/dn-1/hand-over`);
    expect(handOver.request.body).toMatchObject({ version: 3, representativeName: 'Test Representative', secondSigner: null });
    handOver.flush(debitNote({ status: 'HANDED_OVER', version: 4, representativeName: 'Test Representative' }));
    await settle();
    expect(text(el, 'dn-status')).toBe('Handed to vendor rep');
    expect(q(el, 'dn-cancel')).toBeNull();
    expect(q(el, 'dn-credit')).toBeTruthy();
  });

  it('records the credit note and shows the difference and the payable effect', async () => {
    const el = await open(debitNote({ status: 'HANDED_OVER' }));
    q(el, 'dn-credit')?.click();
    await settle();
    await type(el, 'f-cn-no', 'TEST-CN-1');
    await type(el, 'f-cn-amount', '800');
    q(el, 'dn-panel-confirm')?.click();
    const req = http.expectOne(`${S}/debit-notes/dn-1/credit-note`);
    expect(req.request.body).toMatchObject({ version: 2, creditNoteNo: 'TEST-CN-1', amountPaise: 80000 });
    req.flush(
      debitNote({
        status: 'CREDIT_NOTE_RECEIVED',
        creditNote: { creditNoteNo: 'TEST-CN-1', creditNoteDate: '2026-11-19', amountPaise: 80000, differencePaise: 1040 },
        payableEffect: { appliedPaise: 70000, unappliedCreditPaise: 10000 },
        steps: debitNote().steps.map((s) => ({ ...s, at: '2026-11-19T05:00:00Z', by: { membershipId: 'm-me', name: 'Test Owner' } })),
      }),
    );
    await settle();
    expect(text(el, 'dn-credit-note')).toContain('TEST-CN-1');
    expect(text(el, 'dn-difference')).toBe('Difference from the debit note +₹ 10.40');
    expect(text(el, 'dn-payable')).toContain('Adjusted in payable ₹ 700.00');
    expect(text(el, 'dn-payable')).toContain('₹ 100.00 of credit is not yet applied');
    expect(q(el, 'dn-credit')).toBeNull();
  });

  it('cancels a debit note that is not handed over only with a reason', async () => {
    const el = await open(debitNote());
    q(el, 'dn-cancel')?.click();
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush({
      lists: [{ listKey: 'approval-reject', reasons: [{ reasonKey: 'approval-reject.other', label: 'Other', needsNote: true, fixed: false }] }],
    });
    await settle();
    expect((q(el, 'dn-panel-confirm') as HTMLButtonElement).disabled).toBe(true);
    q(el, 'dn-reason-approval-reject.other')?.click();
    await settle();
    q(el, 'dn-panel-confirm')?.click();
    const req = http.expectOne(`${S}/debit-notes/dn-1/cancel`);
    expect(req.request.body).toEqual({ version: 2, reasonKey: 'approval-reject.other' });
    req.flush(debitNote({ status: 'CANCELLED' }));
    await settle();
    expect(text(el, 'dn-status')).toBe('Cancelled');
  });

  it('creates a draft prepared by a receipt, and a draft can be discarded', async () => {
    const el = await open(debitNote({ status: 'DRAFT', number: null }));
    expect(text(el, 'dn-title')).toContain('Debit note (draft)');
    expect(q(el, 'dn-discard')).toBeTruthy();
    q(el, 'dn-submit')?.click();
    const req = http.expectOne(`${S}/debit-notes/dn-1/submit`);
    expect(req.request.body.version).toBe(2);
    req.flush(debitNote());
    await settle();
    expect(text(el, 'dn-status')).toBe('Created');
  });

  it('offers the credit note step at once for a debit note of short supply only, with no hand-over', async () => {
    const base = debitNote();
    const el = await open(debitNote({ lines: base.lines.map((l) => ({ ...l, type: 'SHORT_SUPPLY' as const, batch: null, sourceState: null })) }));
    expect(q(el, 'dn-hand-over')).toBeNull();
    expect(q(el, 'dn-credit')).toBeTruthy();
  });

  it('asks for a second signer on a Schedule X line and sends the PIN only with the hand-over', async () => {
    const base = debitNote();
    const x = debitNote({ lines: base.lines.map((l) => ({ ...l, scheduleX: true })) });
    const el = await open(x);
    q(el, 'dn-hand-over')?.click();
    http.expectOne(`${S}/second-signers`).flush({ items: [{ membershipId: 'm-2', name: 'Test Pharmacist Two', roleLabel: 'Pharmacist' }] });
    await settle();
    expect(q(el, 'dn-signer-note')).toBeTruthy();
    expect(q(el, 'f-dn-pin')?.getAttribute('type')).toBe('password');
    await type(el, 'f-dn-rep', 'Test Representative');
    expect((q(el, 'dn-panel-confirm') as HTMLButtonElement).disabled).toBe(true);
    const cmp = fixture.componentInstance as unknown as { signerId: { setValue(v: string): void }; pin: { setValue(v: string): void; value: string } };
    cmp.signerId.setValue('m-2');
    cmp.pin.setValue('4821');
    await settle();
    q(el, 'dn-panel-confirm')?.click();
    const req = http.expectOne(`${S}/debit-notes/dn-1/hand-over`);
    expect(req.request.body.secondSigner).toEqual({ membershipId: 'm-2', pin: '4821' });
    req.flush(debitNote({ status: 'HANDED_OVER' }));
    await settle();
    expect(cmp.pin.value).toBe('');
  });

  it('says a pharmacist must hand over a Schedule X item when the caller may not handle it', async () => {
    const base = debitNote();
    const el = await open(debitNote({ lines: base.lines.map((l) => ({ ...l, scheduleX: true })) }), BUYER_NO_X_CONTEXT);
    q(el, 'dn-hand-over')?.click();
    http.expectOne(`${S}/second-signers`).flush({ items: [] });
    await settle();
    expect(text(el, 'dn-pharmacist-needed')).toBe('A pharmacist must hand over a Schedule X item.');
  });

  it('shows the server sentence when a step is refused', async () => {
    const el = await open(debitNote());
    q(el, 'dn-picked')?.click();
    http.expectOne(`${S}/debit-notes/dn-1/picked`).flush(
      { type: 'https://sahaj.in/problems/changed', title: 'Changed', status: 409, detail: 'Someone changed this debit note. Reload and try again.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(text(el, 'dn-action-error')).toContain('Someone changed this debit note');
  });
});
