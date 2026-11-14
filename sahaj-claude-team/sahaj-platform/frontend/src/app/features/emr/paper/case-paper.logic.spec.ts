import { casePaperContext, field, section } from '../emr.fixtures';
import { EmrLayoutSection } from '../emr.model';
import { EmrBodyMapValue, EmrListItem, EmrListValue, EmrPrescriptionValue } from '../emr-phase3.model';
import {
  PaperInput,
  composePaper,
  footerLines,
  readyFooter,
  subtitle,
  visibleRows,
  vitalsLine,
  sentToPrinter,
} from './case-paper.logic';

function listValue(kind: EmrListValue['kind'], items: Partial<EmrListItem>[]): EmrListValue {
  return {
    component: 'EMR_LIST',
    kind,
    schemaVersion: 1,
    items: items.map((i, n) => ({ id: `i${n}`, itemKey: `k${n}`, text: 'X', newToList: false, attr1: 'D3', attr2: 'MODERATE', active: true, ...i })),
  };
}

const rxValue: EmrPrescriptionValue = {
  component: 'PRESCRIPTION',
  schemaVersion: 1,
  rows: [
    { id: 'r1', medicineId: 'm1', name: 'Telmisartan 40 mg', form: 'Tablet', frequency: '1-0-0', days: 30, when: 'AFTER_FOOD', source: 'SEARCH' },
    { id: 'r2', medicineId: 'm2', name: 'Metformin 500 mg SR', form: 'Tablet', frequency: '1-0-1', days: 1, when: 'BEDTIME', source: 'SEARCH' },
  ],
};

const comp = (key: string, kind: EmrListValue['kind'], master: 'complaints' | 'diagnoses' | 'advice') =>
  field({ key, valueKind: 'COMPONENT', type: 'SEARCH_LIST', optionsSource: 'MASTER', master, component: { type: 'EMR_LIST', listKind: kind } });

const sections: EmrLayoutSection[] = [
  section({ key: 'vitals', print: true, fields: [field({ key: 'bp_systolic' }), field({ key: 'bp_diastolic' }), field({ key: 'pulse' }), field({ key: 'spo2' })] }),
  section({ key: 'complaints', print: true, fields: [comp('complaint', 'COMPLAINTS', 'complaints')] }),
  section({ key: 'allergy', print: true, fields: [field({ key: 'drug_allergy', valueKind: 'ITEM_LIST' })] }),
  section({ key: 'diagnosis', print: true, part: 'TREATMENT', fields: [comp('dx', 'DIAGNOSIS', 'diagnoses')] }),
  section({ key: 'rx', print: true, part: 'TREATMENT', fields: [field({ key: 'drug', valueKind: 'COMPONENT', type: 'SEARCH_LIST', component: { type: 'PRESCRIPTION', listKind: null } })] }),
];

function input(values: PaperInput['values'], over: Partial<PaperInput> = {}): PaperInput {
  return { context: casePaperContext(), sections, values, language: 'EN', capitals: true, adviceText: () => null, ...over };
}

