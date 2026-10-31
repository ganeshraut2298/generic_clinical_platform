#!/usr/bin/env node
// Build, test and rules gate. A task is complete only when this passes.
// Usage: node scripts/validate.mjs frontend|backend|db|docs|all
// Works on Windows, macOS and Linux.
import fs from 'node:fs'; import path from 'node:path'; import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const area = process.argv[2] || 'all';
const exists = p => fs.existsSync(path.join(root, p));
const read = p => fs.readFileSync(path.join(root, p), 'utf8');
const fail = m => { console.error(`FAILED: ${m}`); process.exit(1); };
const run = (cmd, args) => {
  console.log(`> ${cmd} ${args.join(' ')}`);
  const r = spawnSync(cmd, args, { cwd: root, stdio: 'inherit', shell: process.platform === 'win32' });
  if (r.status !== 0) fail(`${cmd} ${args.join(' ')}`);
};
const mvn = () => exists('backend/mvnw') ? (process.platform === 'win32' ? 'backend\\mvnw.cmd' : './backend/mvnw') : 'mvn';

function backend() {
  if (!exists('backend/pom.xml')) return console.log('backend: not scaffolded yet, skipped');
  // Unit tests + integration tests against the local PostgreSQL test database (sahaj_test), see .env
  run(mvn(), ['-B', '-q', '-f', 'backend/pom.xml', 'verify']);
}
function frontend() {
  if (!exists('frontend/package.json')) return console.log('frontend: not scaffolded yet, skipped');
  run('npm', ['--prefix', 'frontend', 'run', 'lint']);
  run('npm', ['--prefix', 'frontend', 'test', '--', '--watch=false', '--browsers=ChromeHeadless']);
  run('npm', ['--prefix', 'frontend', 'run', 'build']);
}
function db() {
  const dir = 'backend/src/main/resources/db/migration';
  if (exists(dir)) {
    const bad = fs.readdirSync(path.join(root, dir)).filter(f => f !== '.gitkeep' && !/^(V\d{4}_\d{2}_\d{2}_\d{4}__[a-z0-9_]+\.sql|R__[a-z0-9_]+\.sql)$/.test(f));
    if (bad.length) fail(`bad migration file names: ${bad.join(', ')} (use V<YYYY>_<MM>_<DD>_<HHMM>__<module>_<what>.sql)`);
    for (const f of fs.readdirSync(path.join(root, dir)).filter(f => f.endsWith('.sql'))) {
      const sql = read(`${dir}/${f}`).toLowerCase();
      for (const m of sql.matchAll(/create table (?:if not exists )?([a-z_]+\.[a-z_]+)/g))
        if (/tenant_id/.test(sql) && !sql.includes(`alter table ${m[1]} enable row level security`)) fail(`${f}: ${m[1]} has tenant_id but no row level security`);
    }
  }
  backend();
}
// Strict mockup and workflow rules for requirement and story documents
function docs() {
  const map = exists('docs/design/screen-map.md') ? read('docs/design/screen-map.md') : '';
  const screens = [...map.matchAll(/^\|\s*([^|]+?)\s*\|/gm)].map(m => m[1].toLowerCase()).filter(s => !/^(mockup screen|---)/.test(s));
  for (const kind of ['requirements', 'stories']) {
    const d = `docs/${kind}`; if (!exists(d)) continue;
    for (const f of fs.readdirSync(path.join(root, d)).filter(f => f.endsWith('.md'))) {
      const t = read(`${d}/${f}`);
      if (!/^SAH-\d+\.md$/.test(f)) fail(`${d}/${f}: name files by story ID, e.g. SAH-12.md`);
      const ms = (t.match(/^Mockup screen:\s*(.+)$/mi) || [])[1];
      if (!ms) fail(`${d}/${f}: missing "Mockup screen: <screen from docs/design/screen-map.md>"`);
      if (/not in mockup|needs design/i.test(ms)) fail(`${d}/${f}: screen is not in the mockup. A human must add it to the mockup before work continues.`);
      const key = ms.toLowerCase().split(/[(→]/)[0].trim();
      if (!screens.some(s => s.includes(key) || key.includes(s.split(/[(→]/)[0].trim()))) fail(`${d}/${f}: "${ms}" is not a screen in docs/design/screen-map.md`);
      if (kind === 'stories' && !t.includes('## Acceptance criteria')) fail(`${d}/${f}: no "## Acceptance criteria" section`);
    }
  }
  console.log('docs: mockup references and story format OK');
}

({ backend, frontend, db, database: db, docs, requirements: docs, architecture: docs,
   qa: () => { docs(); db(); frontend(); }, all: () => { docs(); db(); frontend(); } }[area] || (() => fail(`unknown area ${area}`)))();
console.log(`validate ${area}: PASSED`);
