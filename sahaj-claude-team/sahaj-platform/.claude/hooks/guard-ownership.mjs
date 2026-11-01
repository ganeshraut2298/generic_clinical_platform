#!/usr/bin/env node
// PreToolUse guard for Write/Edit/MultiEdit/NotebookEdit. Cross-platform (Windows, macOS, Linux). No jq needed.
// Usage in agent frontmatter: node "$CLAUDE_PROJECT_DIR/.claude/hooks/guard-ownership.mjs" <agent-name>
// Enforces: 1) folder ownership  2) the workflow gate for the story on the current branch  3) one writer per area.
// Exit code 2 blocks the tool call; stderr is shown to the agent.
import fs from 'node:fs'; import path from 'node:path'; import { execSync } from 'node:child_process';

const agent = process.argv[2];
const input = JSON.parse(fs.readFileSync(0, 'utf8') || '{}');
const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();
const file = input.tool_input?.file_path || input.tool_input?.notebook_path;
const who = input.agent_id || input.session_id || 'unknown';
if (!file) process.exit(0);
const block = msg => { process.stderr.write(msg + '\n'); process.exit(2); };

const rel = path.relative(root, path.resolve(root, file)).split(path.sep).join('/');

// 1. Ownership (most specific first)
const AREAS = [
  [/^backend\/src\/main\/resources\/db\/migration\/|^docs\/database\//, 'database', 'db-engineer'],
  [/^frontend\/e2e\/|^docs\/test-plans\/|^backend\/src\/test\/java\/.*\/it\//, 'qa', 'qa-engineer'],
  [/^backend\//, 'backend', 'java-developer'],
  [/^frontend\//, 'frontend', 'angular-developer'],
  [/^docs\/requirements\/|^docs\/stories\//, 'requirements', 'business-analyst'],
  [/^docs\/architecture\/|^docs\/api\//, 'architecture', 'solution-architect'],
  [/^docs\/(handoffs|reviews|decisions)\//, 'lead', 'tech-lead'],
];
const hit = AREAS.find(([re]) => re.test(rel));
const [, area, owner] = hit || [null, 'protected', 'human'];
if (owner !== agent) block(`Blocked: ${rel} belongs to ${owner} (area: ${area}). ${agent} may not edit it. Put the change you need in your handoff for ${owner}.`);

// 2. Workflow gate: work only on feature/<ID>-slug, and only after the human approved the earlier stage
let branch = '';
try { branch = execSync('git rev-parse --abbrev-ref HEAD', { cwd: root, stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim(); } catch {}
const id = (branch.match(/^(?:feature|fix)\/(SAH-\d+)/) || [])[1];
if (!id && agent !== 'tech-lead') block(`Blocked: switch to a story branch first (feature/SAH-<n>-<slug>). Current branch: '${branch || 'no git repo'}'. Ask the tech lead.`);
const approvals = fs.existsSync(path.join(root, 'docs/approvals.md')) ? fs.readFileSync(path.join(root, 'docs/approvals.md'), 'utf8') : '';
const approved = what => new RegExp(`^\\|\\s*${id}\\s*\\|\\s*${what}\\s*\\|\\s*APPROVED`, 'mi').test(approvals);
const NEED = {}; // DEC-001: merge-only gate, 31 Oct 2026
for (const what of (id ? NEED[agent] : null) || []) if (!approved(what)) block(`Blocked: ${id} ${what} not approved yet. A human must add "| ${id} | ${what} | APPROVED | <name> | <date> |" to docs/approvals.md before ${agent} starts.`);

// 3. Area lock: one writer at a time, stale after 2 hours
const lockDir = path.join(root, '.claude/locks'); fs.mkdirSync(lockDir, { recursive: true });
const lock = path.join(lockDir, `${area}.lock`); const now = Date.now();
if (fs.existsSync(lock)) {
  const [holder, holderAgent, since] = fs.readFileSync(lock, 'utf8').trim().split(' ');
  if (holder !== who && now - Number(since) < 2 * 3600 * 1000) block(`Blocked: area '${area}' is locked by another running ${holderAgent} (${holder}). Wait for it to finish.`);
}
fs.writeFileSync(lock, `${who} ${agent} ${now}\n`);
process.exit(0);
