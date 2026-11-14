import { EmrListValue } from '../emr-phase3.model';
import { ff, footDefinition } from './form.fixtures';
import {
  abnormalCount,
  bandOf,
  checkNumber,
  fieldCountLine,
  formProblems,
  isFieldFilled,
  isFormDone,
  isVisible,
  missingRequired,
  optionsOf,
  scoreTotal,
  statusLine,
  valuesToSave,
} from './form.logic';

const def = footDefinition();
const field = (key: string) => def.fields.find((f) => f.key === key)!;

describe('form rules (SAH-59)', () => {
  describe('show-if (SAH-59-2)', () => {
    it('hides a field until its source has the value', () => {
      const site = field('site');
      expect(isVisible(site, def.fields, {})).toBe(false);
      expect(isVisible(site, def.fields, { now: false })).toBe(false);
      expect(isVisible(site, def.fields, { now: true })).toBe(true);
    });

    it('shows a field for a Multiple choice source when the value is among those chosen', () => {
      const fields = [
        ff({ key: 'habits', type: 'MULTI_CHOICE', options: [{ key: 'smoke', label: 'Smoking', score: 0 }, { key: 'chew', label: 'Chewing', score: 0 }] }),
        ff({ key: 'detail', type: 'SHORT_TEXT', showIf: { fieldKey: 'habits', optionKey: 'chew' } }),
      ];
      expect(isVisible(fields[1], fields, { habits: ['smoke'] })).toBe(false);
      expect(isVisible(fields[1], fields, { habits: ['smoke', 'chew'] })).toBe(true);
    });

    it('hides a dependant when its source is hidden too (chains)', () => {
      const fields = [
        ff({ key: 'a', type: 'YES_NO' }),
        ff({ key: 'b', type: 'YES_NO', showIf: { fieldKey: 'a', optionKey: 'yes' } }),
        ff({ key: 'c', type: 'SHORT_TEXT', showIf: { fieldKey: 'b', optionKey: 'yes' } }),
      ];
      expect(isVisible(fields[2], fields, { a: false, b: true })).toBe(false);
      expect(isVisible(fields[2], fields, { a: true, b: true })).toBe(true);
    });

    it('treats a rule that points to a later or missing field as hidden', () => {
      const fields = [ff({ key: 'x', type: 'SHORT_TEXT', showIf: { fieldKey: 'y', optionKey: 'yes' } }), ff({ key: 'y', type: 'YES_NO' })];
      expect(isVisible(fields[0], fields, { y: true })).toBe(false);
    });

    it('leaves a hidden required field out of the required count and does not save its value', () => {
      expect(missingRequired(def, {}).map((f) => f.key)).toEqual(['ulcer', 'mono']);
      const values = { ulcer: true, mono: 9, now: false, site: 'typed before' };
      expect(missingRequired(def, values)).toEqual([]);
      expect(valuesToSave(def, values)).toEqual({ ulcer: true, mono: 9, now: false });
    });
  });

  describe('scores and bands (SAH-59-3, C-9 tagged)', () => {
    it('is blank until at least one source is answered', () => {
      expect(scoreTotal(field('risk'), def.fields, {})).toBeNull();
      expect(scoreTotal(field('risk'), def.fields, { ulcer: false })).toBe(0);
    });

    it('adds the scores of the chosen options of the sources', () => {
      expect(scoreTotal(field('risk'), def.fields, { ulcer: true, vib: 'reduced', now: false })).toBe(3);
    });

    it('adds points when a number is below a value or fewer options are chosen', () => {
      expect(scoreTotal(field('risk'), def.fields, { mono: 6 })).toBe(1);
      expect(scoreTotal(field('risk'), def.fields, { mono: 9 })).toBe(0);
      expect(scoreTotal(field('risk'), def.fields, { pulses: ['dpr', 'dpl'] })).toBe(1);
      expect(scoreTotal(field('risk'), def.fields, { pulses: ['dpr', 'dpl', 'ptr', 'ptl'] })).toBe(0);
      expect(scoreTotal(field('risk'), def.fields, { mono: 6, pulses: ['dpr'], ulcer: true })).toBe(4);
    });

    it('adds all chosen options of a Multiple choice source', () => {
      const fields = [
        ff({ key: 'm', type: 'MULTI_CHOICE', options: [{ key: 'a', label: 'A', score: 2 }, { key: 'b', label: 'B', score: 3 }] }),
        ff({ key: 's', type: 'SCORE', score: { sourceFieldKeys: ['m'], rules: [] } }),
      ];
      expect(scoreTotal(fields[1], fields, { m: ['a', 'b'] })).toBe(5);
    });

    it('ignores a source hidden by its rule', () => {
      const values = { now: false, site: 'x' };
      expect(scoreTotal(field('risk'), def.fields, values)).toBe(0);
    });

    it('picks the band with the highest "from" not above the total', () => {
      const band = (values: Record<string, unknown>) => bandOf(field('cat'), def.fields, values)?.label;
      expect(band({ ulcer: false })).toBe('Low risk: check every year');
      expect(band({ ulcer: true })).toBe('Moderate: check every 6 months');
      expect(band({ ulcer: true, vib: 'reduced' })).toBe('High: check every 1–3 months, refer');
      expect(band({})).toBeUndefined();
    });

    it('moves the colour from green through amber to red and keeps the label text', () => {
      const tone = (values: Record<string, unknown>) => bandOf(field('cat'), def.fields, values)?.tone;
      expect(tone({ ulcer: false })).toBe('good');
      expect(tone({ ulcer: true })).toBe('warn');
      expect(tone({ ulcer: true, vib: 'reduced' })).toBe('crit');
    });
  });

  describe('numbers (SAH-59-3)', () => {
    it('refuses a value outside min and max', () => {
      expect(checkNumber(field('mono'), 11)).toEqual({ kind: 'outside-limits', message: 'Outside 0–10' });
      expect(checkNumber(field('mono'), -1)?.kind).toBe('outside-limits');
    });

    it('accepts a value inside the limits but outside the normal range, flagged Abnormal', () => {
      expect(checkNumber(field('mono'), 6)).toEqual({ kind: 'abnormal', message: 'Abnormal' });
      expect(checkNumber(field('mono'), 9)).toBeNull();
    });

    it('says nothing for an empty value or another type', () => {
      expect(checkNumber(field('mono'), null)).toBeNull();
      expect(checkNumber(field('vib'), 3)).toBeNull();
    });

    it('lists the values the server would refuse, and counts the abnormal ones', () => {
      expect(formProblems(def, { mono: 12 })).toEqual({ mono: 'Outside 0–10' });
      expect(formProblems(def, { mono: 6 })).toEqual({});
      expect(abnormalCount(def, { mono: 6 })).toBe(1);
    });
  });

  describe('Done and the status line (SAH-59-5)', () => {
    it('is Done when every required shown field is filled', () => {
      expect(isFormDone(def, {})).toBe(false);
      expect(isFormDone(def, { ulcer: true, mono: 9 })).toBe(true);
      expect(isFormDone(def, { ulcer: true, mono: 9, now: true })).toBe(false);
      expect(isFormDone(def, { ulcer: true, mono: 9, now: true, site: 'right heel' })).toBe(true);
    });

    it('is Done with one filled field when none is required', () => {
      const d = { ...def, fields: [ff({ key: 'a', type: 'SHORT_TEXT' })] };
      expect(isFormDone(d, {})).toBe(false);
      expect(isFormDone(d, { a: 'x' })).toBe(true);
    });

    it('writes the three status lines', () => {
      expect(statusLine(def, {}, false)).toBe('0 of 2 required fields filled');
      expect(statusLine(def, {}, true)).toBe('2 required field(s) missing: Previous foot ulcer, Monofilament: sites felt');
      expect(statusLine(def, { ulcer: true, mono: 6 }, false)).toBe('1 value(s) outside the normal range');
      expect(statusLine(def, { ulcer: true, mono: 9 }, false)).toBe('2 of 2 required fields filled');
    });

    it('writes the count line of the builder', () => {
      expect(fieldCountLine(def.fields)).toBe('9 fields · 1 with logic · 2 scored');
    });
  });

  describe('filled values', () => {
    it('knows a filled yes or no even when it is No', () => {
      expect(isFieldFilled(field('ulcer'), false)).toBe(true);
      expect(isFieldFilled(field('ulcer'), null)).toBe(false);
    });

    it('treats an empty list and blank text as empty', () => {
      expect(isFieldFilled(field('pulses'), [])).toBe(false);
      expect(isFieldFilled(field('site'), '  ')).toBe(false);
      expect(isFieldFilled(field('mono'), 0)).toBe(true);
    });

    it('needs an active item in a component field', () => {
      const f = ff({ key: 'dx', type: 'DIAGNOSIS' });
      const v: EmrListValue = { component: 'EMR_LIST', kind: 'DIAGNOSIS', schemaVersion: 1, items: [{ id: 'i', itemKey: null, text: 'x', newToList: true, attr1: 'PROVISIONAL', attr2: 'ACTIVE', active: false }] };
      expect(isFieldFilled(f, v)).toBe(false);
      v.items[0].active = true;
      expect(isFieldFilled(f, v)).toBe(true);
    });

    it('gives Yes / No its fixed options with their scores', () => {
      expect(optionsOf(field('ulcer')).map((o) => [o.key, o.score])).toEqual([['yes', 2], ['no', 0]]);
      expect(optionsOf(ff({ key: 'z', type: 'YES_NO' })).map((o) => o.key)).toEqual(['yes', 'no']);
    });
  });
});
