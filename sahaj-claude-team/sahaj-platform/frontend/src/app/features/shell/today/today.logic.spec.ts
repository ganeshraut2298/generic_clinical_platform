import { greetingFor, greetingName, initialsOf, plural } from './today.logic';

describe('today.logic', () => {
  it('greets by India hour', () => {
    // 04:30 UTC = 10:00 IST
    expect(greetingFor(new Date('2026-11-04T04:30:00Z'))).toBe('Good morning');
    // 08:30 UTC = 14:00 IST
    expect(greetingFor(new Date('2026-11-04T08:30:00Z'))).toBe('Good afternoon');
    // 13:00 UTC = 18:30 IST
    expect(greetingFor(new Date('2026-11-04T13:00:00Z'))).toBe('Good evening');
    // 19:00 UTC on the 3rd = 00:30 IST on the 4th
    expect(greetingFor(new Date('2026-11-03T19:00:00Z'))).toBe('Good morning');
  });

  it('picks the first name and keeps Dr with the next word', () => {
    expect(greetingName('Asha Patil')).toBe('Asha');
    expect(greetingName('Dr. Meera Rao')).toBe('Dr. Meera');
    expect(greetingName('')).toBe('');
    expect(greetingName(undefined)).toBe('');
  });

  it('drops the placeholder owner name but keeps a real name', () => {
    expect(greetingName('Owner')).toBe('');
    expect(greetingName('  owner ')).toBe('');
    expect(greetingName('Owner Patil')).toBe('Owner');
    expect(greetingName('Dr. Owner')).toBe('Dr. Owner');
  });

  it('builds initials and plurals', () => {
    expect(initialsOf('Dr. Meera Rao')).toBe('MR');
    expect(initialsOf('Kiran')).toBe('K');
    expect(plural(1, 'visit', 'visits')).toBe('1 visit');
    expect(plural(3, 'visit', 'visits')).toBe('3 visits');
  });
});
