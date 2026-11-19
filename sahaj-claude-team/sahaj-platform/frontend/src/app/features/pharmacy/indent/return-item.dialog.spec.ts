import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ReturnItem } from '../pharmacy-indent.model';
import { returnItem, SCHEDULE_X_PRODUCT } from './indent.fixtures';
import { RETURN_ITEM_CONFIG, ReturnItemData, ReturnItemDialog } from './return-item.dialog';

const BASE = '/api/v1/pharmacy';
const C = `${BASE}/stores/store-central`;

const DISPUTE = {
  lists: [{ listKey: 'dispute', reasons: [{ reasonKey: 'dispute.other', label: 'Other', needsNote: true, fixed: true }] }],
};

describe('ReturnItemDialog', () => {
  let fixture: ComponentFixture<ReturnItemDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(over: Partial<ReturnItem> = {}, data: Partial<ReturnItemData> = {}): Promise<HTMLElement> {
    close = vi.fn();
    const side = data.side ?? 'ACCEPTING';
    const storeId = side === 'ACCEPTING' ? 'store-central' : 'store-ward';
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { storeId, itemId: 'ret-1', side, canSignScheduleX: false, ...data } },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ReturnItemDialog);
    fixture.detectChanges();
    http.expectOne(`${BASE}/stores/${storeId}/return-items/ret-1`).flush(returnItem(over));
    if (side === 'ACCEPTING') {
      http.expectOne((r) => r.url === `${BASE}/reasons`).flush(DISPUTE);
      if (data.canSignScheduleX) {
        http.expectOne(`${C}/second-signers`).flush({ items: [{ membershipId: 'm-2', name: 'Test Pharmacist Two', roleLabel: 'Pharmacist' }] });
      }
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
  const click = async (el: HTMLElement, id: string) => {
    q(el, id)?.click();
    await settle();
  };

  afterEach(() => http.verify());

  it('shows the item and defaults Put back into to quarantine for a damaged item', async () => {
    const el = await open();
    expect(RETURN_ITEM_CONFIG.ariaLabelledBy).toBe('return-item-title');
    expect(text(el, 'return-facts')).toContain('Sample Ceftriaxone 1 g injection · TESTB001 · Mar 2028');
    expect(text(el, 'return-qty')).toBe('2 vial');
    expect(q(el, 'put-quarantine')?.getAttribute('aria-pressed')).toBe('true');
    expect(q(el, 'put-saleable')?.getAttribute('aria-pressed')).toBe('false');
    expect(text(el, 'return-accept')).toBe('Accept 2 units back');
  });

  it('defaults a wrong item to saleable stock, and an expired batch can only go to quarantine', async () => {
    const el = await open({ defaultPutBackInto: 'SALEABLE', reason: { reasonKey: 'receipt-reject.wrong-item', label: 'Wrong item' } });
    expect(q(el, 'put-saleable')?.getAttribute('aria-pressed')).toBe('true');
  });

  it('keeps an expired batch out of saleable stock', async () => {
    const el = await open({ defaultPutBackInto: 'SALEABLE', expired: true });
    expect(q(el, 'put-quarantine')?.getAttribute('aria-pressed')).toBe('true');
    expect((q(el, 'put-saleable') as HTMLButtonElement).disabled).toBe(true);
    expect(text(el, 'return-expired')).toBe('This batch has expired. It can only go to quarantine.');
  });

  it('accepts the units back into the chosen place once and shows the result panel', async () => {
    const el = await open();
    await click(el, 'put-saleable');
    q(el, 'return-accept')?.click();
    q(el, 'return-accept')?.click();
    const req = http.expectOne(`${C}/return-items/ret-1/accept`);
    expect(req.request.body).toMatchObject({ version: 1, putBackInto: 'SALEABLE', secondSigner: null });
    req.flush(returnItem({ status: 'ACCEPTED_SALEABLE', resultNote: null }));
    await settle();
    expect(text(el, 'return-result')).toBe('Return accepted. 2 units added to Central Store: saleable stock.');
    expect(text(el, 'return-status')).toBe('Accepted by CS · saleable');
    expect(q(el, 'return-accept')).toBeNull();
  });

  it('shows the server sentence when two people act on the same item', async () => {
    const el = await open();
    await click(el, 'return-accept');
    http.expectOne(`${C}/return-items/ret-1/accept`).flush(
      { type: 'https://sahaj.in/problems/changed', title: 'Changed', status: 409, detail: 'This item was changed by someone else. Open it again.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(text(el, 'return-error')).toContain('changed by someone else');
    expect(q(el, 'return-accept')).toBeTruthy();
  });

  it('disputes with a reason and a note, and "Other" needs the note', async () => {
    const el = await open();
    await click(el, 'return-dispute-start');
    await click(el, 'return-dispute-send');
    expect(text(el, 'return-error')).toBe('Choose a reason for the dispute.');
    await click(el, 'dispute-reason-dispute.other');
    await click(el, 'return-dispute-send');
    expect(text(el, 'return-error')).toBe('Write a note of at least 4 characters.');
    const note = q(el, 'dispute-note') as HTMLTextAreaElement;
    note.value = 'Seal was intact';
    note.dispatchEvent(new Event('input'));
    await settle();
    await click(el, 'return-dispute-send');
    const req = http.expectOne(`${C}/return-items/ret-1/dispute`);
    expect(req.request.body).toEqual({ version: 1, reasonKey: 'dispute.other', note: 'Seal was intact' });
    req.flush(returnItem({ status: 'DISPUTED' }));
    await settle();
    expect(text(el, 'return-result')).toContain('Disputed. Test Ward 3B store sees the reason.');
  });

  it('blocks a Schedule X item for a person who is not a pharmacist', async () => {
    const el = await open({ scheduleX: true, product: SCHEDULE_X_PRODUCT });
    expect(q(el, 'return-x-blocked')).toBeTruthy();
    await click(el, 'return-accept');
    expect(text(el, 'return-error')).toContain('accepted back by a pharmacist');
    http.expectNone(`${C}/return-items/ret-1/accept`);
  });

  it('sends the second signer and the PIN for a Schedule X item and clears the PIN', async () => {
    const el = await open({ scheduleX: true, product: SCHEDULE_X_PRODUCT }, { canSignScheduleX: true });
    (fixture.componentInstance as unknown as { signerId: { set(v: string): void }; pin: { set(v: string): void } }).signerId.set('m-2');
    (fixture.componentInstance as unknown as { pin: { set(v: string): void } }).pin.set('1234');
    await click(el, 'return-accept');
    const req = http.expectOne(`${C}/return-items/ret-1/accept`);
    expect(req.request.body.secondSigner).toEqual({ membershipId: 'm-2', pin: '1234' });
    req.flush(returnItem({ status: 'ACCEPTED_QUARANTINE' }));
    await settle();
    expect((fixture.componentInstance as unknown as { pin(): string }).pin()).toBe('');
  });

  it('shows the returning store the dispute reason and lets it add a reply', async () => {
    const el = await open(
      { status: 'DISPUTED', dispute: { reason: { reasonKey: 'dispute.other', label: 'Other', note: 'Seal broken' }, by: { membershipId: 'm', name: 'Test Central Store User' }, at: '2026-11-19T07:00:00Z' } },
      { side: 'RETURNING' },
    );
    expect(text(el, 'return-dispute')).toContain('Disputed by Test Central Store User · Other · Seal broken');
    expect(q(el, 'return-accept')).toBeNull();
    const note = q(el, 'reply-note') as HTMLTextAreaElement;
    note.value = 'It was sealed';
    note.dispatchEvent(new Event('input'));
    await settle();
    await click(el, 'return-reply');
    const req = http.expectOne(`${BASE}/stores/store-ward/return-items/ret-1/notes`);
    expect(req.request.body.note).toBe('It was sealed');
    req.flush(returnItem({ status: 'DISPUTED' }), { status: 201, statusText: 'Created' });
    await settle();
    expect(text(el, 'return-result')).toBe('Your reply was added.');
  });
});
