import {
  affectedText,
  dayMonth,
  inAtText,
  leaveRange,
  nextShift,
  weekdayShort,
} from './staff-display.util';

describe('staff display helpers', () => {
  it('cycles Morning, Evening, Night, Off, Morning', () => {
    expect(nextShift('M')).toBe('E');
    expect(nextShift('E')).toBe('N');
    expect(nextShift('N')).toBe('X');
    expect(nextShift('X')).toBe('M');
    expect(nextShift(null)).toBe('M');
  });

  it('formats days and the in-at text', () => {
    expect(weekdayShort('2026-11-05')).toBe('Thu');
    expect(dayMonth('2026-11-05')).toBe('5 Nov');
    expect(inAtText('08:52')).toBe('in at 8:52 AM');
    expect(inAtText(null)).toBe('');
  });

  it('formats leave ranges and the appointment warning', () => {
    expect(leaveRange('2026-11-02', '2026-11-02')).toBe('2 Nov 2026');
    expect(leaveRange('2026-11-13', '2026-11-15')).toBe('13 Nov 2026 to 15 Nov 2026');
    expect(affectedText(1)).toBe('1 booked appointment on that day');
    expect(affectedText(3)).toBe('3 booked appointments on those days');
  });
});
