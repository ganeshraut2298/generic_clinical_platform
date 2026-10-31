#!/usr/bin/env node
// Creates roles, databases and schemas from scripts/db/init.sql, using passwords in .env.
// Usage (from repo root): node scripts/setup/create-database.mjs
import fs from 'node:fs'; import path from 'node:path'; import { spawnSync } from 'node:child_process'; import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const envFile = path.join(root, '.env');
if (!fs.existsSync(envFile)) { console.error('No .env found. Copy .env.example to .env and set the passwords first.'); process.exit(1); }
const env = Object.fromEntries(fs.readFileSync(envFile, 'utf8').split(/\r?\n/).filter(l => /^\s*[A-Z_]+=/.test(l)).map(l => { const i = l.indexOf('='); return [l.slice(0, i).trim(), l.slice(i + 1).trim()]; }));
for (const k of ['PG_SUPERUSER_PASSWORD', 'SAHAJ_DB_MIGRATOR_PASSWORD', 'SAHAJ_DB_APP_PASSWORD'])
  if (!env[k] || env[k].startsWith('change-me')) { console.error(`Set ${k} in .env first.`); process.exit(1); }
const args = ['-h', env.PG_HOST || 'localhost', '-p', env.PG_PORT || '5432', '-U', env.PG_SUPERUSER || 'postgres', '-d', 'postgres',
  '-v', `migrator_password=${env.SAHAJ_DB_MIGRATOR_PASSWORD}`, '-v', `app_password=${env.SAHAJ_DB_APP_PASSWORD}`, '-f', path.join(root, 'scripts/db/init.sql')];
const r = spawnSync('psql', args, { stdio: 'inherit', env: { ...process.env, PGPASSWORD: env.PG_SUPERUSER_PASSWORD } });
if (r.error) { console.error('psql not found. Install PostgreSQL first (scripts/setup/install-*.ps1|sh) and open a new terminal.'); process.exit(1); }
if (r.status !== 0) process.exit(r.status);
console.log('\nDatabases sahaj_dev and sahaj_test are ready, with roles sahaj_migrator and sahaj_app and schemas platform, patient, opd, ipd, hra, billing.');
