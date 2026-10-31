#!/usr/bin/env node
// Stop hook (runs as SubagentStop) for agents that change files.
// Usage: node "$CLAUDE_PROJECT_DIR/.claude/hooks/finish-task.mjs" <agent> <validate-area>
// Runs the validation gate; while it fails the agent must keep working. Releases the agent's area locks.
import fs from 'node:fs'; import path from 'node:path'; import { spawnSync } from 'node:child_process';
const [agent, area = 'none'] = process.argv.slice(2);
const input = JSON.parse(fs.readFileSync(0, 'utf8') || '{}');
const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();
const who = input.agent_id || input.session_id || 'unknown';
const release = () => { const d = path.join(root, '.claude/locks'); if (!fs.existsSync(d)) return;
  for (const f of fs.readdirSync(d)) { const p = path.join(d, f); if (fs.readFileSync(p, 'utf8').startsWith(`${who} ${agent} `)) fs.rmSync(p); } };
if (area !== 'none') {
  const r = spawnSync(process.execPath, [path.join(root, 'scripts/validate.mjs'), area], { cwd: root, encoding: 'utf8' });
  if (r.status !== 0) {
    const out = ((r.stdout || '') + (r.stderr || '')).split('\n').slice(-40).join('\n');
    if (input.stop_hook_active) { release(); process.stderr.write(`Validation still failing for '${area}'. Report status BLOCKED in your handoff with this output. Do not mark the task complete.\n${out}\n`); process.exit(0); }
    process.stderr.write(`Validation failed for '${area}'. Fix it before you finish:\n${out}\n`); process.exit(2);
  }
}
release(); process.exit(0);
