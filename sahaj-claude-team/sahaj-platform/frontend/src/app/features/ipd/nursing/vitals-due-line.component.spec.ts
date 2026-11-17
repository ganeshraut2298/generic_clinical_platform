import { ComponentFixture, TestBed } from '@angular/core/testing';
import { VitalsDueLineComponent, vitalsDueView, vitalsIntervalText } from './vitals-due-line.component';

/** 12:00 PM IST on 6 Nov 2026. */
const DUE = '2026-11-06T06:30:00Z';

describe('vitalsDueView (SAH-76-6, F1)', () => {
  it('before the due time: "Next vitals due 12:00 PM"', () => {
    expect(vitalsDueView(DUE, '2026-11-06T05:35:00Z')).toEqual({ text: 'Next vitals due 12:00 PM', tone: 'ok' });
  });

  it('within the first minute after it: "Vitals due now"', () => {
    expect(vitalsDueView(DUE, DUE)).toEqual({ text: 'Vitals due now', tone: 'warn' });
    expect(vitalsDueView(DUE, '2026-11-06T06:30:59Z')?.text).toBe('Vitals due now');
  });

  it('after that: "Vitals overdue by 25 min", whole minutes from the server time', () => {
    expect(vitalsDueView(DUE, '2026-11-06T06:55:30Z')).toEqual({ text: 'Vitals overdue by 25 min', tone: 'crit' });
    expect(vitalsDueView(DUE, '2026-11-06T06:31:00Z')?.text).toBe('Vitals overdue by 1 min');
  });

  it('no vitals yet: due at once', () => {
    expect(vitalsDueView(null, '2026-11-06T05:35:00Z')).toEqual({ text: 'Vitals due now', tone: 'warn' });
  });

  it('a discharged patient has no line', () => {
    expect(vitalsDueView(null, DUE, true)).toBeNull();
    expect(vitalsDueView(DUE, DUE, true)).toBeNull();
  });

  it('a bad instant shows no line', () => {
    expect(vitalsDueView('not a time', DUE)).toBeNull();
  });

  it('the notice interval reads 1 hour, 4 hours or minutes', () => {
    expect(vitalsIntervalText(60)).toBe('1 hour');
    expect(vitalsIntervalText(240)).toBe('4 hours');
    expect(vitalsIntervalText(90)).toBe('90 minutes');
  });
});

describe('VitalsDueLineComponent', () => {
  let fixture: ComponentFixture<VitalsDueLineComponent>;

  function show(dueAt: string | null, asOf: string, discharged = false): HTMLElement {
    fixture = TestBed.createComponent(VitalsDueLineComponent);
    fixture.componentRef.setInput('dueAt', dueAt);
    fixture.componentRef.setInput('asOf', asOf);
    fixture.componentRef.setInput('discharged', discharged);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => TestBed.configureTestingModule({ imports: [VitalsDueLineComponent] }));

  it('draws the line with the text and a crit class when overdue', () => {
    const host = show(DUE, '2026-11-06T06:55:00Z');
    const line = host.querySelector('[data-testid=vitals-due]')!;
    expect(line.textContent?.replace(/\s+/g, ' ').trim()).toBe('Vitals overdue by 25 min');
    expect(line.classList.contains('crit')).toBe(true);
  });

  it('draws the upcoming time without an alert class', () => {
    const host = show(DUE, '2026-11-06T05:35:00Z');
    const line = host.querySelector('[data-testid=vitals-due]')!;
    expect(line.textContent?.replace(/\s+/g, ' ').trim()).toBe('Next vitals due 12:00 PM');
    expect(line.classList.contains('crit')).toBe(false);
    expect(line.classList.contains('warn')).toBe(false);
  });

  it('draws nothing for a discharged patient', () => {
    expect(show(null, DUE, true).querySelector('[data-testid=vitals-due]')).toBeNull();
  });
});
