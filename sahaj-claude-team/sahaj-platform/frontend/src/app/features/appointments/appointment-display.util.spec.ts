import { appointmentDisplayPillClass, appointmentDisplayStatus } from './appointment-display.util';

describe('appointmentDisplayStatus', () => {
  const noShowAfterMinutes = 15;

  it('passes through non-BOOKED statuses unchanged', () => {
    expect(appointmentDisplayStatus('ARRIVED', '09:00', true, noShowAfterMinutes, 540)).toBe('ARRIVED');
    expect(appointmentDisplayStatus('NOSHOW', '09:00', true, noShowAfterMinutes, 540)).toBe('NOSHOW');
    expect(appointmentDisplayStatus('CANCELLED', '09:00', true, noShowAfterMinutes, 540)).toBe('CANCELLED');
  });

  it('never splits BOOKED into Due/Late for a day other than today', () => {
    expect(appointmentDisplayStatus('BOOKED', '09:00', false, noShowAfterMinutes, 540)).toBe('BOOKED');
  });

  it('stays BOOKED well before the slot time', () => {
    // Slot at 10:00, now 09:00: 60 minutes away.
    expect(appointmentDisplayStatus('BOOKED', '10:00', true, noShowAfterMinutes, 540)).toBe('BOOKED');
  });

  it('becomes DUE within 15 minutes of the slot, before or after it starts', () => {
    // Slot at 09:00, now 09:10: 10 minutes late, inside the no-show window.
    expect(appointmentDisplayStatus('BOOKED', '09:00', true, noShowAfterMinutes, 550)).toBe('DUE');
  });

  it('becomes LATE once the no-show window has passed', () => {
    // Slot at 09:00, now 09:20: 20 minutes late, past the 15-minute no-show window.
    expect(appointmentDisplayStatus('BOOKED', '09:00', true, noShowAfterMinutes, 560)).toBe('LATE');
  });
});

describe('appointmentDisplayPillClass', () => {
  it('maps each display status to an existing pill colour class', () => {
    expect(appointmentDisplayPillClass('ARRIVED')).toBe('p-good');
    expect(appointmentDisplayPillClass('LATE')).toBe('p-crit');
    expect(appointmentDisplayPillClass('NOSHOW')).toBe('p-crit');
    expect(appointmentDisplayPillClass('DUE')).toBe('p-warn');
    expect(appointmentDisplayPillClass('CANCELLED')).toBe('p-wait');
    expect(appointmentDisplayPillClass('BOOKED')).toBe('p-sec');
  });
});
