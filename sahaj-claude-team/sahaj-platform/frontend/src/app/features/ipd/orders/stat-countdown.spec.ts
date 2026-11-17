import { statText, unacknowledgedText } from './stat-countdown';

const AS_OF = '2026-11-06T05:47:00Z';
const stat = (over = {}) => ({
  dueBy: '2026-11-06T05:56:00Z',
  windowMinutes: 15,
  minutesLeft: 9,
  minutesOverdue: null,
  ...over,
});

describe('statText (SAH-76-3, BR-3)', () => {
  it('says how many minutes are left, from the server time of the answer', () => {
    expect(statText(stat(), AS_OF, 0)).toEqual({
      text: 'STAT · give within 15 min · 9 min left',
      overdue: false,
    });
  });

  it('counts on by the time that passed since the answer, rounding a part minute up', () => {
    expect(statText(stat(), AS_OF, 3 * 60_000 + 10_000).text).toBe('STAT · give within 15 min · 6 min left');
  });

  it('turns red and says how long it is overdue once the due time has passed', () => {
    expect(statText(stat(), AS_OF, 15 * 60_000)).toEqual({ text: 'STAT · overdue by 6 min', overdue: true });
  });

  it('says overdue by at least 1 minute', () => {
    expect(statText(stat(), AS_OF, 9 * 60_000 + 5_000).text).toBe('STAT · overdue by 1 min');
  });

  it('uses the minutes the server sent when a time cannot be read', () => {
    expect(statText(stat({ dueBy: 'x' }), AS_OF, 0).text).toBe('STAT · give within 15 min · 9 min left');
    expect(statText(stat({ dueBy: 'x', minutesLeft: null, minutesOverdue: 6 }), AS_OF, 0)).toEqual({
      text: 'STAT · overdue by 6 min',
      overdue: true,
    });
  });

  it('never goes back in time for a negative stopwatch value', () => {
    expect(statText(stat(), AS_OF, -5000).text).toBe('STAT · give within 15 min · 9 min left');
  });
});

describe('unacknowledgedText', () => {
  it('says how long an order has waited', () => {
    expect(unacknowledgedText(35)).toBe('Not acknowledged for 35 min');
    expect(unacknowledgedText(null)).toBe('Not acknowledged for 0 min');
  });
});
