import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { approvalRules } from '../pharmacy-c.fixtures';
import { TRANSACTION_TYPES } from './approval-rules.util';
import { PRESET_DIALOG_CONFIG, PresetDialog, PresetDialogData } from './preset.dialog';

const BASE = '/api/v1/pharmacy';

interface Internals {
  form: PresetDialog['form'];
  apply(): void;
}

describe('PresetDialog', () => {
  let fixture: ComponentFixture<PresetDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(data: PresetDialogData): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PresetDialog);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const me = () => fixture.componentInstance as unknown as Internals;
  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);

  afterEach(() => http.verify());

  it('says what the preset replaces and names the sheet title', async () => {
    const el = await open({ preset: 'LEAN', baseVersionNo: 2, limitTypes: ['PURCHASE_ORDER'] });
    expect(PRESET_DIALOG_CONFIG.ariaLabelledBy).toBe('preset-title');
    expect(q(el, 'preset-confirm')?.textContent).toBe('This replaces all rows below. You can change them afterwards.');
    expect(el.querySelector('#preset-title')?.textContent).toContain('Lean');
  });

  it('asks for the purchase order limit under Lean, and for nothing it does not need', async () => {
    const el = await open({ preset: 'LEAN', baseVersionNo: 2, limitTypes: ['PURCHASE_ORDER'] });
    expect(q(el, 'preset-limit-PURCHASE_ORDER')).not.toBeNull();
    expect(q(el, 'preset-limit-SALE_DISCOUNT')).toBeNull();
  });

  it('asks for a percentage for the discount at sale and for the rate rise under Standard', async () => {
    const el = await open({ preset: 'STANDARD', baseVersionNo: 2, limitTypes: ['PURCHASE_ORDER', 'GRN_RATE_RISE', 'STOCK_ADJUSTMENT', 'SALE_DISCOUNT', 'SALES_RETURN'] });
    for (const type of ['PURCHASE_ORDER', 'GRN_RATE_RISE', 'STOCK_ADJUSTMENT', 'SALE_DISCOUNT', 'SALES_RETURN']) {
      expect(q(el, `preset-limit-${type}`)).not.toBeNull();
    }
    expect(q(el, 'preset-limit-INDENT')).toBeNull();
    expect(q(el, 'preset-limit-SALE_DISCOUNT')?.closest('mat-form-field')?.textContent).toContain('limit %');
    expect(q(el, 'preset-limit-PURCHASE_ORDER')?.closest('mat-form-field')?.textContent).toContain('limit ₹');
  });

  it('asks for every type under Strict', async () => {
    const el = await open({ preset: 'STRICT', baseVersionNo: 2, limitTypes: [...TRANSACTION_TYPES] });
    expect(el.querySelectorAll('[data-testid^="preset-limit-"]')).toHaveLength(9);
  });

  it('asks for exactly the types the server lists, not a list of its own (DEC-078 item 3)', async () => {
    const el = await open({ preset: 'STANDARD', baseVersionNo: 2, limitTypes: ['VENDOR_RETURN', 'SALE_DISCOUNT'] });
    expect(Array.from(el.querySelectorAll('[data-testid^="preset-limit-"]')).map((e) => e.getAttribute('data-testid'))).toEqual([
      'preset-limit-VENDOR_RETURN',
      'preset-limit-SALE_DISCOUNT',
    ]);
    me().form.controls['VENDOR_RETURN'].patchValue({ amount: '1000' });
    me().form.controls['SALE_DISCOUNT'].patchValue({ percent: '10' });
    me().apply();
    const request = http.expectOne(`${BASE}/approval-rules/preset`);
    expect(request.request.body.limits.map((l: { transactionType: string }) => l.transactionType)).toEqual([
      'VENDOR_RETURN',
      'SALE_DISCOUNT',
    ]);
    request.flush(approvalRules({ preset: 'STANDARD', versionNo: 3 }));
  });

  it('asks for a percentage, never an amount, for the rate rise', async () => {
    const el = await open({ preset: 'STANDARD', baseVersionNo: 2, limitTypes: ['GRN_RATE_RISE'] });
    expect(q(el, 'preset-limit-GRN_RATE_RISE')?.closest('mat-form-field')?.textContent).toContain('limit %');
  });

  it('shows a limits.<TYPE> 400 of the server on that type', async () => {
    const el = await open({ preset: 'LEAN', baseVersionNo: 2, limitTypes: ['PURCHASE_ORDER'] });
    me().form.controls['PURCHASE_ORDER'].patchValue({ amount: '50000' });
    me().apply();
    http.expectOne(`${BASE}/approval-rules/preset`).flush(
      {
        type: 'https://sahaj.in/problems/validation',
        title: 'Validation',
        status: 400,
        detail: 'Check the fields.',
        fieldErrors: [{ field: 'limits.PURCHASE_ORDER', message: 'Enter the limit' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();
    expect(el.textContent).toContain('Enter the limit');
    expect(close).not.toHaveBeenCalled();
  });

  it('does not apply until each limit is entered or "No amount condition" is chosen', async () => {
    const el = await open({ preset: 'LEAN', baseVersionNo: 2, limitTypes: ['PURCHASE_ORDER'] });
    me().apply();
    fixture.detectChanges();
    expect(el.textContent).toContain('Enter the limit');
    http.expectNone(`${BASE}/approval-rules/preset`);
  });

  it('sends the limit in paise with the version read', async () => {
    await open({ preset: 'LEAN', baseVersionNo: 2, limitTypes: ['PURCHASE_ORDER'] });
    me().form.controls['PURCHASE_ORDER'].patchValue({ amount: '50000' });
    me().apply();
    const request = http.expectOne(`${BASE}/approval-rules/preset`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      baseVersionNo: 2,
      preset: 'LEAN',
      limits: [{ transactionType: 'PURCHASE_ORDER', amountPaise: 5000000, percent: null, noAmountCondition: false }],
    });
    const applied = approvalRules({ preset: 'LEAN', versionNo: 3 });
    request.flush(applied);
    expect(close).toHaveBeenCalledWith(applied);
  });

  it('sends "No amount condition" instead of a limit', async () => {
    await open({ preset: 'LEAN', baseVersionNo: 0, limitTypes: ['PURCHASE_ORDER'] });
    me().form.controls['PURCHASE_ORDER'].patchValue({ none: true });
    me().apply();
    const request = http.expectOne(`${BASE}/approval-rules/preset`);
    expect(request.request.body.limits[0]).toEqual({
      transactionType: 'PURCHASE_ORDER',
      amountPaise: null,
      percent: null,
      noAmountCondition: true,
    });
    request.flush(approvalRules({ preset: 'LEAN', versionNo: 1 }));
  });

  it('keeps the sheet open and shows the sentence when the save fails', async () => {
    const el = await open({ preset: 'LEAN', baseVersionNo: 2, limitTypes: ['PURCHASE_ORDER'] });
    me().form.controls['PURCHASE_ORDER'].patchValue({ amount: '50000' });
    me().apply();
    http.expectOne(`${BASE}/approval-rules/preset`).error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(q(el, 'preset-error')?.textContent).toContain('Could not save. Check your connection and try again.');
    expect(close).not.toHaveBeenCalled();
  });

  it('is a column capped by the screen whose body scrolls, so Save and Cancel stay in reach (QA-78-5)', async () => {
    const el = await open({ preset: 'LEAN', baseVersionNo: 2, limitTypes: ['PURCHASE_ORDER'] });
    const host = getComputedStyle(fixture.nativeElement as HTMLElement);
    expect(host.display).toBe('flex');
    expect(host.flexDirection).toBe('column');
    expect(host.maxHeight).toMatch(/^92d?vh$/);
    const body = getComputedStyle(el.querySelector('.sheet-b') as HTMLElement);
    expect(body.overflowY).toBe('auto');
    expect(body.minHeight).toBe('0px');
    expect(getComputedStyle(el.querySelector('.sheet-h') as HTMLElement).flexShrink).toBe('0');
    expect(getComputedStyle(el.querySelector('.sheet-f') as HTMLElement).flexShrink).toBe('0');
  });
});
