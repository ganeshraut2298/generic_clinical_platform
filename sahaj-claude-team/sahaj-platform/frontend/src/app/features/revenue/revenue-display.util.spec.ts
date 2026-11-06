import { addDays, istToday } from '../../shared/format/ist-date';
import { changeText, dayBars, doctorRows, modeRows, revenueLead, rupees, serviceRows } from './revenue-display.util';

describe('revenue display util', () => {
  it('writes the lead for each range', () => {
    expect(revenueLead('TODAY')).toBe('Everything collected, billed and still due today, in one place.');
    expect(revenueLead('WEEK')).toContain('in the last 7 days');
    expect(revenueLead('MONTH')).toContain('in the last 30 days');
  });

  it('formats paise as rupees with the shared formatter', () => {
    expect(rupees(12500000)).toBe('₹1,25,000');
    expect(rupees(0)).toBe('₹0');
  });

  it('shows arrows for change and nothing when null', () => {
    expect(changeText(12)).toBe('▲ 12%');
    expect(changeText(-5)).toBe('▼ 5%');
    expect(changeText(0)).toBe('0%');
    expect(changeText(null)).toBeNull();
    expect(changeText(undefined)).toBeNull();
  });

  it('computes mode percentages in Cash, UPI, Card order', () => {
    const rows = modeRows([
      { mode: 'UPI', amountPaise: 5000 },
      { mode: 'CASH', amountPaise: 3000 },
    ]);
    expect(rows.map((r) => [r.label, r.percent])).toEqual([
      ['Cash', 38],
      ['UPI', 63],
      ['Card', 0],
    ]);
  });

  it('handles an all-zero mode list', () => {
    expect(modeRows([]).every((r) => r.percent === 0 && r.widthPercent === 0)).toBe(true);
  });

  it('computes doctor average and share', () => {
    const rows = doctorRows([
      { doctorMembershipId: 'a', doctorName: 'Dr. A', patients: 4, collectedPaise: 30000 },
      { doctorMembershipId: 'b', doctorName: 'Dr. B', patients: 0, collectedPaise: 10000 },
    ]);
    expect(rows[0].averagePaise).toBe(7500);
    expect(rows[0].sharePercent).toBe(75);
    expect(rows[1].averagePaise).toBe(0);
  });

  it('scales service bars to the largest', () => {
    const rows = serviceRows([
      { name: 'Consultation', billedPaise: 20000 },
      { name: 'ECG', billedPaise: 5000 },
    ]);
    expect(rows.map((r) => r.widthPercent)).toEqual([100, 25]);
  });

  it('makes one bar per day, flags today and titles each bar', () => {
    const today = istToday();
    const days = [-2, -1, 0].map((n) => ({ date: addDays(today, n), collectedPaise: n === -1 ? 0 : 10000 }));
    const bars = dayBars(days, today);
    expect(bars.length).toBe(3);
    expect(bars.map((b) => b.isToday)).toEqual([false, false, true]);
    expect(bars[2].label).toBe('Today');
    expect(bars[2].title).toBe('Today: ₹100 collected');
    expect(bars[1].title).toContain('₹0 collected');
    expect(bars[1].height).toBeLessThan(bars[0].height);
  });

  it('labels every fifth bar on a long range', () => {
    const today = istToday();
    const days = Array.from({ length: 30 }, (_, i) => ({ date: addDays(today, i - 29), collectedPaise: 100 }));
    const shown = dayBars(days, today).filter((b) => b.showLabel).length;
    expect(shown).toBe(6);
  });
});
