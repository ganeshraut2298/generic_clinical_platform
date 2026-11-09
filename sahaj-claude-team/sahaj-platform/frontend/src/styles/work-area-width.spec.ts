// Bug fix (9 Nov 2026): the page width changed wrongly when the left rail was expanded or collapsed.
// Cause: a maximum width on the work area (1680 px, centred) that only applied once the rail was
// collapsed, so the content jumped into a centred column with wide empty bands. The mockup's last
// rule is `.axws, .cs main.page { max-width: none }`. The style files are read as text because the
// unit-test runner does not load global styles or do layout; the measured widths are in the e2e run.

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
  expect(start).toBeGreaterThanOrEqual(0);
  return scss.slice(start, scss.indexOf('\n}', start));
}

describe('work area width follows the rail (fluid, no maximum)', () => {
  const consoleScss = read('src/styles/_console.scss');
  const shell = read('src/styles/_shell.scss');
  const admin = read('src/app/features/admin/shell/admin-shell.component.scss');

  it('has no maximum width and no centring margin on the clinic work area', () => {
    const main = block(consoleScss, '.shell main.page');
    expect(main).not.toMatch(/max-width/);
    expect(main).not.toMatch(/margin:\s*0 auto/);
    expect(main).toMatch(/width:\s*100%/);
  });

  it('keeps the top bar on a fixed 20 px edge, not tied to a page maximum', () => {
    expect(shell).not.toMatch(/--page-max/);
    expect(block(shell, '.topbar')).toMatch(/padding:\s*7px 20px;/);
  });

  it('has no maximum width on the admin work area', () => {
    const page = block(admin, '.adm-page');
    expect(page).not.toMatch(/max-width/);
    expect(page).not.toMatch(/margin-inline:\s*auto/);
  });

  it('uses the same two rail widths for the grid on desktop', () => {
    expect(block(shell, '.shell')).toMatch(/grid-template-columns:\s*248px minmax\(0, 1fr\)/);
    expect(shell).toMatch(/grid-template-columns:\s*var\(--rail-w\) minmax\(0, 1fr\)/);
  });
});
