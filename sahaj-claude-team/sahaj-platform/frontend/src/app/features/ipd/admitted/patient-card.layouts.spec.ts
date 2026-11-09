import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CATALOGUE, resolvedStandard, standardSetup, withField } from '../card/card-setup.fixtures';
import { resolveSetup } from '../card/card-setup.logic';
import { CardLayout, CardSetup, ResolvedCardSetup } from '../card/card-setup.model';
import { AdmittedCardAbilities, AdmittedClinicalFlags, AdmittedPatientRow } from '../ipd.model';
import {
  ABILITIES_FRONT_DESK,
  ABILITIES_NURSE,
  ABILITIES_OWNER,
  AS_OF,
  ROW,
  rowOf,
  withoutClinical,
} from './admitted.fixtures';
import { CardAction } from './admitted.model';
import { PatientCardComponent } from './patient-card.component';

// 24 Oct 2026, 1:00 PM IST is AS_OF; the vitals were saved 35 minutes before.
const RECORDED = '2026-10-24T06:55:00Z';

const CLINICAL: AdmittedClinicalFlags = {
  escalation: null,
  latestEws: { score: 3, risk: 'LOW', recordedAt: RECORDED },
  vitalsDue: false,
  allergy: { status: 'NONE_KNOWN' },
  diagnosis: 'Fever under evaluation',
  latestVitals: {
    recordedAt: RECORDED,
    recordedByName: 'Sr. Example Nurse',
    bp: { systolic: 168, diastolic: 98, mark: 'CHECK' },
    pulse: { value: 88 },
    spo2: { value: 90, mark: 'CRITICAL' },
    temp: { value: 37 },
    rr: { value: 18 },
  },
};

function everything(layout: CardLayout): CardSetup {
  let s = standardSetup();
  for (const key of [
    'bp',
    'pulse',
    'spo2',
    'temp',
    'rr',
    'lastRecorded',
    'diagnosis',
    'ipNumber',
    'mobile',
    'admissionType',
    'runningBill',
    'deposit',
    'payer',
  ] as const) {
    s = withField(s, key, true);
  }
  s.sections.find((x) => x.key === 'billing')!.on = true;
  s.actions.forEach((a) => (a.on = true));
  return { ...s, layout };
}

