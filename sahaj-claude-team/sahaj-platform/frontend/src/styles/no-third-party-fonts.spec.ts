// SAH-41 guard: no third-party font host may come back into the frontend. Visitors' IP addresses
// must not be sent to Google (DPDP). The src and public folders are read as text because the
// unit-test runner does not load global styles. The checks are plain functions, and the last
// describe block feeds them fixtures to prove each one still catches a regression.

export {};

interface NodeProcess {
  cwd(): string;
  getBuiltinModule(id: string): unknown;
}
interface NodeDirent {
  name: string;
  isDirectory(): boolean;
  isFile(): boolean;
}
interface NodeFs {
  readdirSync(path: string, options: { withFileTypes: true }): NodeDirent[];
  readFileSync(path: string, encoding: 'utf8'): string;
  existsSync(path: string): boolean;
}

const hostProcess = (globalThis as unknown as { process: NodeProcess }).process;
const hostFs = hostProcess.getBuiltinModule('node:fs') as NodeFs;
const root = hostProcess.cwd().replace(/\\/g, '/');
const srcDir = `${root}/src`;
const publicDir = `${root}/public`;

// Built from parts so this file does not match its own search.
const FORBIDDEN_HOSTS = ['fonts.' + 'googleapis.com', 'fonts.' + 'gstatic.com'];
const BINARY_EXTENSIONS = /\.(woff2?|ttf|otf|eot|png|jpe?g|gif|ico|webp)$/i;

function walk(dir: string): string[] {
  const files: string[] = [];
  for (const entry of hostFs.readdirSync(dir, { withFileTypes: true })) {
    const path = `${dir}/${entry.name}`;
    if (entry.isDirectory()) {
      files.push(...walk(path));
    } else if (entry.isFile() && !BINARY_EXTENSIONS.test(entry.name)) {
      files.push(path);
    }
  }
  return files;
}

/** Forbidden font hosts found in the text, matched without regard to case. */
function forbiddenHosts(text: string): string[] {
  const lower = text.toLowerCase();
  return FORBIDDEN_HOSTS.filter((host) => lower.includes(host));
}

