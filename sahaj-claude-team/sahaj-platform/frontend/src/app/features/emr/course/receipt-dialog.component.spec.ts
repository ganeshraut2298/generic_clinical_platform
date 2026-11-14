import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { CourseReceipt } from './course.model';
import { ReceiptDialogComponent } from './receipt-dialog.component';

const receipt: CourseReceipt = {
  receiptNo: 'RC-2201',
  clinicName: 'Test Clinic',
  clinicAddress: '12 Test Road',
  clinicPhone: '020 5555 0100',
  date: '2026-10-28T05:34:00Z',
  patientName: 'Test Patient',
  uhid: 'U-000042',
  courseNo: 'TC-0412',
  courseName: 'Dental treatment course',
  type: 'ADVANCE',
  mode: 'UPI',
  against: 'Course advance',
  amountPaise: 500000,
  collectedByName: 'Front Desk',
  balanceAfterPaise: 125000,
};

describe('ReceiptDialogComponent (SAH-58-5)', () => {
  let fixture: ComponentFixture<ReceiptDialogComponent>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let close: ReturnType<typeof vi.fn>;

  function boot(): void {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { courseId: 'c1', receiptNo: 2201 } },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ReceiptDialogComponent);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  const q = <T extends Element = HTMLElement>(sel: string) => el.querySelector<T>(sel);

  afterEach(() => {
    vi.restoreAllMocks();
    http.verify();
  });

  it('reads the receipt by its number and shows every line of the printable receipt', () => {
    boot();
    http.expectOne('/api/v1/billing/courses/c1/receipts/2201').flush(receipt);
    fixture.detectChanges();
    const text = q('.sheet')!.textContent!.replace(/\s+/g, ' ');
    expect(q('[data-testid=receipt-no]')!.textContent).toBe('RC-2201');
    expect(text).toContain('Test Clinic');
    expect(text).toContain('12 Test Road · 020 5555 0100');
    expect(text).toContain('28 Oct 2026, 11:04 AM');
    expect(text).toContain('Test Patient · UHID U-000042');
    expect(text).toContain('TC-0412 · Dental treatment course');
    expect(text).toContain('Advance');
    expect(text).toContain('Course advance');
    expect(text).toContain('₹5,000');
    expect(text).toContain('Balance after this₹1,250');
    expect(text).toContain('Front Desk');
  });

  it('prints in the browser', () => {
    const print = vi.spyOn(window, 'print').mockImplementation(() => undefined);
    boot();
    http.expectOne('/api/v1/billing/courses/c1/receipts/2201').flush(receipt);
    fixture.detectChanges();
    q<HTMLButtonElement>('[data-act=print]')!.click();
    expect(print).toHaveBeenCalledTimes(1);
  });

  it('shows "Send on WhatsApp" switched off with the note', () => {
    boot();
    http.expectOne('/api/v1/billing/courses/c1/receipts/2201').flush(receipt);
    fixture.detectChanges();
    expect(q<HTMLButtonElement>('[data-act=send-whatsapp]')!.disabled).toBe(true);
    expect(q('#rd-wa')!.textContent).toBe('Needs the WhatsApp service');
  });

  it('shows the server message when the receipt cannot be opened, and cannot print', () => {
    boot();
    http.expectOne('/api/v1/billing/courses/c1/receipts/2201').flush({}, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(q('.err-text')!.textContent).toBe('We could not open the receipt. Try again.');
    expect(q<HTMLButtonElement>('[data-act=print]')!.disabled).toBe(true);
  });

  it('closes', () => {
    boot();
    http.expectOne('/api/v1/billing/courses/c1/receipts/2201').flush(receipt);
    fixture.detectChanges();
    Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent!.trim() === 'Close')!.click();
    expect(close).toHaveBeenCalled();
  });
});
