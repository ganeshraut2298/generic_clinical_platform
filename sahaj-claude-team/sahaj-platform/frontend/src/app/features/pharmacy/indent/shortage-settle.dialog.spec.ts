import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Shortage } from '../pharmacy-indent.model';
import { SCHEDULE_X_PRODUCT, shortage } from './indent.fixtures';
import { SHORTAGE_CONFIG, ShortageSettleDialog } from './shortage-settle.dialog';

const BASE = '/api/v1/pharmacy';
const C = `${BASE}/stores/store-central`;

describe('ShortageSettleDialog', () => {
  let fixture: ComponentFixture<ShortageSettleDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(over: Partial<Shortage> = {}, canSign = false): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { storeId: 'store-central', shortage: shortage(over), canSignScheduleX: canSign } },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ShortageSettleDialog);
    fixture.detectChanges();
    if (canSign && over.product?.schedule === 'X') {
      http.expectOne(`${C}/second-signers`).flush({ items: [{ membershipId: 'm-2', name: 'Test Pharmacist Two', roleLabel: 'Pharmacist' }] });
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

  it('shows the banner "Shortage, not a return", the three ways to settle and "Settle shortage of n"', async () => {
    const el = await open();
    expect(SHORTAGE_CONFIG.ariaLabelledBy).toBe('shortage-title');
    expect(text(el, 'shortage-banner')).toBe(
      'Shortage, not a return. 3 units never reached Test Ward 3B store, so nothing comes back to the shelf. Central Store investigates: found in store, transit loss, or claim from carrier.',
    );
    expect(text(el, 'shortage-status')).toBe('Short · under investigation');
    expect(q(el, 'settle-FOUND')?.textContent?.trim()).toBe('Found in Central Store');
    expect(q(el, 'settle-TRANSIT_LOSS')?.textContent?.trim()).toBe('Transit loss · write off');
    expect(q(el, 'settle-CARRIER_CLAIM')?.textContent?.trim()).toBe('Claim from carrier');
    expect(text(el, 'shortage-settle')).toBe('Settle shortage of 3');
    expect((q(el, 'shortage-settle') as HTMLButtonElement).disabled).toBe(true);
  });

  it('settles as found once and shows the result', async () => {
    const el = await open();
    await click(el, 'settle-FOUND');
    q(el, 'shortage-settle')?.click();
    q(el, 'shortage-settle')?.click();
    const req = http.expectOne(`${C}/shortages/short-1/settle`);
    expect(req.request.body).toMatchObject({ version: 1, outcome: 'FOUND', note: null, secondSigner: null });
    req.flush(shortage({ status: 'FOUND' }));
    await settle();
    expect(text(el, 'shortage-result')).toBe('Shortage settled. 3 units found and added to Central Store.');
  });

  it('says a transit loss waits for the write-off approval when the rule asks for one', async () => {
    const el = await open();
    await click(el, 'settle-TRANSIT_LOSS');
    await click(el, 'shortage-settle');
    http.expectOne(`${C}/shortages/short-1/settle`).flush(shortage({ status: 'WAITING_WRITE_OFF' }));
    await settle();
    expect(text(el, 'shortage-result')).toBe('The write-off is waiting for approval. It shows in the Approvals inbox.');
  });

  it('says the units were written off and nothing was added back', async () => {
    const el = await open();
    await click(el, 'settle-TRANSIT_LOSS');
    await click(el, 'shortage-settle');
    http.expectOne(`${C}/shortages/short-1/settle`).flush(shortage({ status: 'WRITTEN_OFF' }));
    await settle();
    expect(text(el, 'shortage-result')).toBe('Shortage settled. 3 units written off as transit loss. No stock added back.');
  });

  it('keeps the dialog and shows the server sentence on a conflict', async () => {
    const el = await open();
    await click(el, 'settle-CARRIER_CLAIM');
    await click(el, 'shortage-settle');
    http.expectOne(`${C}/shortages/short-1/settle`).flush(
      { type: 'https://sahaj.in/problems/changed', title: 'Changed', status: 409, detail: 'Someone else settled this shortage.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(text(el, 'shortage-error')).toContain('Someone else settled this shortage.');
    expect(close).not.toHaveBeenCalled();
  });

  it('needs a pharmacist and a second signature to settle a Schedule X shortage as found', async () => {
    const el = await open({ product: SCHEDULE_X_PRODUCT });
    await click(el, 'settle-FOUND');
    expect(q(el, 'shortage-x-blocked')).toBeTruthy();
    await click(el, 'shortage-settle');
    expect(text(el, 'shortage-error')).toContain('settled as found by a pharmacist');
    http.expectNone(`${C}/shortages/short-1/settle`);
  });

  it('sends the signer and the PIN for a pharmacist and clears the PIN', async () => {
    const el = await open({ product: SCHEDULE_X_PRODUCT }, true);
    await click(el, 'settle-FOUND');
    (fixture.componentInstance as unknown as { signerId: { set(v: string): void } }).signerId.set('m-2');
    (fixture.componentInstance as unknown as { pin: { set(v: string): void } }).pin.set('1234');
    await click(el, 'shortage-settle');
    const req = http.expectOne(`${C}/shortages/short-1/settle`);
    expect(req.request.body.secondSigner).toEqual({ membershipId: 'm-2', pin: '1234' });
    req.flush(shortage({ status: 'FOUND' }));
    await settle();
    expect((fixture.componentInstance as unknown as { pin(): string }).pin()).toBe('');
  });
});