function stripScssComments(scss: string): string {
  return scss.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

/** Every url() target in the stylesheet that is absolute: http, https or protocol-relative. */
function absoluteCssUrls(scss: string): string[] {
  return [...stripScssComments(scss).matchAll(/url\(\s*([^)]*?)\s*\)/gi)]
    .map((m) => m[1].replace(/^['"]|['"]$/g, '').trim())
    .filter((url) => /^(https?:)?\/\//i.test(url));
}

/** Every link element in the html that loads a stylesheet or font from an absolute address. */
function absoluteStylesheetLinks(html: string): string[] {
  const offenders: string[] = [];
  for (const tag of html.match(/<link\b[^>]*>/gi) ?? []) {
    const rel = /\brel\s*=\s*["']?([^"'\s>]+(?:\s+[^"'\s>]+)*)/i.exec(tag)?.[1] ?? '';
    const href = /\bhref\s*=\s*["']?([^"'\s>]+)/i.exec(tag)?.[1] ?? '';
    if (/stylesheet|preload|preconnect/i.test(rel) && /^(https?:)?\/\//i.test(href)) {
      offenders.push(href);
    }
  }
  return offenders;
}

interface Face {
  family: string;
  style: string;
  weight: string;
}

/** Reads each @font-face block into its declarations, whatever order they are written in. */
function parseFaces(scss: string): { faces: Face[]; blockCount: number } {
  const clean = stripScssComments(scss);
  const blockCount = (clean.match(/@font-face/gi) ?? []).length;
  const faces: Face[] = [];
  for (const block of clean.matchAll(/@font-face\s*\{([^{}]*)\}/gi)) {
    const props = new Map<string, string>();
    for (const declaration of block[1].split(';')) {
      const colon = declaration.indexOf(':');
      if (colon > 0) {
        props.set(declaration.slice(0, colon).trim().toLowerCase(), declaration.slice(colon + 1).trim());
      }
    }
    faces.push({
      family: (props.get('font-family') ?? '').replace(/^['"]|['"]$/g, ''),
      style: props.get('font-style') ?? '',
      weight: props.get('font-weight') ?? '',
    });
  }
  return { faces, blockCount };
}

/** Problems with the declared faces. An empty list means every block was read and is a single weight. */
function faceProblems(scss: string): string[] {
  const { faces, blockCount } = parseFaces(scss);
  const problems: string[] = [];
  if (faces.length !== blockCount) {
    problems.push(`parsed ${faces.length} faces but found ${blockCount} @font-face blocks`);
  }
  faces.forEach((face, i) => {
    if (!face.family) problems.push(`face ${i + 1}: no font-family`);
    if (face.style !== 'normal') problems.push(`face ${i + 1}: font-style is "${face.style}"`);
    if (!/^\d{3}$/.test(face.weight)) problems.push(`face ${i + 1}: font-weight is "${face.weight}", not a single weight`);
  });
  return problems;
}

describe('no third-party font hosts (SAH-41)', () => {
  const files = [...walk(srcDir), ...(hostFs.existsSync(publicDir) ? walk(publicDir) : [])];
  const scss = hostFs.readFileSync(`${srcDir}/styles/_fonts.scss`, 'utf8');
  const indexHtml = hostFs.readFileSync(`${srcDir}/index.html`, 'utf8');

  it('scans the frontend source and the public folder', () => {
    expect(files.length).toBeGreaterThan(20);
    expect(files.some((f) => f.endsWith('/src/index.html'))).toBe(true);
    expect(files.some((f) => f.endsWith('/src/styles/_fonts.scss'))).toBe(true);
    expect(files.some((f) => f.includes('/public/'))).toBe(true);
  });

  it('finds no Google Fonts host in any file, in any letter case', () => {
    const offenders = files.filter((file) => forbiddenHosts(hostFs.readFileSync(file, 'utf8')).length > 0);
    expect(offenders.map((f) => f.slice(root.length + 1))).toEqual([]);
  });

  it('has no absolute url() in _fonts.scss and no absolute stylesheet link in index.html', () => {
    expect(absoluteCssUrls(scss)).toEqual([]);
    expect(absoluteStylesheetLinks(indexHtml)).toEqual([]);
  });

  it('declares only own-file urls in _fonts.scss, with swap and woff2', () => {
    const urls = [...scss.matchAll(/url\(([^)]*)\)/g)].map((m) => m[1].replace(/['"]/g, ''));
    expect(urls.length).toBeGreaterThan(0);
    for (const url of urls) {
      expect(url).toMatch(/^\.\.\/assets\/fonts\/[a-z-]+\/[a-z0-9-]+\.woff2$/);
      expect(hostFs.existsSync(`${srcDir}/styles/${url}`)).toBe(true);
    }
    const faces = scss.match(/@font-face/g) ?? [];
    expect(scss.match(/font-display: swap;/g)?.length).toBe(faces.length);
    expect(scss.match(/format\('woff2'\)/g)?.length).toBe(faces.length);
  });

  it('reads every @font-face block, each with a single weight', () => {
    const { faces, blockCount } = parseFaces(scss);
    expect(blockCount).toBeGreaterThan(0);
    expect(faces.length).toBe(blockCount);
    expect(faceProblems(scss)).toEqual([]);
  });

  it('ships exactly the agreed families and weights', () => {
    const combos = new Set(parseFaces(scss).faces.map((f) => `${f.family} ${f.weight}`));
    expect([...combos].sort()).toEqual(
      [
        'Caveat 500',
        'Figtree 400',
        'Figtree 500',
        'Figtree 600',
        'Figtree 700',
        'Noto Sans Devanagari 400',
        'Noto Sans Devanagari 500',
        'Noto Sans Devanagari 600',
      ].sort(),
    );
  });
});

describe('the font guard catches a regression (fixtures, SAH-41)', () => {
  const googleCss = 'https://' + 'fonts.' + 'googleapis.com/css2?family=Figtree&display=swap';
  const gstaticFile = 'https://' + 'fonts.' + 'gstatic.com/s/figtree/v9/x.woff2';

  it('flags a Google stylesheet link in index.html, whatever the attribute order', () => {
    expect(absoluteStylesheetLinks(`<link rel="stylesheet" href="${googleCss}">`)).toEqual([googleCss]);
    expect(absoluteStylesheetLinks(`<link href='${googleCss}' rel='stylesheet' />`)).toEqual([googleCss]);
    expect(absoluteStylesheetLinks(`<LINK REL="PRELOAD" HREF="${gstaticFile}" as="font">`)).toEqual([gstaticFile]);
  });

  it('flags any absolute stylesheet link, not only the two known hosts', () => {
    expect(absoluteStylesheetLinks('<link rel="stylesheet" href="//cdn.example.org/a.css">')).toEqual(['//cdn.example.org/a.css']);
  });

  it('accepts the own-origin links in index.html', () => {
    expect(absoluteStylesheetLinks('<link rel="icon" type="image/x-icon" href="favicon.ico" />')).toEqual([]);
    expect(absoluteStylesheetLinks('<link rel="stylesheet" href="styles.css">')).toEqual([]);
  });

  it('flags the host names in any letter case', () => {
    expect(forbiddenHosts(googleCss.toUpperCase())).toEqual([FORBIDDEN_HOSTS[0]]);
    expect(forbiddenHosts(`@import url(${gstaticFile});`)).toEqual([FORBIDDEN_HOSTS[1]]);
    expect(forbiddenHosts("src: url('../assets/fonts/figtree/figtree-latin.woff2');")).toEqual([]);
  });

  it('flags an absolute url() in a font stylesheet and ignores a commented one', () => {
    expect(absoluteCssUrls(`@font-face { src: url("${gstaticFile}") format('woff2'); }`)).toEqual([gstaticFile]);
    expect(absoluteCssUrls('@font-face { src: url(HTTP://cdn.example.org/a.woff2); }')).toEqual(['HTTP://cdn.example.org/a.woff2']);
    expect(absoluteCssUrls("@font-face { src: url('../assets/fonts/a/b.woff2'); }")).toEqual([]);
    expect(absoluteCssUrls(`/* url(${gstaticFile}) */\n// url(${gstaticFile})`)).toEqual([]);
  });

  it('reads a block whose properties are in another order', () => {
    const scss = "@font-face { font-weight: 400; src: url('a.woff2'); font-style: normal; font-family: 'Figtree'; }";
    expect(parseFaces(scss).faces).toEqual([{ family: 'Figtree', style: 'normal', weight: '400' }]);
    expect(faceProblems(scss)).toEqual([]);
  });

  it('fails a font-weight range', () => {
    const scss = "@font-face { font-family: 'Figtree'; font-style: normal; font-weight: 300 800; }";
    expect(faceProblems(scss)).toEqual(['face 1: font-weight is "300 800", not a single weight']);
  });

  it('fails when a block cannot be read, so the face count differs from the block count', () => {
    const scss = "@font-face { font-family: 'A'; font-style: normal; font-weight: 400; }\n@font-face font-weight: 500;";
    expect(parseFaces(scss)).toEqual({ faces: [{ family: 'A', style: 'normal', weight: '400' }], blockCount: 2 });
    expect(faceProblems(scss)).toEqual(['parsed 1 faces but found 2 @font-face blocks']);
  });
});
