import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { REJECT_REASONS, problem } from '../emr/emr.fixtures';
import { ROW, verifiedResult } from './pharmacist.fixtures';
import { RejectSheetComponent, RejectSheetData } from './reject-sheet.component';

const REJECT = '/api/v1/ipd/medicine-orders/o1/reject';

describe('RejectSheetComponent', () => {
  let fixture: ComponentFixture<RejectSheetComponent>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;
  let ref: { close: ReturnType<typeof vi.fn>; disableClose?: boolean };
  let onConflict: ReturnType<typeof vi.fn<() => void>>;

  beforeEach(async () => {
    close = vi.fn();
    ref = { close };
    onConflict = vi.fn<() => void>();
    const data: RejectSheetData = { row: ROW, reasons: REJECT_REASONS, onConflict };
    await TestBed.configureTestingModule({
      imports: [RejectSheetComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: ref },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(RejectSheetComponent);
    fixture.detectChanges();
  });
  afterEach(() => http.verify());

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const select = () => q<HTMLSelectElement>('#sf-rj')!;
  const rejectButton = () => q<HTMLButtonElement>('[data-act=sfrejok]')!;
  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  async function press(): Promise<TestRequest> {
    rejectButton().click();
    await settle();
    return http.expectOne(REJECT);
  }

  it('has the title, the subtitle, the select with the first reason chosen, and the footer', () => {
    expect(q('h2#sf-rj-title')!.textContent).toBe('Reject · Tab. Paracetamol 650 mg');
    expect(squash(q('[data-testid=reject-subtitle]'))).toBe('Asha Patil · ordered by Dr. Meera Kulkarni');
    expect(squash(q('label[for=sf-rj]'))).toBe('Reason, sent to the doctor');
    expect(Array.from(select().options).map((o) => o.textContent)).toEqual([
      'Allergy conflict',
      'Interaction risk too high',
      'Wrong dose or frequency',
      'Duplicate therapy',
    ]);
    expect(select().value).toBe('ALLERGY_CONFLICT');
    expect(Array.from(el().querySelectorAll('.sheet-f button')).map((b) => squash(b))).toEqual(['Cancel', 'Reject order']);
    expect(rejectButton().classList).toContain('danger');
  });

  it('sends the chosen reason key once and closes with the answer', async () => {
    select().value = 'INTERACTION_RISK';
    select().dispatchEvent(new Event('change'));
    const req = await press();
    expect(req.request.body).toEqual({ reasonKey: 'INTERACTION_RISK' });
    expect(rejectButton().disabled).toBe(true);
    expect(rejectButton().getAttribute('aria-busy')).toBe('true');
    expect(q<HTMLButtonElement>('[data-act=rj-cancel]')!.disabled).toBe(true);
    expect(q<HTMLButtonElement>('[data-act=rj-x]')!.disabled).toBe(true);
    expect(ref.disableClose).toBe(true);
    rejectButton().click();
    http.expectNone(REJECT);
    const answer = verifiedResult({ outcome: 'REJECTED', rejectReason: { key: 'INTERACTION_RISK', label: 'Interaction risk too high' } });
    req.flush(answer);
    await settle();
    expect(close).toHaveBeenCalledWith(answer);
  });

  it('sends the first reason when nothing was changed', async () => {
    const req = await press();
    expect(req.request.body).toEqual({ reasonKey: 'ALLERGY_CONFLICT' });
    req.flush(verifiedResult({ outcome: 'REJECTED' }));
  });

  it('keeps the sheet open with the chosen reason on a failure, and the button works again', async () => {
    select().value = 'WRONG_DOSE';
    select().dispatchEvent(new Event('change'));
    (await press()).flush(problem('server-error', 500, 'Try later.'), { status: 500, statusText: 'x' });
    await settle();
    expect(squash(q('[data-testid=reject-failure]'))).toBe('Try later.');
    expect(select().value).toBe('WRONG_DOSE');
    expect(rejectButton().disabled).toBe(false);
    expect(close).not.toHaveBeenCalled();
    expect(onConflict).not.toHaveBeenCalled();
  });

  it('shows a generic text when the connection is lost', async () => {
    (await press()).error(new ProgressEvent('error'));
    await settle();
    expect(squash(q('[data-testid=reject-failure]'))).toBe('We could not reject that order. Try again.');
  });

  it('on a 409 shows the text, reloads the panel behind and switches Reject order off', async () => {
    (await press()).flush(problem('order-state', 409, 'Rahul Deshmukh already verified this order.'), {
      status: 409,
      statusText: 'Conflict',
    });
    await settle();
    expect(squash(q('[data-testid=reject-failure]'))).toBe('Rahul Deshmukh already verified this order.');
    expect(onConflict).toHaveBeenCalledTimes(1);
    expect(rejectButton().disabled).toBe(true);
  });

  it('on a 404 does the same', async () => {
    (await press()).flush(problem('not-found', 404, 'Order not found.'), { status: 404, statusText: 'x' });
    await settle();
    expect(onConflict).toHaveBeenCalledTimes(1);
    expect(rejectButton().disabled).toBe(true);
  });

  it('on a 403 shows the text and switches Reject order off without reloading', async () => {
    (await press()).flush(problem('forbidden', 403, 'Only the pharmacist or the clinic owner can check orders.'), {
      status: 403,
      statusText: 'x',
    });
    await settle();
    expect(squash(q('[data-testid=reject-failure]'))).toBe('Only the pharmacist or the clinic owner can check orders.');
    expect(rejectButton().disabled).toBe(true);
    expect(onConflict).not.toHaveBeenCalled();
  });

  it('shows a 400 on reasonKey under the select', async () => {
    (await press()).flush(
      problem('validation', 400, 'x', [{ field: 'reasonKey', message: 'Choose a reason' }]),
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    expect(squash(q('#sf-rj-err'))).toBe('Choose a reason');
    expect(select().getAttribute('aria-invalid')).toBe('true');
    select().dispatchEvent(new Event('change'));
    await settle();
    expect(q('#sf-rj-err')).toBeNull();
  });

  it('Cancel and the close button close without an answer', () => {
    q<HTMLButtonElement>('[data-act=rj-cancel]')!.click();
    q<HTMLButtonElement>('[data-act=rj-x]')!.click();
    expect(close).toHaveBeenCalledTimes(2);
    expect(close).toHaveBeenCalledWith();
  });
});
