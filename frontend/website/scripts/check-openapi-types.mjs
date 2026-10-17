#!/usr/bin/env node
// Fails the build if src/registration/lib/api-types.ts is out of date relative to docs/api/openapi.json -
// mirrors the backend's OpenApiSpecDriftTest (same "regenerate and diff" philosophy, applied to the
// generated TypeScript client instead of the spec itself).
import { execFileSync } from 'node:child_process'
import { readFileSync, unlinkSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..')
const checkedIn = path.join(root, 'src/registration/lib/api-types.ts')
const freshPath = path.join(root, 'src/registration/lib/api-types.fresh.ts')

execFileSync(
  'npx',
  ['openapi-typescript', '../../docs/api/openapi.json', '-o', 'src/registration/lib/api-types.fresh.ts'],
  { cwd: root, stdio: 'inherit' },
)

const checkedInContent = readFileSync(checkedIn, 'utf8')
const freshContent = readFileSync(freshPath, 'utf8')
unlinkSync(freshPath)

if (checkedInContent !== freshContent) {
  console.error(
    'src/registration/lib/api-types.ts is out of date - run `npm run openapi:generate` and commit the result.',
  )
  process.exit(1)
}
console.log('src/registration/lib/api-types.ts matches docs/api/openapi.json.')
