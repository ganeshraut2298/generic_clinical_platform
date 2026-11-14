import {
  DOB_FUTURE,
  DOB_INVALID,
  DOB_TOO_OLD,
  ageText,
  completedYears,
  dateOfBirthProblem,
  parseIsoDate,
  patientAgeText,
  workOutAge,
} from './patient-age';

/** The test vectors of ADR-024 D1 (same cases as the Java `PatientAge` tests). */
describe('workOutAge', () => {
  const unitAndValue = (dob: string, asOf: string) => {
    const age = workOutAge(dob, asOf);
    return age ? `${age.value} ${age.unit}` : null;
  };

  it('works out 17 months for 10 Jun 2025 on 13 Nov 2026', () => {
    expect(unitAndValue('2025-06-10', '2026-11-13')).toBe('17 MONTHS');
  });

  it('shows a 34 year old in years', () => {
    expect(unitAndValue('1992-05-01', '2026-11-13')).toBe('34 YEARS');
  });

  it('moves a 29 Feb 2020 birthday to 1 Mar in other years', () => {
    expect(completedYears('2020-02-29', '2027-02-28')).toBe(6);
    expect(completedYears('2020-02-29', '2027-03-01')).toBe(7);
    expect(completedYears('2020-02-29', '2028-02-29')).toBe(8);
    expect(completedYears('2020-02-29', '2028-02-28')).toBe(7);
  });

  it('switches to years on the second birthday and not a day before', () => {
    expect(unitAndValue('2024-11-13', '2026-11-13')).toBe('2 YEARS');
    expect(unitAndValue('2024-11-13', '2026-11-12')).toBe('23 MONTHS');
  });

  it('switches to months at 3 completed months and not a day before', () => {
    expect(unitAndValue('2026-08-13', '2026-11-13')).toBe('3 MONTHS');
    expect(unitAndValue('2026-08-14', '2026-11-13')).toBe('13 WEEKS');
  });

  it('switches to weeks at 14 days and not a day before', () => {
    expect(unitAndValue('2026-10-30', '2026-11-13')).toBe('2 WEEKS');
    expect(unitAndValue('2026-10-31', '2026-11-13')).toBe('13 DAYS');
    expect(unitAndValue('2026-10-03', '2026-11-13')).toBe('5 WEEKS');
  });

  it('counts a baby born today as 0 days', () => {
    expect(unitAndValue('2026-11-13', '2026-11-13')).toBe('0 DAYS');
  });

  it('gives months, days after months and total days for Paediatrics', () => {
    const age = workOutAge('2025-05-01', '2026-11-13')!;
    expect(age.totalMonths).toBe(18);
    expect(age.daysAfterMonths).toBe(12);
    expect(age.totalDays).toBe(561);
    expect(age.years).toBe(1);
  });

  it('cuts a month end back like Java plusMonths (31 Jan, then 28 Feb)', () => {
    const age = workOutAge('2026-01-31', '2026-03-01')!;
    expect(age.totalMonths).toBe(1);
    expect(age.daysAfterMonths).toBe(1);
  });

  it('returns null for a future date or a date that does not exist', () => {
    expect(workOutAge('2026-11-14', '2026-11-13')).toBeNull();
    expect(workOutAge('2026-02-31', '2026-11-13')).toBeNull();
  });
});

describe('parseIsoDate', () => {
  it('accepts a real date only', () => {
    expect(parseIsoDate('2024-02-29')).toEqual({ y: 2024, m: 2, d: 29 });
    expect(parseIsoDate('2025-02-29')).toBeNull();
    expect(parseIsoDate('2026-13-01')).toBeNull();
    expect(parseIsoDate('13/11/2026')).toBeNull();
    expect(parseIsoDate('20255-06-10')).toBeNull();
    expect(parseIsoDate('')).toBeNull();
  });
});

describe('dateOfBirthProblem', () => {
  const today = '2026-11-13';

  it('accepts an empty value, today and a date in the past', () => {
    expect(dateOfBirthProblem('', today)).toBeNull();
    expect(dateOfBirthProblem(null, today)).toBeNull();
    expect(dateOfBirthProblem('2026-11-13', today)).toBeNull();
    expect(dateOfBirthProblem('2025-06-10', today)).toBeNull();
  });

  it('refuses a date that does not exist or is not a date', () => {
    expect(dateOfBirthProblem('2026-02-31', today)).toBe(DOB_INVALID);
    expect(dateOfBirthProblem('abc', today)).toBe(DOB_INVALID);
    expect(DOB_INVALID).toBe('Enter a valid date of birth.');
  });

  it('refuses a future date', () => {
    expect(dateOfBirthProblem('2026-11-14', today)).toBe(DOB_FUTURE);
    expect(DOB_FUTURE).toBe('Date of birth cannot be in the future.');
  });

  it('refuses more than 120 completed years and accepts exactly 120', () => {
    expect(dateOfBirthProblem('1906-11-13', today)).toBeNull();
    expect(dateOfBirthProblem('1905-11-13', today)).toBe(DOB_TOO_OLD);
    expect(DOB_TOO_OLD).toBe('Date of birth must be within the last 120 years.');
  });

  it('never repeats the date in the message', () => {
    for (const value of ['2026-02-31', '2030-01-01', '1800-01-01']) {
      expect(dateOfBirthProblem(value, today)).not.toContain(value.slice(0, 4));
    }
  });
});

describe('age text', () => {
  it('writes years as "34" in rows and "34 yrs" in headers', () => {
    expect(ageText({ value: 34, unit: 'YEARS' }, 'row')).toBe('34');
    expect(ageText({ value: 34, unit: 'YEARS' }, 'header')).toBe('34 yrs');
  });

  it('writes small children the same in rows and headers', () => {
    expect(ageText({ value: 18, unit: 'MONTHS' }, 'row')).toBe('18 months');
    expect(ageText({ value: 10, unit: 'WEEKS' }, 'header')).toBe('10 weeks');
    expect(ageText({ value: 9, unit: 'DAYS' }, 'row')).toBe('9 days');
    expect(ageText({ value: 1, unit: 'DAYS' }, 'row')).toBe('1 day');
    expect(ageText({ value: 0, unit: 'DAYS' }, 'row')).toBe('0 days');
  });

  it('falls back to the whole-number age when the server sent no detail', () => {
    expect(patientAgeText({ age: 30 }, 'row')).toBe('30');
    expect(patientAgeText({ age: 30 }, 'header')).toBe('30 yrs');
    expect(patientAgeText({ age: 1, ageDetail: { value: 18, unit: 'MONTHS' } }, 'row')).toBe('18 months');
  });
});
