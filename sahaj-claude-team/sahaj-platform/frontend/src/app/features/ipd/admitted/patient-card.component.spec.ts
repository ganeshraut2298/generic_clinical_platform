import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AdmittedCardAbilities, AdmittedPatientRow } from '../ipd.model';
import {
  ABILITIES_FRONT_DESK,
  ABILITIES_NURSE,
  ABILITIES_OWNER,
  AS_OF,
  CLINICAL_QUIET,
  ROW,
  rowOf,
  withoutClinical,
} from './admitted.fixtures';
import { CardAction } from './admitted.model';
import { PatientCardComponent } from './patient-card.component';

describe('PatientCardComponent', () => {
  let fixture: ComponentFixture<PatientCardComponent>;
  let acted: CardAction[];
  let named: number;

  function render(row: AdmittedPatientRow, card: AdmittedCardAbilities = ABILITIES_NURSE): HTMLElement {
    fixture = TestBed.createComponent(PatientCardComponent);
    fixture.componentRef.setInput('row', row);
    fixture.componentRef.setInput('card', card);
    fixture.componentRef.setInput('asOf', AS_OF);
    acted = [];
    named = 0;
    fixture.componentInstance.act.subscribe((a) => acted.push(a));
    fixture.componentInstance.openName.subscribe(() => named++);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const text = (el: HTMLElement, sel: string) => el.querySelector(sel)!.textContent!.trim();

  it('is an article named "<name>, bed <code>"', () => {
    const view = render(ROW);
    const article = view.querySelector('article')!;
    expect(article.getAttribute('aria-label')).toBe('Asha Patil, bed 101-A');
  });

  it('shows the fixed Standard card in order', () => {
    const view = render(ROW);
    expect(text(view, '.pav')).toBe('AP');
    expect(view.querySelector('.pav')!.getAttribute('aria-hidden')).toBe('true');
    expect(text(view, '[data-testid=card-name]')).toBe('Asha Patil');
    expect(text(view, '[data-testid=card-identity]')).toBe('UHID PT-000042 · 56 · Female');
    expect(text(view, '[data-testid=card-bed]')).toBe('Bed101-A');
    const labels = Array.from(view.querySelectorAll('dt')).map((d) => d.textContent!.trim());
    expect(labels).toEqual(['Admitted on', 'Length of stay', 'Ward', 'Room', 'Doctor']);
    expect(text(view, '[data-testid=card-admitted-on]')).toBe('22 Oct 2026, 9:20 AM IST');
    expect(text(view, '[data-testid=card-stay]')).toBe('3 days');
    expect(text(view, '[data-testid=card-ward]')).toBe('General ward');
    expect(text(view, '[data-testid=card-room]')).toBe('Room 1');
    expect(text(view, '[data-testid=card-doctor]')).toBe('Dr. Meera Rao');
  });

  it('shows "Day 1 (today)" on the day of admission and a dash for no room', () => {
    const view = render(rowOf({ admittedAt: '2026-10-24T03:50:00Z', dayNumber: 1, roomName: null }));
    expect(text(view, '[data-testid=card-stay]')).toBe('Day 1 (today)');
    expect(text(view, '[data-testid=card-room]')).toBe('—');
  });

  it('shows a dash for a missing age, sex or UHID, never null or undefined', () => {
    const view = render(rowOf({ age: null, sex: null, uhid: null }));
    expect(text(view, '[data-testid=card-identity]')).toBe('UHID — · — · —');
    expect(view.textContent).not.toMatch(/null|undefined/);
  });

  it('keeps long text whole in the DOM and in the tooltip, cut only by CSS', () => {
    const ward = 'The very long name of a ward in a large hospital building';
    const view = render(rowOf({ wardName: ward, roomName: 'Room with a very long name 12345' }));
    expect(text(view, '[data-testid=card-ward]')).toBe(ward);
    expect(view.querySelector('[data-testid=card-ward]')!.getAttribute('title')).toBe(ward);
    expect(view.querySelector('[data-testid=card-room]')!.getAttribute('title')).toBe('Room with a very long name 12345');
  });

  it('opens the admission sheet from the name button, with the tooltip', () => {
    const view = render(ROW);
    const name = view.querySelector<HTMLButtonElement>('[data-testid=card-name]')!;
    expect(name.tagName).toBe('BUTTON');
    expect(name.getAttribute('title')).toBe('Open bill and bed details');
    name.click();
    expect(named).toBe(1);
  });

  it('draws the actions the server allows with their words and accessible names', () => {
    const view = render(ROW, ABILITIES_OWNER);
    const buttons = Array.from(view.querySelectorAll<HTMLButtonElement>('.pcacts button'));
    expect(buttons.map((b) => b.textContent!.trim())).toEqual(['Nursing', 'EMR', 'Transfer', 'Discharge']);
    expect(buttons.map((b) => b.getAttribute('aria-label'))).toEqual([
      'Nursing for Asha Patil',
      'EMR for Asha Patil',
      'Transfer for Asha Patil',
      'Discharge for Asha Patil',
    ]);
    expect(buttons[0].classList.contains('pri')).toBe(true);
    buttons.forEach((b) => b.click());
    expect(acted).toEqual(['nursing', 'emr', 'transfer', 'discharge']);
  });

  it('does not draw an action the server does not allow, and no row at all when none', () => {
    const nurse = render(ROW, ABILITIES_NURSE);
    expect(Array.from(nurse.querySelectorAll('.pcacts button')).map((b) => b.textContent!.trim())).toEqual([
      'Nursing',
      'EMR',
      'Transfer',
    ]);
    const front = render(ROW, ABILITIES_FRONT_DESK);
    expect(Array.from(front.querySelectorAll('.pcacts button')).map((b) => b.textContent!.trim())).toEqual(['Transfer']);
    const none = render(ROW, { nursing: false, emr: false, transfer: false, discharge: false, depositLowFlag: false });
    expect(none.querySelector('.pcacts')).toBeNull();
  });

  it('has no Case paper, Update stage, Deposit action or stage chip', () => {
    const view = render(ROW, ABILITIES_OWNER);
    expect(view.textContent).not.toMatch(/Case paper|Update stage|Deposit\b(?! low)/);
    expect(view.querySelector('.pcstage')).toBeNull();
  });

  it('keeps the Tab order name, Nursing, EMR, Transfer, Discharge (flags are not focusable)', () => {
    const view = render(
      rowOf({ goingHomeToday: true, clinical: { ...CLINICAL_QUIET, vitalsDue: true } }),
      ABILITIES_OWNER,
    );
    const focusable = Array.from(view.querySelectorAll<HTMLElement>('button, a, input, select, [tabindex]'));
    expect(focusable.map((e) => e.getAttribute('data-act') ?? e.getAttribute('data-testid'))).toEqual([
      'card-name',
      'nursing',
      'emr',
      'transfer',
      'discharge',
    ]);
  });

  describe('flags', () => {
    const flagTexts = (view: HTMLElement) =>
      Array.from(view.querySelectorAll('.pcstat .pcf')).map((f) => f.textContent!.replace(/\s+/g, ' ').trim());

    it('keeps an empty flags line (no text) for a quiet patient', () => {
      const view = render(ROW);
      const line = view.querySelector('[data-testid=card-flags]')!;
      expect(line).not.toBeNull();
      expect(line.textContent!.trim()).toBe('');
    });

    it('shows the escalation, allergy and EWS together in full, and folds the rest', () => {
      const view = render(
        rowOf({
          goingHomeToday: true,
          depositLow: true,
          clinical: {
            escalation: { status: 'OPEN', waitingMinutes: 12, targetMinutes: 15, pastTarget: false },
            latestEws: { score: 8, risk: 'HIGH', recordedAt: '2026-10-24T06:00:00Z' },
            vitalsDue: true,
            allergy: { status: 'RECORDED', text: 'Penicillin' },
          },
        }),
        ABILITIES_OWNER,
      );
      expect(flagTexts(view)).toEqual([
        'Not seen · 12 min',
        'Allergy: Penicillin',
        'EWS 8 · High',
        '+33 more: Vitals due, Going home today, Deposit low',
      ]);
      const more = view.querySelector('[data-flag=more]')!;
      expect(more.getAttribute('title')).toBe('3 more: Vitals due, Going home today, Deposit low');
      expect(more.querySelector('.sr-only')!.textContent).toBe('3 more: Vitals due, Going home today, Deposit low');
      expect(view.querySelector('[data-flag=escalation]')!.getAttribute('title')).toBe(
        'Early warning escalation raised 12 min ago, within the 15 min target, not seen by a doctor',
      );
    });

    it('gives the card a red outline for a High score only', () => {
      const high = render(
        rowOf({ clinical: { ...CLINICAL_QUIET, latestEws: { score: 8, risk: 'HIGH', recordedAt: '2026-10-24T06:00:00Z' } } }),
      );
      expect(high.querySelector('article')!.classList.contains('pc-crit')).toBe(true);
      const medium = render(
        rowOf({ clinical: { ...CLINICAL_QUIET, latestEws: { score: 5, risk: 'MEDIUM', recordedAt: '2026-10-24T06:00:00Z' } } }),
      );
      expect(medium.querySelector('article')!.classList.contains('pc-crit')).toBe(false);
    });

    it('shows a front desk card with Going home today and Deposit low but nothing clinical', () => {
      const view = render(
        withoutClinical(rowOf({ goingHomeToday: true, depositLow: true })),
        ABILITIES_FRONT_DESK,
      );
      expect(flagTexts(view)).toEqual(['Going home today', '+11 more: Deposit low']);
      expect(view.textContent).not.toMatch(/Allergy|EWS|Vitals due|Not seen|Doctor reviewing/);
    });

    it('hides Deposit low from a nurse', () => {
      const view = render(rowOf({ depositLow: true }), ABILITIES_NURSE);
      expect(view.textContent).not.toContain('Deposit low');
    });

    it('shows the avatar of a female patient with the secondary colour class', () => {
      expect(render(ROW).querySelector('.pav')!.classList.contains('f')).toBe(true);
      expect(render(rowOf({ sex: 'M' })).querySelector('.pav')!.classList.contains('f')).toBe(false);
    });
  });

  it('is built from the inputs only and makes no request', () => {
    // No HttpClient is provided in this TestBed: creating the card would throw if it asked for one.
    expect(() => render(ROW)).not.toThrow();
  });
});
