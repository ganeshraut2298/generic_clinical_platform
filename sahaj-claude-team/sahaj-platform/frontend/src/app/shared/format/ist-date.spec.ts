import { addDays, formatClockLabel, isoWeekday, istToday, nowMinutesIst } from './ist-date';

describe('ist-date', () => {
  it('formats today as YYYY-MM-DD', () => {
    expect(istToday(new Date('2026-10-24T05:00:00Z'))).toBe('2026-10-24');
  });

  it('adds days across a month boundary', () => {
    expect(addDays('2026-10-31', 1)).toBe('2026-11-01');
  });

  it('gives the weekday for a plain date, independent of time zone drift', () => {
    // 24 Oct 2026 is a Saturday.
    expect(isoWeekday('2026-10-24')).toBe(6);
  });

  it('reads the IST clock, not the local one', () => {
    // Midnight UTC is 5:30 AM IST.
    expect(nowMinutesIst(new Date('2026-10-24T00:00:00Z'))).toBe(5 * 60 + 30);
  });

  it('formats a 24-hour time as a 12-hour label', () => {
    expect(formatClockLabel('09:00')).toBe('9:00 AM');
    expect(formatClockLabel('00:00')).toBe('12:00 AM');
    expect(formatClockLabel('12:30')).toBe('12:30 PM');
    expect(formatClockLabel('18:05')).toBe('6:05 PM');
  });
});
