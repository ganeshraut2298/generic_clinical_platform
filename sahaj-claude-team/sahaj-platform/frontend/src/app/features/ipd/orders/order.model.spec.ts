import { DETAILS } from '../emr/emr.fixtures';
import {
  changeLineText,
  clock,
  dayAndTime,
  detailsLine,
  doseText,
  durationText,
  executionLines,
  intervalText,
} from './order.model';

const EXEC = {
  notGivenToday: 0,
  nextDueAt: null,
  lateCount: 0,
  acknowledged: false,
  acknowledgedByName: null,
  acknowledgedAt: null,
};

describe('order.model (SAH-76)', () => {
  describe('times in IST', () => {
    it('writes a start as "24 Oct, 10:00 AM" and a clock as "11:05 AM"', () => {
      expect(dayAndTime('2026-10-24T04:30:00Z')).toBe('24 Oct, 10:00 AM');
      expect(clock('2026-10-24T05:35:00Z')).toBe('11:05 AM');
      expect(dayAndTime('2026-10-24T18:30:00Z')).toBe('25 Oct, 12:00 AM');
      expect(dayAndTime('2026-10-24T06:30:00Z')).toBe('24 Oct, 12:00 PM');
    });
    it('is empty for a missing or bad instant', () => {
      expect(dayAndTime(null)).toBe('');
      expect(clock('nope')).toBe('');
    });
  });

  describe('details line', () => {
    it('writes dose, route, frequency, start and duration', () => {
      expect(detailsLine(DETAILS)).toBe('1 tablet · Oral · Once daily · from 6 Nov, 10:00 AM · Until stopped');
      expect(detailsLine({ ...DETAILS, durationDays: 5 })).toBe(
        '1 tablet · Oral · Once daily · from 6 Nov, 10:00 AM · for 5 days',
      );
      expect(durationText({ kind: 'SCHEDULED', durationDays: 1 })).toBe('for 1 day');
    });
    it('writes STAT with no duration', () => {
      expect(
        detailsLine({ ...DETAILS, kind: 'ONCE', priority: 'STAT', frequencyKey: null, frequencyLabel: 'STAT' }),
      ).toBe('1 tablet · Oral · STAT · from 6 Nov, 10:00 AM');
    });
    it('says "Dose as in the medicine name" for a built order with no amount', () => {
      expect(doseText({ doseAmount: null, doseUnit: null })).toBe('Dose as in the medicine name');
      expect(doseText({ doseAmount: 650, doseUnit: 'MG' })).toBe('650 mg');
      expect(doseText({ doseAmount: 2, doseUnit: 'UNIT' })).toBe('2 units');
    });
    it('writes an interval in minutes or hours', () => {
      expect(intervalText(360)).toBe('6 h');
      expect(intervalText(45)).toBe('45 min');
      expect(intervalText(90)).toBe('1 h 30 min');
      expect(intervalText(null)).toBe('');
    });
    it('writes a change line', () => {
      expect(changeLineText({ field: 'dose', label: 'Dose', before: '1 tablet', after: '2 tablet' })).toBe(
        'Changed: Dose: 1 tablet → 2 tablet',
      );
    });
  });

  describe('execution lines (SAH-76-5)', () => {
    const given = {
      outcome: 'GIVEN' as const,
      reason: null,
      recordedAt: '2026-11-06T05:35:00Z',
      recordedByName: 'Sr. Anjali Pawar',
    };
    it('says "Not acknowledged yet" and nothing else for an order nobody has touched', () => {
      expect(executionLines(EXEC, null, 'SCHEDULED').map((l) => l.text)).toEqual(['Not acknowledged yet']);
    });
    it('puts late before next due, and shows no next due for PRN', () => {
      const ex = { ...EXEC, nextDueAt: '2026-11-06T08:30:00Z' };
      expect(executionLines(ex, given, 'SCHEDULED').map((l) => l.text)).toEqual([
        'Last dose: Given 11:05 AM by Sr. Anjali Pawar',
        'Next due 2:00 PM',
        'Not acknowledged yet',
      ]);
      expect(executionLines({ ...ex, lateCount: 1 }, given, 'SCHEDULED').map((l) => l.text)).toContain('1 late');
      expect(executionLines(ex, given, 'PRN').map((l) => l.text)).not.toContain('Next due 2:00 PM');
    });
    it('shows "N late" instead of "Next due" and no next due when it is null', () => {
      const late = { ...EXEC, lateCount: 2, nextDueAt: '2026-11-06T08:30:00Z' };
      expect(executionLines(late, given, 'SCHEDULED').map((l) => l.key)).toEqual(['last', 'late', 'ack']);
      expect(executionLines({ ...EXEC, nextDueAt: null }, given, 'SCHEDULED').map((l) => l.key)).toEqual(['last', 'ack']);
      expect(executionLines({ ...late, lateCount: 0 }, null, 'SCHEDULED').map((l) => l.text)).toEqual([
        'Next due 2:00 PM',
        'Not acknowledged yet',
      ]);
    });
    it('is a pure function of the server fields: the device clock changes nothing', () => {
      const ex = { ...EXEC, nextDueAt: '2026-11-06T08:30:00Z' };
      vi.useFakeTimers({ toFake: ['Date'] });
      try {
        vi.setSystemTime(new Date('2031-03-01T12:00:00Z'));
        const a = executionLines(ex, given, 'SCHEDULED');
        vi.setSystemTime(new Date('2020-01-01T00:00:00Z'));
        expect(executionLines(ex, given, 'SCHEDULED')).toEqual(a);
        expect(a.find((l) => l.key === 'next')).toEqual({ key: 'next', text: 'Next due 2:00 PM', warn: false });
      } finally {
        vi.useRealTimers();
      }
    });
    it('shows a refused dose as Not given in amber', () => {
      const refused = { ...given, outcome: 'REFUSED' as const, reason: { label: 'Patient refused' } };
      const [first] = executionLines(EXEC, refused, 'SCHEDULED');
      expect(first).toEqual({
        key: 'last',
        text: 'Not given · Patient refused · 11:05 AM · Sr. Anjali Pawar',
        warn: true,
      });
    });
    it('says who acknowledged and when', () => {
      const ex = {
        ...EXEC,
        acknowledged: true,
        acknowledgedByName: 'Sr. Anjali Pawar',
        acknowledgedAt: '2026-11-06T05:40:00Z',
      };
      expect(executionLines(ex, null, 'SCHEDULED').map((l) => l.text)).toEqual([
        'Acknowledged by Sr. Anjali Pawar at 11:10 AM',
      ]);
    });
  });
});
