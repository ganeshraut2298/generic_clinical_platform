import { plainSpaces } from './plain-space';

describe('plainSpaces', () => {
  it('turns U+202F and U+00A0 into a plain space', () => {
    expect(plainSpaces('11:05\u202FAM')).toBe('11:05 AM');
    expect(plainSpaces('11:05\u00A0PM')).toBe('11:05 PM');
  });

  it('leaves other text alone', () => {
    expect(plainSpaces('24 Oct 2026, 11:05 AM')).toBe('24 Oct 2026, 11:05 AM');
  });
});
