import { VITALS, field, section, sections } from '../emr.fixtures';
import {
  compact,
  fieldProblem,
  footerText,
  isEmptyValue,
  isSectionDone,
  lastVisitText,
  ordinal,
  partProgress,
  sectionProblems,
  sectionStates,
  stillNeeded,
  vitalFlag,
  visitLine,
} from './emr-record.logic';

describe('emr-record.logic', () => {
  describe('empty values', () => {
    it('treats null, blank text, an empty list and an empty object as empty', () => {
      for (const v of [null, undefined, '', '   ', [], {}, { value: null, unit: 'days' }, { text: '' }]) {
        expect(isEmptyValue(v)).toBe(true);
      }
    });

    it('treats a number (also 0), a yes or no and a filled list as filled', () => {
      for (const v of [0, 5, false, true, 'x', [1], { value: 3, unit: 'days' }, { text: 'a' }]) {
        expect(isEmptyValue(v)).toBe(false);
      }
    });

    it('compact drops only the empty values', () => {
      expect(compact({ a: 'x', b: '', c: [], d: 0, e: false })).toEqual({ a: 'x', d: 0, e: false });
    });
  });

  describe('Done (OQ-45-25, OQ-45-26)', () => {
    it('is Done when every required field is filled', () => {
      expect(isSectionDone(VITALS, { bp_systolic: 120 }, false)).toBe(false);
      expect(isSectionDone(VITALS, { bp_systolic: 120, bp_diastolic: 80 }, false)).toBe(true);
    });

    it('is Done with no required field when at least one field is filled', () => {
      const optional = section({ key: 'o', fields: [field({ key: 'a' }), field({ key: 'b' })] });
      expect(isSectionDone(optional, {}, false)).toBe(false);
      expect(isSectionDone(optional, { b: 'x' }, false)).toBe(true);
    });

    it('is never Done while copied values wait for confirmation', () => {
      expect(isSectionDone(VITALS, { bp_systolic: 120, bp_diastolic: 80 }, true)).toBe(false);
    });

    it('ignores display-only and later fields', () => {
      const s = section({
        key: 's',
        fields: [field({ key: 'info', valueKind: 'DISPLAY_ONLY', required: true }), field({ key: 'photo', valueKind: 'LATER' })],
      });
      expect(isSectionDone(s, {}, false)).toBe(false);
    });
  });

  describe('parts and still needed (SAH-47-2, SAH-47-4)', () => {
    const layout = sections();
    const states = (values: Record<string, Record<string, unknown>>, pending: string[] = []) =>
      sectionStates(layout, values, (key) => pending.includes(key));

    it('gives Done, Required or Optional to each section', () => {
      const list = states({ complaints: { complaint: [{ itemKey: null, text: 'Fever', newToList: true }] } });
      expect(list.find((s) => s.key === 'complaints')!.status).toBe('DONE');
      expect(list.find((s) => s.key === 'diagnosis')!.status).toBe('REQUIRED');
      expect(list.find((s) => s.key === 'vitals')!.status).toBe('OPTIONAL');
    });

    it('counts required sections per part', () => {
      const [a, t] = partProgress(states({}));
      expect(a).toMatchObject({ part: 'ASSESSMENT', requiredDone: 0, requiredTotal: 1, complete: false, sectionsTotal: 3 });
      expect(t).toMatchObject({ part: 'TREATMENT', requiredDone: 0, requiredTotal: 1, complete: false });
    });

    it('shows a tick (complete) for a part with no required section, as "0 of 0"', () => {
      const none = sections().map((s) => ({ ...s, required: false }));
      const [a] = partProgress(sectionStates(none, {}, () => false));
      expect(a).toMatchObject({ requiredDone: 0, requiredTotal: 0, complete: true });
    });

    it('lists the required sections that are not done, in layout order', () => {
      expect(stillNeeded(states({}))).toEqual(['complaints', 'diagnosis']);
      expect(stillNeeded(states({ diagnosis: { provisional: [{ itemKey: 'J06.9', text: 'URTI', newToList: false }] } }))).toEqual([
        'complaints',
      ]);
    });

    it('never lists a hidden section: only the layout is looked at', () => {
      const hidden = layout.filter((s) => s.key !== 'diagnosis');
      expect(stillNeeded(sectionStates(hidden, {}, () => false))).toEqual(['complaints']);
    });

    it('writes the footer in the words of the mockup', () => {
      const name = (k: string) => (k === 'complaints' ? 'Chief complaints' : 'Diagnosis');
      expect(footerText(['complaints', 'diagnosis'], name)).toBe('Still needed: chief complaints, diagnosis.');
      expect(footerText([], name)).toBe('Ready to complete.');
    });
  });

  describe('field limits (SAH-47-3)', () => {
    const num = field({ key: 'n', valueKind: 'NUMBER', min: 0, max: 300 });

    it('accepts empty values', () => {
      expect(fieldProblem(num, null)).toBeNull();
    });

    it('limits numbers by the field range, the default range and four decimals', () => {
      expect(fieldProblem(num, 120)).toBeNull();
      expect(fieldProblem(num, 301)).toBe('Enter a number from 0 to 300.');
      expect(fieldProblem(num, -1)).toBe('Enter a number from 0 to 300.');
      expect(fieldProblem(num, 1.23456)).toBe('Use at most 4 decimal places.');
      expect(fieldProblem(field({ key: 'm', valueKind: 'NUMBER' }), 2_000_000)).toContain('1000000');
      expect(fieldProblem(num, Number.NaN)).toBeNull();
    });

    it('limits text to 2,000 characters and rich text to 10,000', () => {
      expect(fieldProblem(field({ key: 't' }), 'x'.repeat(2000))).toBeNull();
      expect(fieldProblem(field({ key: 't' }), 'x'.repeat(2001))).toBe('Use at most 2000 characters.');
      const rich = field({ key: 'r', type: 'RICH_TEXT' });
      expect(fieldProblem(rich, 'x'.repeat(9999))).toBeNull();
      expect(fieldProblem(rich, 'x'.repeat(10001))).toBe('Use at most 10000 characters.');
    });

    it('limits number with unit, dates, free choices, lists and table cells', () => {
      const nu = field({ key: 'd', valueKind: 'NUMBER_UNIT' });
      expect(fieldProblem(nu, { value: 3, unit: 'days' })).toBeNull();
      expect(fieldProblem(nu, { value: 3, unit: 'x'.repeat(21) })).toContain('unit');
      expect(fieldProblem(field({ key: 'x', valueKind: 'DATE' }), '2026-11-12')).toBeNull();
      expect(fieldProblem(field({ key: 'x', valueKind: 'DATE' }), '12/11/2026')).toBe('Enter a date.');
      expect(fieldProblem(field({ key: 'c', valueKind: 'CHOICE' }), 'x'.repeat(201))).toContain('200');
      expect(fieldProblem(field({ key: 'l', valueKind: 'ITEM_LIST' }), Array.from({ length: 51 }, () => ({ text: 'a' })))).toContain('50');
      expect(fieldProblem(field({ key: 'm', valueKind: 'MULTI_CHOICE' }), Array.from({ length: 51 }, () => 'a'))).toContain('50');
      const tbl = field({ key: 'tb', valueKind: 'TABLE' });
      expect(fieldProblem(tbl, [{ a: 'x'.repeat(501) }])).toContain('500');
      expect(fieldProblem(tbl, Array.from({ length: 51 }, () => ({ a: 'x' })))).toContain('50 rows');
    });

    it('collects the problems of a section by field key and skips later fields', () => {
      const s = section({
        key: 's',
        fields: [field({ key: 'n', valueKind: 'NUMBER', min: 0, max: 10 }), field({ key: 'p', valueKind: 'LATER' })],
      });
      expect(sectionProblems(s, { n: 11, p: 'x' })).toEqual({ n: 'Enter a number from 0 to 10.' });
      expect(sectionProblems(s, { n: 5 })).toEqual({});
    });
  });

  describe('vitals limits (tagged rule, DEC-039)', () => {
    it('flags blood pressure', () => {
      expect(vitalFlag('bp_systolic', 140)?.text).toBe('High');
      expect(vitalFlag('bp_systolic', 139)).toBeNull();
      expect(vitalFlag('bp_systolic', 89)?.text).toBe('Low');
      expect(vitalFlag('bp_systolic', 90)).toBeNull();
      expect(vitalFlag('bp_diastolic', 90)?.text).toBe('High');
      expect(vitalFlag('bp_diastolic', 89)).toBeNull();
    });

    it('flags pulse, oxygen, temperature and breathing', () => {
      expect(vitalFlag('pulse', 101)?.text).toBe('High');
      expect(vitalFlag('pulse', 100)).toBeNull();
      expect(vitalFlag('pulse', 59)?.text).toBe('Low');
      expect(vitalFlag('spo2', 94)?.text).toBe('Low');
      expect(vitalFlag('spo2', 95)).toBeNull();
      expect(vitalFlag('temperature', 99.5)?.text).toBe('Fever');
      expect(vitalFlag('temperature', 99.4)).toBeNull();
      expect(vitalFlag('temperature', 95.9)?.text).toBe('Low');
      expect(vitalFlag('respiratory_rate', 21)?.text).toBe('High');
      expect(vitalFlag('respiratory_rate', 20)).toBeNull();
    });

    it('uses red for high and fever, amber for low, reads number with unit, and ignores other fields', () => {
      expect(vitalFlag('pulse', 120)?.tone).toBe('crit');
      expect(vitalFlag('pulse', 40)?.tone).toBe('warn');
      expect(vitalFlag('pulse', { value: 120, unit: 'per min' })?.text).toBe('High');
      expect(vitalFlag('weight', 200)).toBeNull();
      expect(vitalFlag('pulse', null)).toBeNull();
      expect(vitalFlag('pulse', 'abc')).toBeNull();
    });
  });

  describe('words', () => {
    it('writes ordinals', () => {
      expect([1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 101, 111].map(ordinal)).toEqual([
        '1st', '2nd', '3rd', '4th', '11th', '12th', '13th', '21st', '22nd', '23rd', '101st', '111th',
      ]);
    });

    it('writes the visit line and the last visit tag', () => {
      expect(visitLine('FOLLOW_UP', 3)).toBe('Follow-up, 3rd visit');
      expect(visitLine('NEW', null)).toBe('New');
      expect(visitLine('REVISIT', 2)).toBe('Revisit, 2nd visit');
      expect(visitLine(null, 2)).toBeNull();
      expect(lastVisitText(14)).toBe('Last visit: 14 days ago');
      expect(lastVisitText(1)).toBe('Last visit: 1 day ago');
      expect(lastVisitText(0)).toBe('Last visit: today');
      expect(lastVisitText(null)).toBeNull();
    });
  });
});
