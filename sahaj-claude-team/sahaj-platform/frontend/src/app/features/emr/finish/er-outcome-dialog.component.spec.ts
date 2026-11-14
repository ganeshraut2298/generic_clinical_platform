import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { ErOutcomeDialogComponent } from './er-outcome-dialog.component';

describe('ErOutcomeDialogComponent (SAH-67-2)', () => {
  let fixture: ComponentFixture<ErOutcomeDialogComponent>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;
  let el: HTMLElement;

  function boot(current: unknown = null): void {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: { close } },
        { provide: MAT_DIALOG_DATA, useValue: { current, admissionAdvised: false } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ErOutcomeDialogComponent);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  }

  const radio = (kind: string) => el.querySelector<HTMLInputElement>(`[data-kind=${kind}]`)!;
  const choose = (kind: string) => {
    radio(kind).click();
    fixture.detectChanges();
  };
  const field = (name: string) => el.querySelector<HTMLInputElement>(`[data-field=${name}]`)!;
  const type = (name: string, value: string) => {
    const f = field(name);
    f.value = value;
    f.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };
  const next = () => el.querySelector<HTMLButtonElement>('[data-act=outcome-continue]')!;

  afterEach(() => http.verify());

  it('offers the four choices, none chosen at start, and Continue is off', () => {
    boot();
    expect(Array.from(el.querySelectorAll('.opt')).map((o) => o.textContent!.trim())).toEqual(['Discharge from ER', 'Admit to IPD', 'Refer out', 'Send to OPD']);
    expect(el.querySelectorAll<HTMLInputElement>('input[type=radio]:checked')).toHaveLength(0);
    expect(next().disabled).toBe(true);
  });

  it('Discharge from ER needs nothing more', () => {
    boot();
    choose('DISCHARGE');
    expect(next().disabled).toBe(false);
    next().click();
    expect(close).toHaveBeenCalledWith({ kind: 'DISCHARGE' });
  });

  it('Refer out needs the place; the note is optional', () => {
    boot();
    choose('REFER_OUT');
    expect(next().disabled).toBe(true);
    type('refer-to', 'District hospital');
    type('refer-note', 'For CT head');
    next().click();
    expect(close).toHaveBeenCalledWith({ kind: 'REFER_OUT', referTo: 'District hospital', referNote: 'For CT head' });
  });

  it('Admit to IPD preselects Emergency and sends the request facts', () => {
    boot();
    choose('ADMIT');
    expect((field('admit-type') as unknown as HTMLSelectElement).value).toBe('emergency');
    type('admit-note', 'Chest pain, ECG done');
    next().click();
    expect(close).toHaveBeenCalledWith({ kind: 'ADMIT', admission: { admissionType: 'emergency', note: 'Chest pain, ECG done' } });
  });

  it('Admit to IPD: a planned stay must be 1 to 60 days', () => {
    boot();
    choose('ADMIT');
    const select = field('admit-type') as unknown as HTMLSelectElement;
    select.value = 'planned';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    type('admit-stay', '90');
    expect(next().disabled).toBe(true);
    type('admit-stay', '3');
    expect(next().disabled).toBe(false);
    next().click();
    expect(close).toHaveBeenCalledWith({ kind: 'ADMIT', admission: { admissionType: 'planned', plannedStayDays: 3 } });
  });

  it('Send to OPD needs an existing patient from the search', async () => {
    vi.useFakeTimers();
    try {
      boot();
      choose('SEND_TO_OPD');
      expect(next().disabled).toBe(true);
      type('opd-search', 'Mehta');
      await vi.advanceTimersByTimeAsync(350);
      http.expectOne((r) => r.url.endsWith('/patients') && r.params.get('q') === 'Mehta').flush({
        items: [{ id: 'p1', uhid: 'U-1', name: 'Asha Mehta', age: 40, sex: 'F', mobile: '9876543210' }],
        total: 1,
        page: 0,
        pageSize: 20,
      });
      fixture.detectChanges();
      el.querySelector<HTMLButtonElement>('[data-act=pick-patient]')!.click();
      fixture.detectChanges();
      expect(el.querySelector('[data-testid=opd-patient]')!.textContent).toContain('Asha Mehta');
      expect(next().disabled).toBe(false);
      next().click();
      expect(close).toHaveBeenCalledWith({ kind: 'SEND_TO_OPD', opdPatientId: 'p1' });
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps the choice made before when it is opened again', () => {
    boot({ kind: 'REFER_OUT', referTo: 'City hospital', referNote: '' });
    expect(radio('REFER_OUT').checked).toBe(true);
    expect(field('refer-to').value).toBe('City hospital');
    expect(next().disabled).toBe(false);
  });

  it('Cancel gives back nothing', () => {
    boot();
    el.querySelector<HTMLButtonElement>('[data-act=outcome-cancel]')!.click();
    expect(close).toHaveBeenCalledWith(null);
  });
});
