// SAH-39 review finding 1: the home hero panel (.surface-glass) must stay dark in the dark theme,
// so the light text on it stays readable. The theme file is read as text because the unit-test
// runner does not load global styles.

export {};

interface NodeProcess {
  cwd(): string;
  getBuiltinModule(id: string): unknown;
}
interface NodeFs {
  readFileSync(path: string, encoding: 'utf8'): string;
}

const nodeProcess = (globalThis as unknown as { process: NodeProcess }).process;
const fs = nodeProcess.getBuiltinModule('node:fs') as NodeFs;
const scss = fs.readFileSync(`${nodeProcess.cwd().replace(/\\/g, '/')}/src/styles/_tokens.scss`, 'utf8');

function block(selector: string): string {
  const start = scss.indexOf(`${selector} {`);
  expect(start).toBeGreaterThanOrEqual(0);
  return scss.slice(start, scss.indexOf('\n}', start));
}

function token(css: string, name: string): string {
  const match = new RegExp(`${name}:\\s*([^;]+);`).exec(css);
  expect(match).not.toBeNull();
  return (match as RegExpExecArray)[1].trim();
}

type Rgba = [number, number, number, number];

function parse(value: string): Rgba {
  const hex = /^#([0-9a-f]{6})$/i.exec(value);
  if (hex) {
    const n = parseInt(hex[1], 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 1];
  }
  const rgba = /^rgba?\(([^)]+)\)$/.exec(value);
  expect(rgba).not.toBeNull();
  const parts = (rgba as RegExpExecArray)[1].split(',').map((p) => parseFloat(p));
  return [parts[0], parts[1], parts[2], parts[3] ?? 1];
}

function over(top: Rgba, base: Rgba): Rgba {
  const a = top[3];
  return [0, 1, 2].map((i) => top[i] * a + base[i] * (1 - a)).concat(1) as Rgba;
}

function luminance(c: Rgba): number {
  const [r, g, b] = [0, 1, 2].map((i) => {
    const v = c[i] / 255;
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: Rgba, b: Rgba): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

describe('dark theme home hero panel (--glass-surface)', () => {
  const light = block(':root');
  const dark = block(":root[data-theme='dark']");

  it('has a light value and its own dark value', () => {
    expect(token(light, '--glass-surface')).toBe('rgba(255, 255, 255, 0.85)');
    expect(token(dark, '--glass-surface')).not.toBe(token(light, '--glass-surface'));
  });

  it('resolves to a dark colour over the dark page background', () => {
    const panel = over(parse(token(dark, '--glass-surface')), parse(token(dark, '--bg')));
    expect(luminance(panel)).toBeLessThan(0.05);
  });

  it.each(['--ink', '--ink-2', '--pri-text'])('keeps %s at least 4.5:1 on the dark panel', (name) => {
    const panel = over(parse(token(dark, '--glass-surface')), parse(token(dark, '--bg')));
    expect(contrast(parse(token(dark, name)), panel)).toBeGreaterThanOrEqual(4.5);
  });
});
