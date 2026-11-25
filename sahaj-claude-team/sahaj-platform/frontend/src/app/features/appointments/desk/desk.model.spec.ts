import { DEFAULT_BOOKING_RULES } from '../appointments.model';
import {
  ALL_DEPARTMENTS,
  DeskBoardCard,
  columnCards,
  deskRuleChips,
  departmentOptions,
  doctorFeeLine,
  doctorInitials,
  doctorStatusText,
  filterBoardCards,
  filterRailDoctors,
  followUpTagText,
  headerDateTime,
  nextFreeText,
  noSlotsMessage,
  shortDoctorName,
  stripDayLabel,
} from './desk.model';
import { makeCard, makeDoctor } from './desk.testing';

describe('desk model helpers', () => {
  it('makes short names and initials', () => {
    expect(shortDoctorName('Dr. Meera Kulkarni')).toBe('Dr. Meera K.');
    expect(shortDoctorName('Dr. Meera')).toBe('Dr. Meera');
    expect(doctorInitials('Dr. Meera Kulkarni')).toBe('MK');
  });

  it('words the status as text', () => {
    expect(doctorStatusText(makeDoctor())).toBe('In OPD');
    expect(doctorStatusText(makeDoctor({ todayStatus: 'STARTS_LATER', startsAt: '17:00' }))).toBe('From 5:00 PM');
    expect(doctorStatusText(makeDoctor({ todayStatus: 'OFF_TODAY' }))).toBe('Off today');
    expect(doctorStatusText(makeDoctor({ todayStatus: 'FULL_TODAY' }))).toBe('Full today');
    expect(doctorStatusText(makeDoctor({ todayStatus: 'ON_LEAVE', leaveUntil: '2026-11-26' }))).toBe('On leave till Thu');
  });

  it('words the next free slot', () => {
    expect(nextFreeText(makeDoctor(), '2026-11-24')).toBe('Today · 7 free');
    expect(nextFreeText(makeDoctor({ nextFree: { date: '2026-11-25', freeCount: 8 } }), '2026-11-24')).toBe('Wed 25 · 8 free');
    expect(nextFreeText(makeDoctor({ nextFree: null }), '2026-11-24')).toBe('No slots');
  });

  it('shows the fee line from the contract paise', () => {
    expect(doctorFeeLine(makeDoctor())).toBe('Consultation ₹400 · Follow-up ₹200');
    expect(doctorFeeLine(makeDoctor({ followUpFeePaise: 0 }))).toBe('Consultation ₹400 · Follow-up Free');
  });

  it('lists each department once, sorted, after All departments', () => {
    const doctors = [
      makeDoctor({ department: 'Pediatrics' }),
      makeDoctor({ department: 'General medicine' }),
      makeDoctor({ department: 'Pediatrics' }),
      makeDoctor({ department: null }),
    ];
    expect(departmentOptions(doctors)).toEqual([ALL_DEPARTMENTS, 'General medicine', 'Pediatrics']);
  });

  it('filters the rail by department and search, but a search with no match does not narrow', () => {
    const doctors = [
      makeDoctor({ doctorMembershipId: 'a', name: 'Dr. Asha Rao', department: 'Pediatrics' }),
      makeDoctor({ doctorMembershipId: 'b', name: 'Dr. Meera Kulkarni', department: 'General medicine' }),
      makeDoctor({ doctorMembershipId: 'c', name: 'Dr. No Dept', department: null }),
    ];
    expect(filterRailDoctors(doctors, 'Pediatrics', '').map((d) => d.doctorMembershipId)).toEqual(['a']);
    expect(filterRailDoctors(doctors, ALL_DEPARTMENTS, 'meera').map((d) => d.doctorMembershipId)).toEqual(['b']);
    expect(filterRailDoctors(doctors, ALL_DEPARTMENTS, 'pedia').map((d) => d.doctorMembershipId)).toEqual(['a']);
    expect(filterRailDoctors(doctors, ALL_DEPARTMENTS, 'zzz')).toHaveLength(3);
    expect(filterRailDoctors(doctors, ALL_DEPARTMENTS, '')).toHaveLength(3);
  });

  it('filters board cards by scope and patient, a search with no match does not narrow', () => {
    const cards = [
      makeCard({ key: '1', patientName: 'Rekha Shinde', doctorMembershipId: 'd1' }),
      makeCard({ key: '2', patientName: 'Mahesh Gaikwad', doctorMembershipId: 'd2' }),
    ];
    expect(filterBoardCards(cards, 'all', 'd1', '')).toHaveLength(2);
    expect(filterBoardCards(cards, 'selected', 'd1', '').map((c) => c.key)).toEqual(['1']);
    expect(filterBoardCards(cards, 'all', null, 'mahesh').map((c) => c.key)).toEqual(['2']);
    expect(filterBoardCards(cards, 'all', null, 'nobody')).toHaveLength(2);
  });

  it('orders a column by time, earliest first', () => {
    const cards = [makeCard({ key: 'b', time: '11:00' }), makeCard({ key: 'a', time: '09:15' }), makeCard({ key: 'x', column: 'DONE' })];
    expect(columnCards(cards, 'BOOKED').map((c) => c.key)).toEqual(['a', 'b']);
  });

  it('words the follow-up tag on a Done card', () => {
    const done = (followUp: DeskBoardCard['followUp']) => makeCard({ column: 'DONE', followUp });
    expect(
      followUpTagText(
        done({ followUpId: 'f', state: 'BOOKED', dueDate: null, appointmentId: 'a', apptDate: '2026-12-01', apptTime: '10:30' }),
      ),
    ).toBe('Follow-up 1 Dec 2026 · 10:30 AM');
    expect(
      followUpTagText(done({ followUpId: 'f', state: 'NOT_NEEDED', dueDate: null, appointmentId: null, apptDate: null, apptTime: null })),
    ).toBe('No follow-up needed');
    expect(
      followUpTagText(done({ followUpId: null, state: 'NONE', dueDate: null, appointmentId: null, apptDate: null, apptTime: null })),
    ).toBeNull();
  });

  it('marks the reminder and self-booking chips as disabled and keeps the saved values', () => {
    const chips = deskRuleChips({ ...DEFAULT_BOOKING_RULES, closeBeforeHours: 3, noShowAfterMinutes: 20, followPriority: true, overbook: true });
    const byLabel = new Map(chips.map((c) => [c.label, c.disabled]));
    expect(byLabel.get('Booking closes 3 h before')).toBe(false);
    expect(byLabel.get('No-show after 20 min')).toBe(false);
    expect(byLabel.get('Follow-ups get priority')).toBe(false);
    expect(byLabel.get('Double booking allowed')).toBe(false);
    expect(byLabel.get('WhatsApp reminder 2 h before')).toBe(true);
    expect(byLabel.get('Booking by staff only')).toBe(true);
  });

  it('writes dates and the no-slots message', () => {
    expect(stripDayLabel('2026-11-24', '2026-11-24')).toBe('Today');
    expect(stripDayLabel('2026-11-25', '2026-11-24')).toBe('Wed 25');
    expect(headerDateTime('2026-11-24', '11:05 AM')).toBe('Tue, 24 Nov 2026 · 11:05 AM');
    expect(noSlotsMessage('2026-11-24', '2026-11-25')).toBe('No slots on Tue, 24 Nov. Next free day: Wed, 25 Nov.');
    expect(noSlotsMessage('2026-11-24', null)).toBe('No slots on Tue, 24 Nov or later this week.');
  });
});