describe('PatientCardComponent layouts (SAH-37)', () => {
  let fixture: ComponentFixture<PatientCardComponent>;
  let acted: CardAction[];

  function render(
    setup: ResolvedCardSetup,
    row: AdmittedPatientRow = rowOf({ clinical: CLINICAL, mobile: '98000 11111' }),
    card: AdmittedCardAbilities = ABILITIES_OWNER,
    preview = false,
  ): HTMLElement {
    fixture = TestBed.createComponent(PatientCardComponent);
    fixture.componentRef.setInput('row', row);
    fixture.componentRef.setInput('card', card);
    fixture.componentRef.setInput('asOf', AS_OF);
    fixture.componentRef.setInput('setup', setup);
    fixture.componentRef.setInput('preview', preview);
    acted = [];
    fixture.componentInstance.act.subscribe((a) => acted.push(a));
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const resolved = (s: CardSetup) => resolveSetup(s, CATALOGUE, { version: 2, isStandard: false });
  const text = (el: Element, sel: string) =>
    el.querySelector(sel)!.textContent!.replace(/\s+/g, ' ').trim();

  describe('Standard', () => {
    it('draws vital tiles with units, "—" for a missing reading and the words Check and Critical (AC55, AC58)', () => {
      const view = render(
        resolved(everything('standard')),
        rowOf({
          clinical: { ...CLINICAL, latestVitals: { ...CLINICAL.latestVitals!, rr: undefined } },
        }),
      );
      const tiles = Array.from(view.querySelectorAll('.vt'));
      expect(tiles.map((t) => t.getAttribute('data-vital'))).toEqual([
        'bp',
        'pulse',
        'spo2',
        'temp',
        'rr',
      ]);
      expect(text(tiles[0], 'b')).toBe('168/98mmHg');
      expect(text(tiles[1], 'b')).toBe('88/min');
      expect(text(tiles[2], 'b')).toBe('90%');
      expect(text(tiles[3], 'b')).toBe('37.0°C');
      expect(text(tiles[4], 'b')).toBe('—/min');
      expect(tiles[0].getAttribute('title')).toBe('Outside normal range');
      expect(text(tiles[0], 'em')).toBe('Check');
      expect(tiles[2].getAttribute('title')).toBe('Critical');
      expect(text(tiles[2], 'em')).toBe('Critical');
      expect(tiles[1].querySelector('em')).toBeNull();
      expect(tiles[1].getAttribute('title')).toBeNull();
    });

    it('shows Early warning and Last recorded below the tiles, as the server sent them (AC57)', () => {
      const view = render(resolved(everything('standard')));
      const foot = text(view, '.vfoot');
      expect(foot).toContain('Early warning 3 · Low');
      expect(foot).toContain('Last recorded 35 min ago · Sr. Example Nurse');
    });

    it('shows "No vitals recorded yet" and Last recorded "Not recorded" in amber when nothing was recorded (AC34)', () => {
      const none: AdmittedClinicalFlags = {
        escalation: null,
        latestEws: null,
        vitalsDue: true,
        allergy: { status: 'NOT_RECORDED' },
        latestVitals: null,
      };
      const view = render(resolved(everything('standard')), rowOf({ clinical: none }));
      expect(text(view, '[data-testid=card-no-vitals]')).toBe('No vitals recorded yet');
      expect(view.querySelector('.vt')).toBeNull();
      expect(text(view, '.vfoot')).toBe('Last recorded Not recorded');
      expect(view.querySelector('.vfoot .tone-warn')).not.toBeNull();
    });

    it('shows Allergies as None known, Not recorded or the recorded text in red (AC57)', () => {
      const allergy = (a: AdmittedClinicalFlags['allergy']) =>
        render(
          resolvedStandard(),
          rowOf({ clinical: { ...CLINICAL, allergy: a } }),
          ABILITIES_NURSE,
        );
      const known = allergy({ status: 'NONE_KNOWN' });
      expect(text(known, '[data-testid=card-field-allergies]')).toBe('None known');
      const unknown = allergy({ status: 'NOT_RECORDED' });
      expect(text(unknown, '[data-testid=card-field-allergies]')).toBe('Not recorded');
      expect(unknown.querySelector('[data-testid=card-field-allergies] .tone-warn')).not.toBeNull();
      const recorded = allergy({ status: 'RECORDED', text: 'Penicillin' });
      expect(text(recorded, '[data-testid=card-field-allergies]')).toBe('Penicillin');
      expect(
        recorded.querySelector('[data-testid=card-field-allergies] .tone-crit'),
      ).not.toBeNull();
    });

    it('shows the owner label as the heading, as text and never as markup (AC31)', () => {
      const s = everything('standard');
      s.sections.find((x) => x.key === 'care')!.fields[0].label = '<b>Consultant</b>';
      const view = render(resolved(s));
      const dts = Array.from(view.querySelectorAll('dt')).map((d) => d.textContent!.trim());
      expect(dts).toContain('<b>Consultant</b>');
      expect(view.querySelector('dt b')).toBeNull();
    });

    it('draws the actions in the published order, with Nursing as the primary button only when first (AC41)', () => {
      const s = everything('standard');
      s.actions = [s.actions[3], s.actions[0], s.actions[4], s.actions[1], s.actions[2]];
      const view = render(resolved(s));
      const buttons = Array.from(view.querySelectorAll<HTMLButtonElement>('.pcacts button'));
      expect(buttons.map((b) => b.getAttribute('data-act'))).toEqual([
        'discharge',
        'nursing',
        'deposit',
        'emr',
        'transfer',
      ]);
      expect(buttons.some((b) => b.classList.contains('pri'))).toBe(false);
      const first = everything('standard');
      first.actions = [first.actions[0], first.actions[4]];
      const again = render(resolved(first));
      expect(again.querySelector('.pcacts button')!.classList.contains('pri')).toBe(true);
    });

    it('draws a Deposit button for the owner and the front desk when it is on, and emits deposit (AC40)', () => {
      const view = render(resolved(everything('standard')));
      view.querySelector<HTMLButtonElement>('[data-act=deposit]')!.click();
      expect(acted).toEqual(['deposit']);
      const nurse = render(resolved(everything('standard')), undefined, ABILITIES_NURSE);
      expect(nurse.querySelector('[data-act=deposit]')).toBeNull();
    });

    it('draws no action row when every action is off, and the name still opens the sheet (AC41)', () => {
      const s = everything('standard');
      s.actions.forEach((a) => (a.on = false));
      const view = render(resolved(s));
      expect(view.querySelector('.pcacts')).toBeNull();
      expect(view.querySelector('[data-testid=card-name]')!.tagName).toBe('BUTTON');
    });
  });

  describe('Compact', () => {
    it('draws each section as one line of values without titles (AC55)', () => {
      const view = render(resolved(everything('compact')));
      expect(view.querySelector('dl')).toBeNull();
      expect(view.querySelector('.pcst')).toBeNull();
      const lines = Array.from(view.querySelectorAll('.pcline')).map((l) =>
        l.getAttribute('data-section'),
      );
      expect(lines).toEqual(['stay', 'care', 'clinical', 'billing']);
      expect(text(view, '.pcline[data-section=care]')).toContain('Dr. Meera Rao');
    });

    it('shows only BP, SpO₂ and Pulse of the readings, plus the locked Early warning (AC55, AC57)', () => {
      const view = render(resolved(everything('compact')));
      const minis = Array.from(view.querySelectorAll('.vmini > span')).map((m) =>
        m.getAttribute('data-vital'),
      );
      expect(minis).toEqual(['bp', 'pulse', 'spo2', 'ews']);
      expect(text(view, '.vmini [data-vital=spo2]')).toContain('Critical');
      expect(view.querySelector('.vt')).toBeNull();
    });

    it('always shows "Allergies: Not recorded" or "Allergies: None known" (DEC-027)', () => {
      const notRecorded = render(
        resolved(standardSetup()),
        rowOf({ clinical: { ...CLINICAL, allergy: { status: 'NOT_RECORDED' } } }),
        ABILITIES_NURSE,
      );
      expect(notRecorded.querySelector('.pcline')).toBeNull();
      const s = standardSetup();
      s.layout = 'compact';
      const compactNot = render(
        resolved(s),
        rowOf({ clinical: { ...CLINICAL, allergy: { status: 'NOT_RECORDED' } } }),
        ABILITIES_NURSE,
      );
      expect(text(compactNot, '.pcline[data-section=clinical]')).toContain(
        'Allergies: Not recorded',
      );
      const compactNone = render(
        resolved(s),
        rowOf({ clinical: { ...CLINICAL, allergy: { status: 'NONE_KNOWN' } } }),
        ABILITIES_NURSE,
      );
      expect(text(compactNone, '.pcline[data-section=clinical]')).toContain(
        'Allergies: None known',
      );
    });

    it('draws icon-only actions with a tooltip and the name "<Action> for <patient>" (AC55)', () => {
      const view = render(resolved(everything('compact')));
      const buttons = Array.from(view.querySelectorAll<HTMLButtonElement>('.pcacts button'));
      expect(buttons.map((b) => b.getAttribute('aria-label'))).toEqual([
        'Nursing for Asha Patil',
        'EMR for Asha Patil',
        'Transfer for Asha Patil',
        'Discharge for Asha Patil',
        'Deposit for Asha Patil',
      ]);
      expect(buttons.map((b) => b.getAttribute('title'))).toEqual([
        'Nursing',
        'EMR',
        'Transfer',
        'Discharge',
        'Deposit',
      ]);
      expect(buttons.every((b) => b.textContent!.trim() === '')).toBe(true);
    });
  });

  describe('Expanded', () => {
    it('draws section titles and every switched-on field (AC55)', () => {
      const view = render(resolved(everything('expanded')));
      const titles = Array.from(view.querySelectorAll('.pcst')).map((t) => t.textContent!.trim());
      expect(titles).toEqual(['Admission and bed', 'Care team', 'Vitals', 'Clinical', 'Billing']);
      const labels = Array.from(view.querySelectorAll('dt')).map((d) => d.textContent!.trim());
      expect(labels).toEqual([
        'Admitted on',
        'Length of stay',
        'Ward',
        'Room',
        'Admission type',
        'Doctor',
        'Diagnosis',
        'Allergies',
        'Running bill',
        'Deposit',
        'Payer',
      ]);
      expect(text(view, '[data-testid=card-field-diagnosis]')).toBe('Fever under evaluation');
      expect(text(view, '[data-testid=card-field-runningBill]')).toBe('₹3,000');
    });

    it('has no Last nursing note (DEC-027)', () => {
      expect(render(resolved(everything('expanded'))).textContent).not.toMatch(/nursing note/i);
    });

    it('draws icon and word buttons', () => {
      const view = render(resolved(everything('expanded')));
      expect(
        Array.from(view.querySelectorAll('.pcacts button')).map((b) => b.textContent!.trim()),
      ).toEqual(['Nursing', 'EMR', 'Transfer', 'Discharge', 'Deposit']);
    });
  });

  describe('roles (AC56)', () => {
    it('draws the identity line with IP number and, for the owner and front desk only, the mobile', () => {
      const s = resolved(everything('standard'));
      const owner = render(s);
      expect(text(owner, '[data-testid=card-identity]')).toBe(
        'UHID PT-000042 · 56 · Female · IP number IP/26-27/0042 · 98000 11111',
      );
      const nurse = render(s, rowOf({ clinical: CLINICAL }), ABILITIES_NURSE);
      expect(text(nurse, '[data-testid=card-identity]')).not.toContain('98000');
      const front = render(
        s,
        withoutClinical(rowOf({ mobile: '98000 11111' })),
        ABILITIES_FRONT_DESK,
      );
      expect(text(front, '[data-testid=card-identity]')).toContain('98000 11111');
    });

    it('draws no billing field for a nurse and no vitals, diagnosis or allergies for the front desk', () => {
      const s = resolved(everything('standard'));
      const nurse = render(s, rowOf({ clinical: CLINICAL }), ABILITIES_NURSE);
      expect(nurse.querySelector('[data-section=billing]')).toBeNull();
      expect(nurse.textContent).not.toMatch(/Running bill|Payer/);
      const front = render(s, withoutClinical(rowOf()), ABILITIES_FRONT_DESK);
      expect(front.querySelector('[data-testid=card-vitals]')).toBeNull();
      expect(front.querySelector('[data-section=clinical]')).toBeNull();
      expect(front.querySelector('[data-section=billing]')).not.toBeNull();
    });

    it('does not draw a section that has no visible field for the role', () => {
      const front = render(resolvedStandard(), withoutClinical(ROW), ABILITIES_FRONT_DESK);
      expect(front.querySelector('[data-testid=card-vitals]')).toBeNull();
      expect(front.querySelector('[data-section=clinical]')).toBeNull();
    });
  });

  describe('safety', () => {
    it('keeps UHID, Alerts, Early warning and Allergies on the card even when the setup lacks them (AC57, AC61)', () => {
      const bare: ResolvedCardSetup = {
        version: 3,
        isStandard: false,
        fallback: false,
        layout: 'standard',
        cardsPerRow: 3,
        sections: [
          {
            key: 'stay',
            name: 'Admission and bed',
            fields: [{ key: 'ward', label: 'Ward', group: 'patient', safety: false }],
          },
        ],
        actions: [],
      };
      const view = render(bare, rowOf({ clinical: CLINICAL }), ABILITIES_NURSE);
      expect(text(view, '[data-testid=card-name]')).toBe('Asha Patil');
      expect(text(view, '[data-testid=card-identity]')).toContain('UHID PT-000042');
      expect(view.querySelector('[data-testid=card-flags]')).not.toBeNull();
      expect(view.querySelector('[data-testid=card-vitals]')).not.toBeNull();
      expect(view.querySelector('[data-testid=card-field-allergies]')).not.toBeNull();
    });

    it('restores the standard names of the safety fields whatever the setup says (AC32)', () => {
      const s = resolved(standardSetup());
      s.sections
        .find((x) => x.key === 'clinical')!
        .fields.forEach((f) => {
          if (f.key === 'allergies') {
            f.label = 'Notes';
          }
        });
      const view = render(s, rowOf({ clinical: CLINICAL }), ABILITIES_NURSE);
      const dts = Array.from(view.querySelectorAll('dt')).map((d) => d.textContent!.trim());
      expect(dts).toContain('Allergies');
      expect(dts).not.toContain('Notes');
    });

    it('keeps the red outline for a High score and the safety flags in full', () => {
      const high: AdmittedClinicalFlags = {
        ...CLINICAL,
        latestEws: { score: 8, risk: 'HIGH', recordedAt: RECORDED },
      };
      const view = render(resolved(standardSetup()), rowOf({ clinical: high }), ABILITIES_NURSE);
      expect(view.querySelector('article')!.classList.contains('pc-crit')).toBe(true);
      expect(view.querySelector('[data-flag=ews]')).not.toBeNull();
    });
  });

  describe('inert preview (AC47)', () => {
    it('draws the name as text and no button can be tabbed to or used', () => {
      const view = render(resolved(everything('standard')), undefined, ABILITIES_OWNER, true);
      const name = view.querySelector('[data-testid=card-name]')!;
      expect(name.tagName).not.toBe('BUTTON');
      const buttons = Array.from(view.querySelectorAll<HTMLButtonElement>('.pcacts button'));
      expect(buttons.length).toBeGreaterThan(0);
      expect(
        buttons.every(
          (b) => b.getAttribute('tabindex') === '-1' && b.getAttribute('aria-disabled') === 'true',
        ),
      ).toBe(true);
      buttons.forEach((b) => b.click());
      expect(acted).toEqual([]);
      expect(view.querySelectorAll('button:not([tabindex="-1"]), a').length).toBe(0);
    });
  });
});
