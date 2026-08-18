#!/usr/bin/env node
/**
 * PostToolUse hook — flag Jackson 2 databind imports in Java files.
 *
 * Spring Boot 4 ships Jackson 3, whose databind and streaming types live under
 * `tools.jackson.*`. A leftover `com.fasterxml.jackson.databind.*` or
 * `com.fasterxml.jackson.core.*` import still compiles (Jackson 2 is on the
 * classpath transitively) and fails at runtime — exactly the class of mistake
 * that needs a mechanical check rather than a prose reminder.
 *
 * `com.fasterxml.jackson.annotation.*` is deliberately NOT flagged: that artifact
 * is shared between Jackson 2 and 3, so `@JsonView`, `@JsonIgnore`,
 * `@JsonProperty` etc. come from there and are correct (see CLAUDE.md).
 *
 * Contract (docs: https://code.claude.com/docs/en/hooks):
 *   hit      → warning on stderr, exit 2 (PostToolUse exit 2 shows stderr to Claude)
 *   clean    → exit 0, no output
 *   any error → exit 0 silently; a broken hook must not spam every edit
 */

import { readFileSync } from 'node:fs';

const WRITE_TOOLS = new Set(['Edit', 'Write', 'MultiEdit', 'NotebookEdit']);

// Jackson 2 packages whose Jackson 3 equivalent is a straight prefix swap.
const BAD_PACKAGES = [
  { from: 'com.fasterxml.jackson.databind', to: 'tools.jackson.databind' },
  { from: 'com.fasterxml.jackson.core', to: 'tools.jackson.core' },
];

function readStdin() {
  return new Promise((resolve) => {
    if (process.stdin.isTTY) {
      resolve('');
      return;
    }
    let data = '';
    const bail = setTimeout(() => resolve(data), 5000);
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (chunk) => {
      data += chunk;
    });
    process.stdin.on('end', () => {
      clearTimeout(bail);
      resolve(data);
    });
    process.stdin.on('error', () => {
      clearTimeout(bail);
      resolve(data);
    });
  });
}

function candidatePaths(toolInput) {
  if (!toolInput || typeof toolInput !== 'object') return [];
  const out = [];
  for (const key of ['file_path', 'path', 'filePath']) {
    if (typeof toolInput[key] === 'string' && toolInput[key]) out.push(toolInput[key]);
  }
  if (Array.isArray(toolInput.edits)) {
    for (const edit of toolInput.edits) {
      if (edit && typeof edit.file_path === 'string' && edit.file_path) out.push(edit.file_path);
    }
  }
  return out.filter((p) => p.toLowerCase().endsWith('.java'));
}

/** → [{ line, text, suggestion }] for import statements from a Jackson 2 databind/core package. */
function findBadImports(source) {
  const hits = [];
  const lines = source.split(/\r?\n/);
  lines.forEach((line, i) => {
    const m = /^\s*import\s+(?:static\s+)?([\w.]+(?:\.\*)?)\s*;/.exec(line);
    if (!m) return;
    const imported = m[1];
    for (const pkg of BAD_PACKAGES) {
      if (imported === pkg.from || imported.startsWith(pkg.from + '.')) {
        hits.push({
          line: i + 1,
          text: line.trim(),
          suggestion: 'import ' + imported.replace(pkg.from, pkg.to) + ';',
        });
        return;
      }
    }
  });
  return hits;
}

async function main() {
  const raw = await readStdin();
  if (!raw.trim()) return 0;

  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    return 0;
  }

  const toolName = payload && payload.tool_name;
  if (toolName && !WRITE_TOOLS.has(toolName)) return 0;

  const problems = [];
  for (const file of candidatePaths(payload && payload.tool_input)) {
    let source;
    try {
      source = readFileSync(file, 'utf8');
    } catch {
      continue; // deleted, renamed, or unreadable — not our problem
    }
    const hits = findBadImports(source);
    if (hits.length) problems.push({ file, hits });
  }

  if (!problems.length) return 0;

  const out = ['Jackson 2 import in a Spring Boot 4 (Jackson 3) codebase — compiles, fails at runtime:'];
  for (const { file, hits } of problems) {
    out.push('  ' + file.replace(/\\/g, '/'));
    for (const hit of hits) {
      out.push(`    line ${hit.line}: ${hit.text}`);
      out.push(`      replace with: ${hit.suggestion}`);
    }
  }
  out.push('(com.fasterxml.jackson.annotation.* is shared between Jackson 2 and 3 and is correct — only databind/core moved to tools.jackson.*)');
  process.stderr.write(out.join('\n') + '\n');
  return 2;
}

main().then(
  (code) => process.exit(code),
  () => process.exit(0)
);
