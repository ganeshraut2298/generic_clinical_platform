import { formatIstDateTime } from './ist-clock';

describe('formatIstDateTime', () => {
  it('shows the India date and an upper-case AM or PM with a plain space', () => {
    // 05:35 UTC is 11:05 in India
    expect(formatIstDateTime(new Date('2026-10-24T05:35:00Z'))).toBe('Sat, 24 Oct 2026 · 11:05 AM');
    // 13:40 UTC is 7:10 PM in India
    expect(formatIstDateTime(new Date('2026-10-24T13:40:00Z'))).toBe('Sat, 24 Oct 2026 · 7:10 PM');
  });

  it('uses the India date even when the UTC date is the day before', () => {
    // 19:00 UTC on 24 Oct is 12:30 AM on 25 Oct in India
    expect(formatIstDateTime(new Date('2026-10-24T19:00:00Z'))).toBe('Sun, 25 Oct 2026 · 12:30 AM');
  });
});
