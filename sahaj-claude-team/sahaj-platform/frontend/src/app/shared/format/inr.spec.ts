import { formatInr, formatIndian } from './inr';

describe('Indian number format', () => {
  it('groups digits as lakhs and thousands', () => {
    expect(formatIndian(0)).toBe('0');
    expect(formatIndian(999)).toBe('999');
    expect(formatIndian(1999)).toBe('1,999');
    expect(formatIndian(125000)).toBe('1,25,000');
    expect(formatIndian(12345678)).toBe('1,23,45,678');
  });

  it('rounds to whole rupees and writes the rupee sign', () => {
    expect(formatInr(3998)).toBe('₹3,998');
    expect(formatInr(1234.6)).toBe('₹1,235');
    expect(formatInr(-4500)).toBe('₹-4,500');
  });
});