describe('live case paper (SAH-55)', () => {
  describe('vitals line', () => {
    it('writes BP, pulse and SpO2 from the vitals that are filled', () => {
      expect(vitalsLine({ bp_systolic: 142, bp_diastolic: 90, pulse: 82, spo2: 98 })).toBe('BP 142/90 · P 82 · SpO₂ 98%');
      expect(vitalsLine({ pulse: { value: 70, unit: '' } })).toBe('P 70');
      expect(vitalsLine({})).toBeNull();
    });
  });

  describe('headings', () => {
    it('fills the ten headings in order, in the chosen language', () => {
      const model = composePaper(input({}));
      expect(model.rows.map((r) => r.label)).toEqual([
        'Chief complaints',
        'Allergies',
        'Medical history',
        'Examination',
        'Diagnosis',
        'Investigations advised',
        'Medicines ℞',
        'Procedures & services',
        'Advice',
        'Follow-up',
      ]);
      expect(composePaper(input({}, { language: 'MR' })).rows[0].label).toBe('मुख्य तक्रारी');
      expect(composePaper(input({}, { language: 'HI' })).rows[9].label).toBe('अगली मुलाक़ात');
    });

    it('counts none filled on an empty record', () => {
      const model = composePaper(input({}));
      expect(model.filledCount).toBe(0);
      expect(subtitle(model)).toBe('0 of 10 filled · live');
      expect(subtitle(null)).toBe('0 of 10 filled · live');
    });

    it('writes a complaint with its duration and a diagnosis with its code', () => {
      const model = composePaper(
        input({
          complaints: { complaint: listValue('COMPLAINTS', [{ text: 'Headache', attr1: 'D2' }]) },
          diagnosis: { dx: listValue('DIAGNOSIS', [{ text: 'Essential hypertension', code: 'I10' }, { text: 'Typed dx' }]) },
        }),
      );
      expect(model.rows[0].lines).toEqual(['Headache · 2 days']);
      expect(model.rows[4].lines).toEqual(['Essential hypertension (I10)', 'Typed dx']);
      expect(model.filledCount).toBe(2);
      expect(subtitle(model)).toBe('2 of 10 filled · live');
    });

    it('leaves out items whose Active switch is off', () => {
      const model = composePaper(input({ complaints: { complaint: listValue('COMPLAINTS', [{ text: 'Cough' }, { text: 'Fever', active: false }]) } }));
      expect(model.rows[0].lines.join(' ')).toContain('Cough');
      expect(model.rows[0].lines.join(' ')).not.toContain('Fever');
    });

    it('counts Medicines when at least one medicine is present, numbered', () => {
      const model = composePaper(input({ rx: { drug: rxValue } }));
      const rx = model.rows[6];
      expect(rx.filled).toBe(true);
      expect(rx.rx.map((m) => [m.n, m.how, m.days, m.note])).toEqual([
        [1, '1-0-0', '30 days', 'After food'],
        [2, '1-0-1', '1 day', 'Bedtime'],
      ]);
    });

    it('prints medicine names in capitals only when the clinic setting is on', () => {
      expect(composePaper(input({ rx: { drug: rxValue } }, { capitals: true })).rows[6].rx[0].name).toBe('TELMISARTAN 40 MG');
      expect(composePaper(input({ rx: { drug: rxValue } }, { capitals: false })).rows[6].rx[0].name).toBe('Telmisartan 40 mg');
    });

    it('keeps medicine names in English whatever the heading language', () => {
      expect(composePaper(input({ rx: { drug: rxValue } }, { language: 'MR', capitals: false })).rows[6].rx[0].name).toBe('Telmisartan 40 mg');
    });

    it('puts an allergy list under Allergies', () => {
      const model = composePaper(input({ allergy: { drug_allergy: [{ itemKey: 'SULFA', text: 'Sulfa drugs', newToList: false }] } }));
      expect(model.rows[1].lines).toEqual(['Sulfa drugs']);
    });

    it('takes the vitals into the vitals line, not into a heading', () => {
      const model = composePaper(input({ vitals: { bp_systolic: 142, bp_diastolic: 90 } }));
      expect(model.vitalsLine).toBe('BP 142/90');
      expect(model.filledCount).toBe(0);
    });
  });

  describe('Print on prescription', () => {
    it('never lets a section with Print off reach the paper', () => {
      const off = sections.map((s) => (s.key === 'complaints' ? { ...s, print: false } : s));
      const model = composePaper(input({ complaints: { complaint: listValue('COMPLAINTS', [{ text: 'Headache' }]) } }, { sections: off }));
      expect(model.rows[0].filled).toBe(false);
    });

    it('never prints a section the server marks as NONE', () => {
      const ctx = casePaperContext({ sectionHeadings: [{ sectionKey: 'complaints', heading: 'NONE' }] });
      const model = composePaper(input({ complaints: { complaint: listValue('COMPLAINTS', [{ text: 'Headache' }]) } }, { context: ctx }));
      expect(model.filledCount).toBe(0);
    });
  });

  describe('advice language', () => {
    const adviceSections = [...sections, section({ key: 'advice', print: true, fields: [comp('advice', 'ADVICE', 'advice')] })];
    const ctx = casePaperContext({ sectionHeadings: [...casePaperContext().sectionHeadings, { sectionKey: 'advice', heading: 'ADVICE' }] });
    const advice = listValue('ADVICE', [
      { text: 'Low salt diet', attr1: 'MR', itemKey: 'a1' },
      { text: 'Walk daily', attr1: 'HI', itemKey: 'a2' },
      { text: 'Drink water', attr1: 'EN', itemKey: 'a3' },
    ]);

    it('prints each advice item in the language of its own attribute, English when the master has no text', () => {
      const text = (item: EmrListItem, lang: string): string | null => (item.itemKey === 'a1' && lang === 'MR' ? 'कमी मीठ' : null);
      const model = composePaper(input({ advice: { advice } }, { sections: adviceSections, context: ctx, adviceText: text }));
      expect(model.rows[8].lines).toEqual(['कमी मीठ', 'Walk daily', 'Drink water']);
    });
  });

  describe('body map under Examination', () => {
    const bodySections = [...sections, section({ key: 'local', print: true, fields: [field({ key: 'bm', valueKind: 'COMPONENT', type: 'BODY_MAP', component: { type: 'BODY_MAP', listKind: null } })] })];
    const ctx = casePaperContext({ sectionHeadings: [...casePaperContext().sectionHeadings, { sectionKey: 'local', heading: 'EXAMINATION' }] });
    const pin = { no: 1, side: 'FRONT' as const, x: 0.5, y: 0.5, region: 'torso2', where: 'Abdomen', finding: 'TENDERNESS' as const, cause: 'NO_INJURY' as const, severity: 'MODERATE' as const, sizeCm: 2, note: '', mlc: false };

    it('lists the pins as short lines and says "MLC case" for a medico-legal pin', () => {
      const plain: EmrBodyMapValue = { component: 'BODY_MAP', schemaVersion: 1, pins: [pin], noFindingsConfirmed: false };
      const a = composePaper(input({ local: { bm: plain } }, { sections: bodySections, context: ctx }));
      expect(a.rows[3].lines).toEqual(['Abdomen: tenderness, moderate, 2 cm']);
      expect(a.mlc).toBe(false);
      const mlc: EmrBodyMapValue = { ...plain, pins: [{ ...pin, mlc: true }] };
      const b = composePaper(input({ local: { bm: mlc } }, { sections: bodySections, context: ctx }));
      expect(b.rows[3].lines[0]).toBe('MLC case');
      expect(b.mlc).toBe(true);
    });
  });

  describe('Hide empty and the footer', () => {
    it('removes headings without content from the view but keeps the count', () => {
      const model = composePaper(input({ complaints: { complaint: listValue('COMPLAINTS', [{ text: 'Cough' }]) } }));
      expect(visibleRows(model, true).map((r) => r.heading)).toEqual(['CHIEF_COMPLAINTS']);
      expect(visibleRows(model, false)).toHaveLength(10);
      expect(model.filledCount).toBe(1);
    });

    it('prints the NMC line only when capitals is on, never the ABHA line before it exists, and always the signature line', () => {
      expect(footerLines(true, false)).toEqual(['Medicines in generic names, capital letters (NMC).', 'Not valid without signature.']);
      expect(footerLines(false, false)).toEqual(['Not valid without signature.']);
      expect(footerLines(true, true)).toContain("This visit is saved as an OP consultation record and can be shared to the patient's ABHA.");
    });

    it('words the ready footer with the chosen language', () => {
      expect(readyFooter('MR')).toBe('Ready to complete. Prescription prints in Marathi with English drug names.');
      expect(readyFooter('EN')).toBe('Ready to complete. Prescription prints in English with English drug names.');
      expect(readyFooter(null)).toBe('Ready to complete.');
    });
  });
});

describe('the print message (SAH-75-8 G17-3)', () => {
  it('says "Sent to printer · A4", and warns when fewer than 5 sections are filled', () => {
    expect(sentToPrinter('A4', 8)).toBe('Sent to printer · A4');
    expect(sentToPrinter('A5', 4)).toBe('Sent to printer · A5 · some sections are still empty');
    expect(sentToPrinter('A4', 5)).toBe('Sent to printer · A4');
  });
});
