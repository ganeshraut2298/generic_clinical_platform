import { paiseToRupees, parseMultiplier, parseRupees, rupeesToPaise } from './ipd.model';

describe('ipd model helpers', () => {
  it('converts between paise and whole rupees', () => {
    expect(paiseToRupees(150000)).toBe(1500);
    expect(rupeesToPaise(1500)).toBe(150000);
  });

  it('accepts whole rupees from 0 to 1,00,000 only', () => {
    expect(parseRupees('0')).toBe(0);
    expect(parseRupees(' 800 ')).toBe(800);
    expect(parseRupees('100000')).toBe(100000);
    expect(parseRupees('100001')).toBeNull();
    expect(parseRupees('')).toBeNull();
    expect(parseRupees('-5')).toBeNull();
    expect(parseRupees('12.5')).toBeNull();
    expect(parseRupees('abc')).toBeNull();
  });

  it('accepts a multiplier from 0.5 to 5 with up to two decimals', () => {
    expect(parseMultiplier('0.5')).toBe(0.5);
    expect(parseMultiplier('1.25')).toBe(1.25);
    expect(parseMultiplier('5')).toBe(5);
    expect(parseMultiplier('0.49')).toBeNull();
    expect(parseMultiplier('5.01')).toBeNull();
    expect(parseMultiplier('1.234')).toBeNull();
    expect(parseMultiplier('x')).toBeNull();
    expect(parseMultiplier('')).toBeNull();
  });
});
