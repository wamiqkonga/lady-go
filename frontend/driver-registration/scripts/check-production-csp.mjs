#!/usr/bin/env node
// Fails the build if the production bundle's CSP (<meta http-equiv="Content-Security-Policy"> in
// dist/index.html, built by `npm run build`) carries 'unsafe-inline', 'unsafe-eval', a wildcard, or
// any source outside 'self'/'none'/'data:'/the configured API origin. vite.config.ts's buildCsp()
// relaxes script-src with 'unsafe-inline' ONLY in `vite dev` (for @vitejs/plugin-react's dev-mode
// React Fast Refresh preamble, an inline <script>) - this is the test that proves that relaxation
// never reaches what actually gets shipped, run against a real `vite build` output, not by reading
// the config source (which would only prove the code says the right thing, not that the built
// artifact actually has it).
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..')
const distIndex = path.join(root, 'dist/index.html')

// Always build fresh - a stale dist/ from a different vite.config.ts would pass this check for the
// wrong reason.
execFileSync('npx', ['vite', 'build'], { cwd: root, stdio: 'inherit' })

if (!existsSync(distIndex)) {
  console.error(`check-production-csp: ${distIndex} does not exist after build.`)
  process.exit(1)
}

const html = readFileSync(distIndex, 'utf8')
const metaMatches = [...html.matchAll(/<meta\s+http-equiv="Content-Security-Policy"\s+content="([^"]*)"/g)]

if (metaMatches.length === 0) {
  console.error('check-production-csp: no <meta http-equiv="Content-Security-Policy"> tag found in dist/index.html.')
  process.exit(1)
}
if (metaMatches.length > 1) {
  console.error(`check-production-csp: expected exactly one CSP meta tag, found ${metaMatches.length}.`)
  process.exit(1)
}

const csp = metaMatches[0][1]
const errors = []

if (/unsafe-inline/i.test(csp)) {
  errors.push("contains 'unsafe-inline' - this is the dev-only relaxation (vite.config.ts's buildCsp) and must never appear in a production build")
}
if (/unsafe-eval/i.test(csp)) {
  errors.push("contains 'unsafe-eval'")
}
if (/(^|[\s;])\*(?=[\s;]|$)/.test(csp)) {
  errors.push("contains a wildcard '*' source")
}

// Directives that carry a source list (as opposed to e.g. upgrade-insecure-requests, which has none).
const SOURCE_DIRECTIVES = new Set([
  'default-src',
  'script-src',
  'style-src',
  'img-src',
  'font-src',
  'connect-src',
  'frame-src',
  'object-src',
  'base-uri',
  'form-action',
  'frame-ancestors',
])
const ALLOWED_BARE_TOKENS = new Set(["'self'", "'none'", 'data:'])
const apiBaseUrl = process.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

const directives = csp
  .split(';')
  .map((d) => d.trim())
  .filter(Boolean)
  .map((d) => {
    const [name, ...tokens] = d.split(/\s+/)
    return { name, tokens }
  })

for (const { name, tokens } of directives) {
  if (!SOURCE_DIRECTIVES.has(name)) continue
  for (const token of tokens) {
    if (ALLOWED_BARE_TOKENS.has(token)) continue
    if (token === apiBaseUrl) continue
    errors.push(`${name} has a source outside 'self'/'none'/'data:'/the configured API origin: ${token}`)
  }
}

if (errors.length > 0) {
  console.error('check-production-csp: production CSP failed:')
  for (const e of errors) console.error(`  - ${e}`)
  console.error(`\nFull CSP:\n${csp}`)
  process.exit(1)
}

console.log('check-production-csp: production CSP has no unsafe-inline/unsafe-eval/wildcard/third-party sources.')
console.log(`CSP: ${csp}`)
