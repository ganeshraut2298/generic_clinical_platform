import { slugify } from './slugify';

describe('slugify', () => {
  it('drops common clinic words and keeps lowercase letters and digits', () => {
    expect(slugify('Shree Sai Family Clinic')).toBe('shreesai');
  });

  it('falls back to myclinic when nothing is left', () => {
    expect(slugify('Clinic')).toBe('myclinic');
    expect(slugify('')).toBe('myclinic');
  });

  it('caps the result at 20 characters', () => {
    expect(slugify('A'.repeat(40)).length).toBeLessThanOrEqual(20);
  });
});
