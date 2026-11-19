import { IsoDateAdapter, ISO_DATE_FORMATS } from './iso-date-adapter';

describe('IsoDateAdapter (ISO value, "24 Oct 2026" display)', () => {
  const adapter = new IsoDateAdapter();

  it('shows a date as 24 Oct 2026, whatever the device locale or time zone', () => {
    expect(adapter.format('2026-10-24', ISO_DATE_FORMATS.display.dateInput)).toBe('24 Oct 2026');
    expect(adapter.format('2026-09-01', ISO_DATE_FORMATS.display.dateInput)).toBe('1 Sep 2026');
  });

  it('labels the calendar header and a day for screen readers', () => {
    expect(adapter.format('2026-10-24', ISO_DATE_FORMATS.display.monthYearLabel)).toBe('Oct 2026');
    expect(adapter.format('2026-10-24', ISO_DATE_FORMATS.display.monthYearA11yLabel)).toBe('October 2026');
    expect(adapter.format('2026-10-24', ISO_DATE_FORMATS.display.dateA11yLabel)).toBe(
      'Saturday, 24 October 2026',
    );
  });

  it('keeps the API format: parse and deserialize give YYYY-MM-DD back', () => {
    expect(adapter.parse('24 Oct 2026')).toBe('2026-10-24');
    expect(adapter.parse('24 october 2026')).toBe('2026-10-24');
    expect(adapter.parse('24/10/2026')).toBe('2026-10-24');
    expect(adapter.parse('2026-10-24')).toBe('2026-10-24');
    expect(adapter.deserialize('2026-10-24')).toBe('2026-10-24');
  });

  it('treats an empty value as no date and anything else as invalid', () => {
    expect(adapter.parse('')).toBeNull();
    expect(adapter.deserialize('')).toBeNull();
    expect(adapter.deserialize(null)).toBeNull();
    expect(adapter.isValid(adapter.parse('31 Feb 2026') as string)).toBe(false);
    expect(adapter.isValid(adapter.parse('not a date') as string)).toBe(false);
    expect(adapter.isValid(adapter.deserialize('2026-13-01') as string)).toBe(false);
  });

  it('does calendar arithmetic without a time zone', () => {
    expect(adapter.addCalendarDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(adapter.addCalendarMonths('2026-01-31', 1)).toBe('2026-02-28');
    expect(adapter.addCalendarMonths('2026-01-15', -2)).toBe('2025-11-15');
    expect(adapter.addCalendarYears('2024-02-29', 1)).toBe('2025-02-28');
    expect(adapter.getNumDaysInMonth('2028-02-10')).toBe(29);
    expect(adapter.getDayOfWeek('2026-10-24')).toBe(6);
  });

  it('creates dates from year, month index and day, and refuses impossible ones', () => {
    expect(adapter.createDate(2026, 9, 24)).toBe('2026-10-24');
    expect(() => adapter.createDate(2026, 1, 30)).toThrow();
    expect(() => adapter.createDate(2026, 12, 1)).toThrow();
  });
});
