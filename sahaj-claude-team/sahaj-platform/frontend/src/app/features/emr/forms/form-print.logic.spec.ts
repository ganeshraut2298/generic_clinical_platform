import { ff, footDefinition, settings } from './form.fixtures';
import { labelFor, printRows, word } from './form-print.logic';

describe('printed form (SAH-59-5)', () => {
  it('prints the standard words in English, or in two languages when the form asks', () => {
    expect(word('patient', 'EN')).toBe('Patient');
    expect(word('patient', 'EN_MR')).toContain('Patient / ');
    expect(word('signature', 'EN_HI')).toContain('Signature / ');
    expect(word('uhid', 'EN_MR')).toBe('UHID');
  });

  it('prints the field label in English and, when the owner typed one, in the second language too', () => {
    const f = ff({ key: 'a', type: 'SHORT_TEXT', label: 'Pain', labelMr: 'वेदना', labelHi: 'दर्द' });
    expect(labelFor(f, 'EN')).toBe('Pain');
    expect(labelFor(f, 'EN_MR')).toBe('Pain / वेदना');
    expect(labelFor(f, 'EN_HI')).toBe('Pain / दर्द');
    expect(labelFor(ff({ key: 'b', type: 'SHORT_TEXT', label: 'Pain' }), 'EN_MR')).toBe('Pain');
  });

  it('prints only the fields with Print on paper that are shown, with their values', () => {
    const def = footDefinition({
      fields: [
        ff({ key: 'a', type: 'SHORT_TEXT', label: 'Name of carer' }),
        ff({ key: 'b', type: 'SHORT_TEXT', label: 'Hidden from paper', print: false }),
        ff({ key: 'c', type: 'YES_NO', label: 'Diabetic' }),
        ff({ key: 'd', type: 'SHORT_TEXT', label: 'Depends', showIf: { fieldKey: 'c', optionKey: 'yes' } }),
      ],
    });
    const rows = printRows(def, { a: 'Asha', c: false, d: 'x' });
    expect(rows.map((r) => [r.label, r.value])).toEqual([
      ['Name of carer', 'Asha'],
      ['Diabetic', 'No'],
    ]);
  });

  it('leaves a blank line for an empty value, and prints headings and notes without a value', () => {
    const def = footDefinition({
      fields: [ff({ key: 'h', type: 'SECTION_HEADING', label: 'Examination' }), ff({ key: 'n', type: 'INFO_NOTE', label: 'Both feet' }), ff({ key: 'a', type: 'SHORT_TEXT', label: 'Remarks' })],
    });
    const rows = printRows(def, {});
    expect(rows.map((r) => r.kind)).toEqual(['heading', 'note', 'field']);
    expect(rows[2].value).toBe('________________');
  });

  it('prints option labels, numbers with their unit, the score and the band', () => {
    const def = footDefinition();
    const rows = printRows(def, { ulcer: true, mono: 7, vib: 'reduced', pulses: ['dpr', 'dpl'] });
    const by = Object.fromEntries(rows.map((r) => [r.label, r.value]));
    expect(by['Vibration sense']).toBe('Reduced');
    expect(by['Foot pulses felt']).toBe('DP right, DP left');
    expect(by['Monofilament: sites felt']).toBe('7 of 10');
    expect(by['Foot risk score']).toBe('5');
    expect(by['Risk category']).toBe('High: check every 1–3 months, refer');
  });

  it('carries the print language of the form settings into the labels', () => {
    const def = footDefinition({
      settings: settings({ printLanguage: 'EN_MR' }),
      fields: [ff({ key: 'a', type: 'SHORT_TEXT', label: 'Pain', labelMr: 'वेदना' })],
    });
    expect(printRows(def, {})[0].label).toBe('Pain / वेदना');
  });
});
