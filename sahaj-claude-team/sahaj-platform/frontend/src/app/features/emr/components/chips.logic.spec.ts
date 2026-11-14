import { chipText, toggleMany, toggleSingle } from './chips.logic';

const options = [{ key: 'a' }, { key: 'b' }, { key: 'none', isNone: true }];

describe('chip rows (SAH-75-6)', () => {
  it('single choice: pressing the chosen chip again clears it', () => {
    expect(toggleSingle(null, 'x')).toBe('x');
    expect(toggleSingle('x', 'y')).toBe('y');
    expect(toggleSingle('y', 'y')).toBeNull();
  });

  it('many choices: toggles a chip on and off', () => {
    expect(toggleMany(options, [], 'a')).toEqual(['a']);
    expect(toggleMany(options, ['a'], 'b')).toEqual(['a', 'b']);
    expect(toggleMany(options, ['a', 'b'], 'a')).toEqual(['b']);
  });

  it('"None" clears the others, and another chip clears "None"', () => {
    expect(toggleMany(options, ['a', 'b'], 'none')).toEqual(['none']);
    expect(toggleMany(options, ['none'], 'a')).toEqual(['a']);
  });

  it('shows "✓ " before a chosen chip', () => {
    expect(chipText('Sharp', true)).toBe('✓ Sharp');
    expect(chipText('Sharp', false)).toBe('Sharp');
  });
});
