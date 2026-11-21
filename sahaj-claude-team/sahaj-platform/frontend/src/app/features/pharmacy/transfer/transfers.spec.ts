import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { SCHEDULE_X_REF } from '../inventory/inventory.fixtures';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { Transfer } from '../pharmacy-stockuse.model';
import { PharmacyContext } from '../pharmacy.model';
import { storeContext, transfer, transferRow } from '../stockuse/stockuse.fixtures';
import { ReceiptDialog } from '../indent/receipt.dialog';
import { TRANSFER_DETAIL_CONFIG, TransferDetailData, TransferDetailDialog } from './transfer-detail.dialog';
import { TRANSFER_FORM_CONFIG, TransferFormDialog } from './transfer-form.dialog';
import { TransfersSection } from './transfers.section';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-ward`;

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

describe('TransfersSection', () => {
  let fixture: ComponentFixture<TransfersSection>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext = storeContext(), items = [transferRow()]): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(TransfersSection);
    fixture.detectChanges();
    const req = http.expectOne((r) => r.url === `${S}/transfers`);
    expect(req.request.params.get('open')).toBe('true');
    req.flush({ items, total: items.length, page: 0, size: 20 });
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('lists the open transfers with the status and the age, and "Not received · n h" for a late one', async () => {
    const el = await open(storeContext(), [
      transferRow(),
      transferRow({ id: 'trf-2', number: 'TRF-0431', notReceivedHours: 26, ageMinutes: 1560 }),
    ]);
    expect(text(el, 'transfer-row-TRF-0440')).toContain('Test Central Store → Test Ward 3B store');
    expect(text(el, 'transfer-row-TRF-0440')).toContain('In transit');
    expect(text(el, 'transfer-row-TRF-0431')).toContain('Not received · 26 h');
  });

  it('shows "+ New transfer" only with the raise capability', async () => {
    const el = await open(storeContext({ raiseIndent: false }), []);
    expect(q(el, 'new-transfer')).toBeNull();
    expect(q(el, 'transfers-empty')).toBeTruthy();
  });

  it('opens a transfer on its detail with the working store, and reloads when something changed', async () => {
    const el = await open();
    const dialog = fixture.debugElement.injector.get(MatDialog);
    const spy = vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of(true) } as never);
    q(el, 'open-transfer-TRF-0440')?.click();
    expect(spy).toHaveBeenCalledWith(
      TransferDetailDialog,
      expect.objectContaining({ data: expect.objectContaining({ storeId: 'store-ward', transferId: 'trf-1', canReceive: true, canDispatch: false }) }),
    );
    http.expectOne((r) => r.url === `${S}/transfers`).flush({ items: [], total: 0, page: 0, size: 20 });
  });

  it('opens the form with the working store and the unit transfer stores, and shows the note after a request', async () => {
    const el = await open();
    const dialog = fixture.debugElement.injector.get(MatDialog);
    const spy = vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of(transfer({ status: 'WAITING_APPROVAL' })) } as never);
    q(el, 'new-transfer')?.click();
    http.expectOne(`${S}/transfer-stores`).flush({
      items: [{ storeId: 'store-central', name: 'Test Central Store', type: 'CENTRAL' }, { storeId: 'store-ot', name: 'Test OT Store', type: 'SUB_STORE' }],
    });
    expect(spy).toHaveBeenCalledWith(
      TransferFormDialog,
      expect.objectContaining({
        ...TRANSFER_FORM_CONFIG,
        data: expect.objectContaining({
          stores: [
            expect.objectContaining({ id: 'store-ward' }),
            { id: 'store-central', name: 'Test Central Store', central: true },
            { id: 'store-ot', name: 'Test OT Store', central: false },
          ],
        }),
      }),
    );
    http.expectOne((r) => r.url === `${S}/transfers`).flush({ items: [], total: 0, page: 0, size: 20 });
    await settle();
    expect(text(el, 'transfer-note')).toBe('TRF-0440 is waiting for approval. Nothing moves until it is dispatched.');
  });
});

describe('TransferDetailDialog', () => {
  let fixture: ComponentFixture<TransferDetailDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(t: Transfer, data: Partial<TransferDetailData> = {}): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        {
          provide: MAT_DIALOG_DATA,
          useValue: { storeId: 'store-central', transferId: 'trf-1', canDispatch: true, canReceive: false, isPharmacist: true, ...data },
        },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TransferDetailDialog);
    fixture.detectChanges();
    http.expectOne(`${BASE}/stores/${data.storeId ?? 'store-central'}/transfers/trf-1`).flush(t);
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush({
      lists: [{ listKey: 'approval-reject', reasons: [{ reasonKey: 'approval-reject.other', label: 'Other', needsNote: false, fixed: true }] }],
    });
    http.expectOne(`${BASE}/stores/${data.storeId ?? 'store-central'}/second-signers`).flush({ items: [] });
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('shows the lines, the available figure of the sender, the step bar and the note of the mockup', async () => {
    const el = await open(transfer());
    expect(TRANSFER_DETAIL_CONFIG.ariaLabelledBy).toBe('transfer-title');
    expect(text(el, 'transfer-header')).toContain('TRF-0440 · Test Central Store → Test Ward 3B store');
    expect(text(el, 'transfer-available-0')).toBe('25');
    expect(text(el, 'transfer-total')).toBe('₹ 630.00');
    expect(q(el, 'step-Approved')?.getAttribute('data-done')).toBe('true');
    expect(q(el, 'step-Dispatched')?.getAttribute('data-done')).toBe('false');
    expect(text(el, 'transfer-note')).toContain('Receiving works exactly like an indent');
  });

  it('shows a dash and "Counting in progress" when the sender quantity is hidden (blind)', async () => {
    const t = transfer();
    const el = await open({ ...t, lines: [{ ...t.lines[0], availableAtSender: null }] });
    expect(text(el, 'transfer-available-0')).toBe('—');
    expect(text(el, 'transfer-counting')).toContain('Counting in progress');
  });

  it('dispatches an approved transfer from the sending store, once', async () => {
    const el = await open(transfer());
    q(el, 'transfer-dispatch')?.click();
    await settle();
    const req = http.expectOne(`${BASE}/stores/store-central/transfers/trf-1/dispatch`);
    expect(req.request.body).toMatchObject({ version: 2, secondSigner: null });
    req.flush(transfer({ status: 'IN_TRANSIT', step: 'DISPATCHED', dispatch: { dispatchId: 'd-1', status: 'IN_TRANSIT' } }));
    await settle();
    expect(text(el, 'transfer-done')).toBe('Dispatched. Test Ward 3B store can now receive it.');
    expect(q(el, 'transfer-dispatch')).toBeNull();
  });

  it('does not offer Dispatch to the receiving store or without the capability', async () => {
    const el = await open(transfer(), { canDispatch: false });
    expect(q(el, 'transfer-dispatch')).toBeNull();
  });

  it('asks for the second signer before it dispatches a Schedule X line', async () => {
    const t = transfer();
    const el = await open({ ...t, lines: [{ ...t.lines[0], product: SCHEDULE_X_REF }] });
    q(el, 'transfer-dispatch')?.click();
    await settle();
    expect(el.textContent).toContain('Choose the second signer');
  });

  it('opens Receive items on the dispatch at the receiving store', async () => {
    const dispatched = transfer({ status: 'IN_TRANSIT', step: 'DISPATCHED', dispatch: { dispatchId: 'd-1', status: 'IN_TRANSIT' } });
    const el = await open(dispatched, { storeId: 'store-ward', canDispatch: false, canReceive: true });
    const spy = vi.spyOn(fixture.debugElement.injector.get(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(true) } as never);
    q(el, 'transfer-receive')?.click();
    expect(spy).toHaveBeenCalledWith(
      ReceiptDialog,
      expect.objectContaining({ data: { storeId: 'store-ward', dispatchId: 'd-1', isPharmacist: true } }),
    );
    expect(close).toHaveBeenCalledWith(true);
  });

  it('cancels with a reason before dispatch', async () => {
    const el = await open(transfer());
    q(el, 'transfer-cancel')?.click();
    await settle();
    q(el, 'transfer-cancel-confirm')?.click();
    await settle();
    expect(text(el, 'transfer-error')).toBe('Choose a reason.');
    q(el, 'transfer-cancel-reason-approval-reject.other')?.click();
    await settle();
    q(el, 'transfer-cancel-confirm')?.click();
    const req = http.expectOne(`${BASE}/stores/store-central/transfers/trf-1/cancel`);
    expect(req.request.body).toEqual({ version: 2, reasonKey: 'approval-reject.other' });
    req.flush(transfer({ status: 'CANCELLED' }));
    await settle();
    expect(text(el, 'transfer-done')).toBe('TRF-0440 cancelled.');
  });
});

describe('TransferFormDialog', () => {
  let fixture: ComponentFixture<TransferFormDialog>;
  let http: HttpTestingController;

  async function open(): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            storeId: 'store-ward',
            storeName: 'Test Ward 3B store',
            stores: [
              { id: 'store-ward', name: 'Test Ward 3B store', central: false },
              { id: 'store-central', name: 'Test Central Store', central: true },
              { id: 'store-icu', name: 'Test ICU store', central: false },
            ],
          },
        },
        { provide: MatDialogRef, useValue: { close: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TransferFormDialog);
    fixture.detectChanges();
    http.expectOne((r) => r.url === `${BASE}/reasons`).flush({
      lists: [{ listKey: 'transfer', reasons: [{ reasonKey: 'transfer.urgent-shortage', label: 'Urgent shortage', needsNote: false, fixed: false }] }],
    });
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  it('refuses the same store twice, the Central Store as the destination, and no reason, and sends nothing', async () => {
    const el = await open();
    const c = fixture.componentInstance as unknown as { toId: { set(v: string): void } };
    c.toId.set('store-ward');
    q(el, 'transfer-submit')?.click();
    fixture.detectChanges();
    expect(text(el, 'transfer-store-error')).toBe('Choose two different stores.');
    expect(text(el, 'transfer-reason-error')).toBe('Choose a reason.');
    c.toId.set('store-central');
    q(el, 'transfer-submit')?.click();
    fixture.detectChanges();
    expect(text(el, 'transfer-store-error')).toBe('Use Return to Central Store.');
  });

  it('requests the transfer once with the lines and the reason', async () => {
    const el = await open();
    const c = fixture.componentInstance as unknown as {
      toId: { set(v: string): void };
      reasonKey: { set(v: string): void };
      add(p: unknown): void;
    };
    c.toId.set('store-icu');
    c.reasonKey.set('transfer.urgent-shortage');
    c.add({ productId: 'prod-1', code: 'G-1', name: 'Sample', strength: '1 g', form: 'Injection' });
    fixture.detectChanges();
    const input = q(el, 'transfer-qty-0') as HTMLInputElement;
    input.value = '6';
    input.dispatchEvent(new Event('input'));
    q(el, 'transfer-submit')?.click();
    const req = http.expectOne(`${S}/transfers`);
    expect(req.request.body).toMatchObject({
      fromStoreId: 'store-ward',
      toStoreId: 'store-icu',
      reasonKey: 'transfer.urgent-shortage',
      lines: [{ productId: 'prod-1', quantity: 6 }],
    });
    req.flush(transfer());
  });
});
