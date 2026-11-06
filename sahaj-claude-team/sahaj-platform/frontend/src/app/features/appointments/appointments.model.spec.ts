import { BookingRules, bookingRuleChips } from './appointments.model';

const RULES: BookingRules = {
  closeBeforeHours: 1,
  remindBeforeHours: 2,
  noShowAfterMinutes: 15,
  followPriority: true,
  selfBook: true,
  overbook: true,
};

describe('bookingRuleChips', () => {
  it('lists every rule in the mockup order', () => {
    expect(bookingRuleChips(RULES)).toEqual([
      'Booking closes 1 h before',
      'WhatsApp reminder 2 h before',
      'No-show after 15 min',
      'Follow-ups get priority',
      'Patients can book on WhatsApp',
      'Double booking allowed',
    ]);
  });

  it('keeps a zero and states staff-only booking', () => {
    const chips = bookingRuleChips({ ...RULES, closeBeforeHours: 0, followPriority: false, selfBook: false, overbook: false });
    expect(chips).toEqual(['Booking closes 0 h before', 'WhatsApp reminder 2 h before', 'No-show after 15 min', 'Booking by staff only']);
  });

  it('never prints undefined or NaN when the server leaves a number out', () => {
    const chips = bookingRuleChips({ selfBook: false } as Partial<BookingRules>);
    expect(chips).toEqual(['Booking by staff only']);
    expect(bookingRuleChips({ closeBeforeHours: Number.NaN } as Partial<BookingRules>).join(' ')).not.toMatch(/undefined|NaN/);
  });
});
