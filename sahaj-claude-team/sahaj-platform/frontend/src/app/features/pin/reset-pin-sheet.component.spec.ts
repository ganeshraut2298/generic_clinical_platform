import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../core/http/problem-detail.interceptor';
import { ResetPinSheetComponent } from './reset-pin-sheet.component';

const RESET = '/api/v1/platform/team/m1/pin/reset';
const MEMBER = {
  id: 'm1',
  name: 'Rahul Deshmukh',
  mobileMasked: '••••••3301',
  role: 'PHARMACIST',
  status: 'ACTIVE',
  pin: { state: 'NOT_SET', setAt: null, lockedUntil: null },
};

describe('ResetPinSheetComponent', () => {
  let fixture: ComponentFixture<ResetPinSheetComponent>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;
  let ref: { close: ReturnType<typeof vi.fn>; disableClose?: boolean };

  beforeEach(async () => {
    close = vi.fn();
    ref = { close };
    await TestBed.configureTestingModule({
      imports: [ResetPinSheetComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { id: 'm1', name: 'Rahul Deshmukh' } },
        { provide: MatDialogRef, useValue: ref },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ResetPinSheetComponent);
    fixture.detectChanges();
  });
  afterEach(() => http.verify());

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const confirmButton = () => el().querySelector<HTMLButtonElement>('[data-act=rp-confirm]')!;
  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  it('is titled "Reset PIN · <name>" and sends nothing until the button is pressed', () => {
    expect(el().querySelector('h2#rp-title')!.textContent).toBe('Reset PIN · Rahul Deshmukh');
    expect(squash(el().querySelector('.sheet-b p'))).toContain('You will not see or choose their PIN.');
    expect(Array.from(el().querySelectorAll('.sheet-f button')).map((b) => squash(b))).toEqual(['Cancel', 'Reset PIN']);
    http.expectNone(RESET);
  });

  it('resets once and closes with the updated member', async () => {
    confirmButton().click();
    await settle();
    expect(confirmButton().disabled).toBe(true);
    expect(confirmButton().getAttribute('aria-busy')).toBe('true');
    expect(el().querySelector<HTMLButtonElement>('[data-act=rp-cancel]')!.disabled).toBe(true);
    expect(el().querySelector<HTMLButtonElement>('[data-act=rp-x]')!.disabled).toBe(true);
    expect(ref.disableClose).toBe(true);
    confirmButton().click();
    const req = http.expectOne(RESET);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush(MEMBER);
    await settle();
    expect(close).toHaveBeenCalledWith(MEMBER);
  });

  it('keeps the sheet open with the server text on a failure, and the button works again', async () => {
    confirmButton().click();
    http.expectOne(RESET).flush(
      { type: 'https://sahaj.in/problems/rate-limit', title: 'rate-limit', status: 429, detail: 'Too many requests. Try again later.' },
      { status: 429, statusText: 'Too Many' },
    );
    await settle();
    expect(squash(el().querySelector('[data-testid=rp-failure]'))).toBe('Too many requests. Try again later.');
    expect(close).not.toHaveBeenCalled();
    expect(confirmButton().disabled).toBe(false);
  });

  it('shows a generic text when the connection is lost', async () => {
    confirmButton().click();
    http.expectOne(RESET).error(new ProgressEvent('error'));
    await settle();
    expect(squash(el().querySelector('[data-testid=rp-failure]'))).toBe('We could not reset that PIN. Try again.');
  });

  it('Cancel and the close button close without a member', () => {
    el().querySelector<HTMLButtonElement>('[data-act=rp-cancel]')!.click();
    el().querySelector<HTMLButtonElement>('[data-act=rp-x]')!.click();
    expect(close).toHaveBeenCalledTimes(2);
    expect(close).toHaveBeenCalledWith();
  });
});
