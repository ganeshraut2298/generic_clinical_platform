import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { DayClosingDialogComponent } from './day-closing-dialog.component';

const BASE = '/api/v1/billing';

describe('DayClosingDialogComponent', () => {
  let fixture: ComponentFixture<DayClosingDialogComponent>;
  let http: HttpTestingController;
  let closeSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    closeSpy = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: { close: closeSpy } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DayClosingDialogComponent);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function submitButton(): HTMLButtonElement {
    return Array.from(el().querySelectorAll('button')).find((b) =>
      b.textContent!.includes('Close the day'),
    )!;
  }

  it('shows an error instead of calling the server when the counted amount is missing', () => {
    submitButton().click();
    fixture.detectChanges();
    expect(el().textContent).toContain('Enter the cash counted');
  });

  it('submits counted cash and shows the server-computed expected cash and difference', () => {
    const input = el().querySelector<HTMLInputElement>('input')!;
    input.value = '1500';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    submitButton().click();
    const req = http.expectOne(`${BASE}/day-closing`);
    expect(req.request.body).toEqual({ countedCashPaise: 150000, note: null });
    req.flush({
      businessDate: '2026-11-01',
      countedCashPaise: 150000,
      expectedCashPaise: 160000,
      diffPaise: -10000,
      note: null,
      closedByName: 'Dr. Meera',
      closedAt: new Date().toISOString(),
    });
    fixture.detectChanges();

    expect(el().textContent).toContain('Day closed');
    expect(el().textContent).toContain('Short by');
  });
});
