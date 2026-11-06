import { addDays, describeAction, describeEntity, formatDate, formatDayMonth, formatWeekdayDate, formatWhen, istToday } from './config-format';

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

  it('gives the PIN events fixed labels for the change history (ADR-010 D10)', () => {
    expect(describeAction('PLATFORM_PIN_SET')).toBe('PIN set');
    expect(describeAction('PLATFORM_PIN_CHANGED')).toBe('PIN changed');
    expect(describeAction('PLATFORM_PIN_RESET')).toBe('PIN reset by the owner');
    expect(describeAction('PLATFORM_PIN_LOCKED')).toBe('PIN locked after 5 wrong tries');
    expect(describeAction('PLATFORM_PIN_CLEARED_AFTER_LOCKS')).toBe('PIN switched off after too many wrong tries');
  });

  it('gives the medicine order events fixed labels', () => {
    expect(describeAction('IPD_MED_ORDERED')).toBe('Medicine ordered');
    expect(describeAction('IPD_MED_ORDER_BLOCKED')).toBe('Medicine order blocked');
    expect(describeAction('IPD_MED_STOPPED')).toBe('Medicine stopped');
    expect(describeAction('IPD_MED_VERIFIED')).toBe('Medicine verified');
    expect(describeAction('IPD_MED_REJECTED')).toBe('Medicine rejected');
  });

  it('names the entity types of the PIN and the medicine order, and leaves others alone', () => {
    expect(describeEntity('STAFF_PIN')).toBe('Staff PIN');
    expect(describeEntity('IPD_MEDICINE_ORDER')).toBe('Medicine order');
    expect(describeEntity('Service')).toBe('Service');
  });
});
