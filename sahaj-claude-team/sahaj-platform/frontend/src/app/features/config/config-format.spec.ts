import { addDays, describeAction, formatDate, formatDayMonth, formatWeekdayDate, formatWhen, istToday } from './config-format';

describe('config-format', () => {
  it('gives today in India, not in UTC', () => {
    // 11:00 PM UTC on 24 Oct is already 25 Oct in India
    expect(istToday(new Date('2026-10-24T23:00:00Z'))).toBe('2026-10-25');
  });

  it('adds days across a month end', () => {
    expect(addDays('2026-10-30', 3)).toBe('2026-11-02');
  });

  it('formats dates the Sahaj way', () => {
    expect(formatDate('2026-10-24')).toBe('24 Oct 2026');
    expect(formatDayMonth('2026-10-24')).toBe('24 Oct');
    expect(formatWeekdayDate('2026-10-24')).toBe('Sat 24 Oct');
    expect(formatWhen('2026-10-24T05:35:00Z')).toBe('24 Oct 2026, 11:05 AM');
    expect(formatWhen(null)).toBe('');
  });

  it('turns an action code into a sentence and leaves a sentence alone', () => {
    expect(describeAction('clinic.profile_updated')).toBe('Clinic profile updated');
    expect(describeAction('Clinic logo removed')).toBe('Clinic logo removed');
  });
});
