import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ShellStore } from '../../../core/shell/shell.store';
import { DeskTokenPanelComponent } from './desk-token-panel.component';
import { DeskTokenPanel } from './desk.model';
import { makeTokenPanel } from './desk.testing';

const URL = '/api/v1/opd/desk/visits/v1/token-panel';

describe('DeskTokenPanelComponent', () => {
  let fixture: ComponentFixture<DeskTokenPanelComponent>;
  let http: HttpTestingController;
  let view: HTMLElement;

  function open(initial: DeskTokenPanel | null, issued = false) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ role: 'NURSE', clinic: { name: 'Shree Sai Clinic' } }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DeskTokenPanelComponent);
    fixture.componentRef.setInput('visitId', 'v1');
    fixture.componentRef.setInput('initial', initial);
    fixture.componentRef.setInput('issued', issued);
    fixture.detectChanges();
    view = fixture.nativeElement as HTMLElement;
  }

  const q = (id: string) => view.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

  afterEach(() => http.verify());

  it('shows Token issued with the token, patient, doctor, room and the wait line from the check-in answer', () => {
    open(makeTokenPanel(), true);
    expect(view.querySelector('h2')!.textContent).toBe('Token issued');
    expect(q('token-number')!.textContent).toBe('T-8');
    expect(q('token-patient')!.textContent).toBe('Rekha Shinde');
    expect(q('token-doctor')!.textContent!.replace(/\s+/g, ' ').trim()).toBe('Dr. Meera K. · Room 2');
    expect(q('token-wait')!.textContent).toBe('3 ahead · about 45 min');
  });

  it('says Next in line when nobody is ahead and leaves the room out when there is none', () => {
    open(makeTokenPanel({ patientsAhead: 0, estimatedWaitMinutes: 0, room: null }), true);
    expect(q('token-wait')!.textContent).toBe('Next in line');
    expect(q('token-doctor')!.textContent!.trim()).toBe('Dr. Meera K.');
  });

  it('reads the current numbers when opened from a card, for any role', () => {
    open(null);
    http.expectOne(URL).flush(makeTokenPanel({ patientsAhead: 1, estimatedWaitMinutes: 15 }));
    fixture.detectChanges();
    expect(view.querySelector('h2')!.textContent).toBe('Token');
    expect(q('token-wait')!.textContent).toBe('1 ahead · about 15 min');
    expect(q('print-slip')).not.toBeNull();
  });

  it('shows a plain error with Try again when the read fails', () => {
    open(null);
    http.expectOne(URL).flush({}, { status: 500, statusText: 'Server error' });
    fixture.detectChanges();
    expect(view.textContent).toContain('We could not load the token. Try again.');
    (Array.from(view.querySelectorAll('button')).find((b) => b.textContent!.includes('Try again')) as HTMLButtonElement).click();
    http.expectOne(URL).flush(makeTokenPanel());
    fixture.detectChanges();
    expect(q('token-number')!.textContent).toBe('T-8');
  });

  it('opens the browser print dialog from Print slip', () => {
    open(makeTokenPanel());
    const print = vi.spyOn(window, 'print').mockImplementation(() => undefined);
    q('print-slip')!.click();
    expect(print).toHaveBeenCalledTimes(1);
  });

  it('builds a slip with clinic, token, patient, doctor, date and time and nothing clinical', () => {
    open(makeTokenPanel());
    const slip = q('slip')!.textContent!;
    expect(slip).toContain('Shree Sai Clinic');
    expect(slip).toContain('T-8');
    expect(slip).toContain('Rekha Shinde');
    expect(slip).toContain('Dr. Meera K.');
    expect(slip).toContain('25 Nov 2026, 11:05 AM IST');
    expect(slip).not.toMatch(/diagnosis|ABHA|note/i);
  });

  it('shows Send on WhatsApp disabled with the not-in-this-phase text', () => {
    open(makeTokenPanel());
    const wa = Array.from(view.querySelectorAll('button')).find((b) => b.textContent!.includes('Send on WhatsApp')) as HTMLButtonElement;
    expect(wa.disabled).toBe(true);
    expect(q('token-whatsapp-note')!.textContent).toBe('Needs WhatsApp (not in this phase)');
  });

  it('closes on Close', () => {
    open(makeTokenPanel());
    const closed = vi.fn();
    fixture.componentInstance.closePanel.subscribe(closed);
    q('token-close')!.click();
    expect(closed).toHaveBeenCalled();
  });
});
