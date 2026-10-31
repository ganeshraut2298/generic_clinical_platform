#!/usr/bin/env node
// PreToolUse guard on Bash for the read-only reviewers: only inspection commands, no chaining or redirects.
import fs from 'node:fs';
const cmd = (JSON.parse(fs.readFileSync(0, 'utf8') || '{}').tool_input?.command || '').trim();
const block = m => { process.stderr.write(m + '\n'); process.exit(2); };
if (/[;&|`>]|\$\(/.test(cmd)) block('Blocked: reviewers run one read-only command at a time, without pipes or redirects.');
const ok = [/^git (diff|log|show|status|blame|branch)\b/, /^npm (--prefix frontend )?(audit|ls)\b/, /^mvn (-q )?-f backend\/pom\.xml dependency:tree\b/, /^(ls|dir)\b/, /^node scripts\/validate\.mjs docs\b/];
if (!ok.some(re => re.test(cmd))) block(`Blocked: '${cmd}' is not on the reviewer read-only list (git diff/log/show/status/blame/branch, npm audit/ls, mvn dependency:tree, ls).`);
process.exit(0);
