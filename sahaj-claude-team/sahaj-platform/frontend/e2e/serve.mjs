// Tiny static server for the e2e tests. Serves the production build of the Angular app
// (frontend/dist/frontend/browser) with a fallback to index.html, like a real SPA host.
// It has no /api: tests that need the API mock it in the browser (see home.spec.ts).
import { createServer } from 'node:http';
import { createReadStream, existsSync, readFileSync, statSync } from 'node:fs';
import { extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

// E2E_DIST=<folder> (mutation checks only): serve a temporary copy of the build, with an old CSS rule put back, to prove that a
// regression test fails on the old rule (SAH-37 audit, DEC-032). Unset in every normal run.
const root = process.env.E2E_DIST
  ? resolve(process.env.E2E_DIST)
  : resolve(fileURLToPath(new URL('.', import.meta.url)), '..', 'dist', 'frontend', 'browser');
const port = Number(process.env.E2E_PORT ?? 4300);
// E2E_NO_KINETIC=1 (SAH-39 comparison runs only): serves the same build with the homepage background layer switched off,
// to measure whether the layer slows other specs. Unset in every normal run.
const NO_KINETIC = process.env.E2E_NO_KINETIC === '1';
const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.ico': 'image/x-icon',
  '.svg': 'image/svg+xml',
  '.json': 'application/json',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
};

if (!existsSync(join(root, 'index.html'))) {
  console.error(`No build found in ${root}. Run: npm --prefix frontend run build`);
  process.exit(1);
}

createServer((req, res) => {
  let path;
  try {
    path = decodeURIComponent(new URL(req.url ?? '/', 'http://localhost').pathname);
  } catch {
    // a malformed percent-escape (for example /%E0%A4%A) must not crash the server (security review Low #1)
    res.writeHead(400, { 'content-type': 'text/plain' }).end('Bad request');
    return;
  }
  let file = normalize(join(root, path));
  if (!file.startsWith(root + sep) && file !== root) {
    res.writeHead(403).end();
    return;
  }
  if (!existsSync(file) || statSync(file).isDirectory()) {
    if (path.startsWith('/api/')) {
      res.writeHead(404, { 'content-type': 'text/plain' }).end('No API in the e2e server');
      return;
    }
    file = join(root, 'index.html');
  }
  if (NO_KINETIC && file === join(root, 'index.html')) {
    // SAH-39 comparison run only (E2E_NO_KINETIC=1): same build, the homepage layer switched off (no 2D context, so it
    // draws nothing and adds no listener) and its CSS removed (no blur panels, no stacking change). Used to find out
    // whether the layer slows the older specs. Never set in a normal run.
    const off =
      '<script>HTMLCanvasElement.prototype.getContext=function(){return null};</script>' +
      '<style>app-kinetic-background{display:none!important}.surface-solid::before,.surface-glass::before{display:none!important}' +
      '.pubwrap{position:static!important;z-index:auto!important;isolation:auto!important}</style>';
    res.writeHead(200, { 'content-type': types['.html'] });
    res.end(readFileSync(file, 'utf8').replace('</head>', `${off}</head>`));
    return;
  }
  res.writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream' });
  createReadStream(file).pipe(res);
}).listen(port, '127.0.0.1', () => console.log(`e2e server on http://127.0.0.1:${port}`));
