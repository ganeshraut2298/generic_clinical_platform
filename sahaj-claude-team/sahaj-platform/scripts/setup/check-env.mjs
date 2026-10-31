#!/usr/bin/env node
// Checks that every tool Sahaj needs is installed, at the right version.
import { spawnSync } from 'node:child_process';
const win = process.platform === 'win32';
const v = (cmd, args) => { const r = spawnSync(cmd, args, { encoding: 'utf8', shell: win }); return r.status === 0 || r.stderr ? ((r.stdout || '') + (r.stderr || '')).trim() : null; };
const checks = [
  ['Java 17 (JDK)', v('java', ['-version']), o => /version "17\./.test(o) || /openjdk 17\./.test(o)],
  ['Maven 3.9+', v('mvn', ['-v']), o => /Apache Maven 3\.(9|\d{2})/.test(o)],
  ['Node 20+', process.version, o => Number(o.slice(1).split('.')[0]) >= 20],
  ['npm', v('npm', ['-v']), o => !!o],
  ['Git', v('git', ['--version']), o => /git version/.test(o)],
  ['PostgreSQL client (psql) 16+', v('psql', ['--version']), o => /\b(1[6-9]|2\d)\./.test(o)],
];
let ok = true;
for (const [name, out, test] of checks) { const pass = !!out && test(out); ok &&= pass; console.log(`${pass ? 'OK  ' : 'MISS'} ${name}${out ? '  — ' + out.split('\n')[0] : '  — not found'}`); }
console.log(ok ? '\nAll tools present.' : '\nInstall the missing tools (scripts/setup/install-*), then open a new terminal and run this again.');
process.exit(ok ? 0 : 1);
