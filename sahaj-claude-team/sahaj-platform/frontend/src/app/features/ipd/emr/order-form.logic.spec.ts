import { DETAILS, DRUGS, PRIORITIES, ROUTES, FREQUENCIES, sheetOf } from './emr.fixtures';
import {
  OrderDraft,
  blankDraft,
  draftOf,
  drugDefaults,
  previewLines,
  reasonProblem,
  startInstant,
  toChangeInput,
  toOrderInput,
  validateDraft,
} from './order-form.logic';

const draft = (over: Partial<OrderDraft> = {}): OrderDraft => ({ ...blankDraft(sheetOf()), dose: '1', ...over });
const units = ['TABLET', 'MG'];

describe('order form logic (SAH-76-1)', () => {
  describe('blank draft and defaults', () => {
    it('starts with the first medicine, its route and unit, Routine and an empty dose', () => {
      expect(blankDraft(sheetOf())).toMatchObject({
        drug: 'pcm',
        freq: 'od',
        dose: '',
        unit: 'TABLET',
        route: 'ORAL',
        priority: 'ROUTINE',
        start: '',
        duration: '',
      });
    });
    it('takes the route and first unit of the medicine chosen', () => {
      expect(drugDefaults(DRUGS[3])).toEqual({ unit: 'IU', route: 'SC' });
      expect(drugDefaults(undefined)).toBeNull();
    });
  });

  describe('validateDraft', () => {
    it('passes a complete scheduled order', () => {
      expect(validateDraft(draft(), units, ROUTES)).toEqual({});
    });
    it.each(['', '0', '-1', 'abc', '1.2345', '100001'])('refuses the dose "%s"', (dose) => {
      expect(validateDraft(draft({ dose }), units, ROUTES).doseAmount).toBe('Enter the dose.');
    });
    it('accepts up to three decimals', () => {
      expect(validateDraft(draft({ dose: '0.125' }), units, ROUTES)).toEqual({});
    });
    it('refuses a unit the medicine does not offer and a route not in the list', () => {
      const errors = validateDraft(draft({ unit: 'ML', route: 'X' }), units, ROUTES);
      expect(errors.doseUnit).toBe('Choose the unit.');
      expect(errors.routeKey).toBe('Choose the route.');
    });
    it.each(['0', '366', '1.5', 'x'])('refuses the duration "%s"', (duration) => {
      expect(validateDraft(draft({ duration }), units, ROUTES).durationDays).toBe(
        'Enter whole days, or choose Until stopped.',
      );
    });
    it('accepts 1 and 365 days and an empty duration (Until stopped)', () => {
      for (const duration of ['', '1', '365']) {
        expect(validateDraft(draft({ duration }), units, ROUTES)).toEqual({});
      }
    });
    it('needs How often unless the order is STAT, and ignores duration and frequency for STAT', () => {
      expect(validateDraft(draft({ freq: '' }), units, ROUTES).frequencyKey).toBe('Choose how often');
      expect(validateDraft(draft({ freq: '', priority: 'STAT', duration: '0' }), units, ROUTES)).toEqual({});
    });
    it('needs the indication, the interval (15 to 4320 minutes) and the most doses (1 to 24) for PRN', () => {
      const prn = draft({ freq: 'prn' });
      expect(validateDraft(prn, units, ROUTES)).toEqual({
        indication: 'Enter what it is for.',
        prnMinIntervalMinutes: 'Enter the minimum time between doses.',
        prnMaxPer24h: 'Enter the most doses in 24 hours.',
      });
      expect(validateDraft({ ...prn, indication: 'Fever', prnInterval: '14', prnMax: '25' }, units, ROUTES)).toEqual({
        prnMinIntervalMinutes: 'Enter the minimum time between doses.',
        prnMaxPer24h: 'Enter the most doses in 24 hours.',
      });
      expect(
        validateDraft({ ...prn, indication: 'Fever', prnInterval: '72', prnUnit: 'h', prnMax: '24' }, units, ROUTES),
      ).toEqual({});
      expect(validateDraft({ ...prn, indication: 'Fever', prnInterval: '73', prnUnit: 'h', prnMax: '4' }, units, ROUTES).prnMinIntervalMinutes).toBeDefined();
    });
    it('limits the indication to 200 characters', () => {
      expect(validateDraft(draft({ indication: 'x'.repeat(201) }), units, ROUTES).indication).toBe(
        'Keep it to 200 characters.',
      );
    });
  });

  describe('toOrderInput', () => {
    it('sends only what the contract names, with the trimmed indication', () => {
      expect(toOrderInput(draft({ indication: ' Fever ', duration: '5' }), 'k1', null)).toEqual({
        clientKey: 'k1',
        drugKey: 'pcm',
        frequencyKey: 'od',
        doseAmount: 1,
        doseUnit: 'TABLET',
        routeKey: 'ORAL',
        durationDays: 5,
        indication: 'Fever',
        priority: 'ROUTINE',
      });
    });
    it('leaves out the frequency, the duration and the start for STAT', () => {
      const body = toOrderInput(draft({ priority: 'STAT', duration: '5', start: '2026-11-07T08:30' }), 'k1', null);
      expect(body).not.toHaveProperty('frequencyKey');
      expect(body).not.toHaveProperty('durationDays');
      expect(body).not.toHaveProperty('startAt');
    });
    it('converts the interval to minutes for PRN and adds the reason with its token', () => {
      const body = toOrderInput(
        draft({ freq: 'prn', indication: 'Fever', prnInterval: '6', prnUnit: 'h', prnMax: '4' }),
        'k1',
        { overrideReasonKey: 'BENEFIT_MONITORED', checkToken: 'T'.repeat(22) },
      );
      expect(body).toMatchObject({
        prnMinIntervalMinutes: 360,
        prnMaxPer24h: 4,
        overrideReasonKey: 'BENEFIT_MONITORED',
        checkToken: 'T'.repeat(22),
      });
    });
  });

  it('reads a typed start as an IST instant and an empty one as "now"', () => {
    expect(startInstant('2026-11-07T08:30')).toBe('2026-11-07T08:30:00+05:30');
    expect(startInstant('')).toBeNull();
    expect(startInstant('7 Nov')).toBeNull();
  });

  describe('Change', () => {
    it('is prefilled from the current version, with hours when the PRN interval is whole hours', () => {
      const prn = { ...DETAILS, kind: 'PRN' as const, frequencyKey: 'prn' as const, prnMinIntervalMinutes: 360, prnMaxPer24h: 4, indication: 'Fever', durationDays: 3 };
      expect(draftOf(prn, DRUGS[0])).toMatchObject({
        freq: 'prn',
        dose: '1',
        unit: 'TABLET',
        route: 'ORAL',
        duration: '3',
        indication: 'Fever',
        prnInterval: '6',
        prnUnit: 'h',
        prnMax: '4',
      });
      expect(draftOf({ ...prn, prnMinIntervalMinutes: 90 }, DRUGS[0])).toMatchObject({ prnInterval: '90', prnUnit: 'min' });
    });
    it('has no frequency in the draft of a STAT order', () => {
      expect(draftOf({ ...DETAILS, kind: 'ONCE', frequencyKey: null, priority: 'STAT' }, DRUGS[0]).freq).toBe('');
    });
    it('sends null for what is absent, trims the reason and adds the start only when it was edited', () => {
      const d = draft();
      expect(toChangeInput(d, 'k', 3, '  Not enough  ', false)).toMatchObject({
        orderVersion: 3,
        reason: 'Not enough',
        durationDays: null,
        indication: null,
        prnMinIntervalMinutes: null,
        prnMaxPer24h: null,
      });
      expect(toChangeInput(d, 'k', 3, 'Not enough', false)).not.toHaveProperty('startAt');
      expect(toChangeInput({ ...d, start: '2026-11-07T08:30' }, 'k', 3, 'Not enough', true).startAt).toBe(
        '2026-11-07T08:30:00+05:30',
      );
    });
    it('lists only the edited fields as old and new', () => {
      const labels = { routes: ROUTES, frequencies: FREQUENCIES, priorities: PRIORITIES };
      const base = draftOf(DETAILS, DRUGS[0]);
      expect(previewLines(base, DETAILS, labels)).toEqual([]);
      expect(previewLines({ ...base, dose: '2', route: 'IV', freq: 'bd', duration: '5', indication: 'Pain', priority: 'URGENT' }, DETAILS, labels)).toEqual([
        { field: 'dose', label: 'Dose', before: '1 tablet', after: '2 tablet' },
        { field: 'route', label: 'Route', before: 'Oral', after: 'IV' },
        { field: 'frequency', label: 'How often', before: 'Once daily', after: 'Twice daily' },
        { field: 'end', label: 'Duration', before: 'Until stopped', after: '5 days' },
        { field: 'indication', label: 'Indication', before: '', after: 'Pain' },
        { field: 'priority', label: 'Priority', before: 'Routine', after: 'Urgent' },
      ]);
    });
    it('shows STAT as the new frequency and "Until stopped" as the duration', () => {
      const labels = { routes: ROUTES, frequencies: FREQUENCIES, priorities: PRIORITIES };
      const base = draftOf({ ...DETAILS, durationDays: 5 }, DRUGS[0]);
      const lines = previewLines({ ...base, priority: 'STAT' }, { ...DETAILS, durationDays: 5 }, labels);
      expect(lines.find((l) => l.field === 'frequency')).toMatchObject({ before: 'Once daily', after: 'STAT' });
      expect(lines.find((l) => l.field === 'end')).toMatchObject({ before: '5 days', after: 'Until stopped' });
    });
  });

  describe('reasonProblem', () => {
    it('wants 4 to 200 characters after trimming', () => {
      expect(reasonProblem('abc', 'x')).toBe('x');
      expect(reasonProblem('   abc   ', 'x')).toBe('x');
      expect(reasonProblem('abcd', 'x')).toBeNull();
      expect(reasonProblem('a'.repeat(200), 'x')).toBeNull();
      expect(reasonProblem('a'.repeat(201), 'x')).toBe('x');
    });
  });
});
