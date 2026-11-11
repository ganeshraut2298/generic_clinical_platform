// SAH-37 release audit (DEC-032), D-17 and D-18. The unit-test runner does not do layout, so the style files
// are read as text; the measured widths are in the e2e run (sah37-audit-regression, layout-matrix).

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
const root = nodeProcess.cwd().replace(/\\/g, '/');
const read = (path: string): string => fs.readFileSync(`${root}/${path}`, 'utf8');

function block(scss: string, selector: string): string {
  const start = scss.indexOf(`${selector} {`);
  expect(start, `${selector} is in the file`).toBeGreaterThanOrEqual(0);
  return scss.slice(start, scss.indexOf('\n}', start));
}

describe('SAH-37 D-17: a card value wraps in every layout', () => {
  const scss = read('src/app/features/ipd/admitted/patient-card.component.scss');

  it('lets every .pckv dd wrap and never cuts it with an ellipsis', () => {
    const dd = block(block(scss, '.pckv'), '  dd');
    expect(dd).toMatch(/white-space:\s*normal/);
    expect(dd).toMatch(/overflow-wrap:\s*anywhere/);
    expect(dd).not.toMatch(/text-overflow:\s*ellipsis/);
    expect(dd).not.toMatch(/white-space:\s*nowrap/);
    expect(dd).not.toMatch(/overflow:\s*hidden/);
  });
});

describe('SAH-37 D-18: the Cards and Table buttons are 44 px wide as well as high', () => {
  const scss = read('src/app/features/ipd/admitted/admitted-panel.component.scss');

  it('gives .pcview button a minimum width of 44 px', () => {
    expect(block(scss, '.pcview button')).toMatch(/min-width:\s*44px/);
  });

  it('keeps the 44 px minimum height of the segmented buttons', () => {
    expect(block(scss, '.seg button')).toMatch(/min-height:\s*44px/);
  });
});

describe('SAH-37 D-11: the page head is laid out for the finished lead while the lead is still arriving', () => {
  const ts = read('src/app/shared/ui/page-head.component.ts');

  it('sizes the head with a hidden copy of the expected words, not with width breakpoints or line counts', () => {
    // no breakpoint and no guessed height: the copy is a real .lead box, so it wraps like the finished lead at every width
    expect(ts).not.toMatch(/@media/);
    expect(ts).not.toMatch(/min-height/);
    expect(ts).toMatch(/\.lead-hold\s*\{\s*display:\s*grid/);
    expect(ts).toMatch(/\.lead-hold > \.lead\s*\{\s*grid-area:\s*1 \/ 1/);
    expect(ts).toMatch(/\.sizer\s*\{\s*visibility:\s*hidden/);
    expect(ts).toMatch(/\.sizer::after\s*\{\s*content:\s*attr\(data-like\)/);
  });

  it('keeps the real lead readable: never aria-hidden, never hidden itself', () => {
    expect(ts).not.toMatch(/aria-hidden/);
    expect(ts).not.toMatch(/\.lead\.reserve\s*\{[^}]*(visibility|display:\s*none|opacity)/);
  });
});
