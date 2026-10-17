# SAST (Semgrep) triage

Status: triaged. First real run of the `sast` job (`.github/workflows/security.yml`) produced 42
findings across 4 rule categories. All 42 have been fixed, suppressed with a written reason, or
accepted below. A follow-up local re-scan with the exact same version and rule config confirms 0
findings remain.

## Methodology

Findings were triaged from a local Semgrep run rather than by re-triggering CI, using the exact
same pinned version and rule config as the `sast` job:

```
pip install semgrep==1.180.0
SEMGREP_SEND_METRICS=off semgrep scan \
  --config p/security-audit --config p/owasp-top-ten \
  --config p/java --config p/typescript --config p/kotlin --config p/swift --config p/react \
  --json --output semgrep-results.json
```

Run 2026-10-15, before any fixes: 42 findings (without `p/react`, added in a follow-up pass — see
"Rule pack coverage" below). Same command after all fixes below: 0 findings.

Note: `p/security-audit`, `p/owasp-top-ten`, etc. are Semgrep registry packs, not
version-pinned themselves (Semgrep's registry doesn't version packs the way `pip`/Maven version a
release — see the `sast` job's own comments in `security.yml`). A pack's rule count can drift
between runs as Semgrep updates its feed; this did not change the finding count in this triage.

## Severity scale

Semgrep findings in this repo mix two severity scales depending on rule type:

- Native 3-tier scale: `INFO` / `WARNING` / `ERROR`
- Newer 4-tier scale (used by some newer rule packs, e.g. supply-chain config rules):
  `LOW` / `MEDIUM` / `HIGH` / `CRITICAL`

The `sast` job's severity gate (added as part of this triage) treats `{ERROR, HIGH, CRITICAL}` as
blocking and fails the job if any such finding is present; `{WARNING, MEDIUM, LOW, INFO}` are still
reported as GitHub Actions warning annotations but do not fail the job. None of the 42 findings in
this first run were `ERROR`/`HIGH`/`CRITICAL` — all 34 were `WARNING` and all 8 were `MEDIUM`. The
gate was proven locally with a deliberately introduced `ERROR`-severity sample (see "Gate
verification" below), since this dataset alone has no example of the blocking path.

## Findings and classification

### 1. `yaml.github-actions.security.github-actions-mutable-action-tag` — 32 findings — severity `WARNING`

> GitHub Actions step uses a mutable tag or branch reference. Tags and branch names can be
> silently repointed by the action owner, enabling supply-chain attacks (e.g. the `trivy-action`
> and `kics-github-action` compromises).

**Classification: real issue — fixed.** All 32 `uses:` references across
`.github/workflows/{admin-web-e2e,android,backend,driver-registration-e2e,frontend,ios,security}.yml`
were pinned to a full 40-character commit SHA (resolved via `gh api
repos/{owner}/{repo}/commits/{tag}`), with the original tag kept as a trailing comment for
readability, e.g.:

```yaml
- uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
```

No test applicable (CI workflow config, not application code) — verified instead by confirming
`grep -rn "uses:.*@v[0-9]" .github/workflows/*.yml` returns no matches, that all 8 workflow files
still parse as valid YAML, and that this task's own CI run on the final push completes.

### 2. `java.android.security.exported_activity.exported_activity` — 2 findings — severity `WARNING`

> The application exports an activity. Any application on the device can launch the exported
> activity, which could lead to launching unintended/unauthorized actions.

Files: `mobile/android-customer/app/src/main/AndroidManifest.xml`,
`mobile/android-driver/app/src/main/AndroidManifest.xml` — both on the apps' `MainActivity`.

**Classification: false positive — suppressed with `nosemgrep`.** This is each app's required
MAIN/LAUNCHER activity. Android itself requires `exported="true"` here from API 31 onward — the
manifest merger fails the build for any activity with a MAIN/LAUNCHER intent-filter that doesn't
declare it. The activity accepts no deep-link data, no custom URI scheme, and reads no `Intent`
extras, so there is no attacker-controlled surface for another app to drive it into a state it
doesn't already start in on its own. Suppressed with a `nosemgrep` comment directly above the
`<activity>` tag in both manifests, each with the reasoning inline:

```xml
<!-- False positive: this is the app's MAIN/LAUNCHER activity, not a general exported
     component - Android REQUIRES exported="true" here (API 31+ build-fails without it for
     any activity with a MAIN/LAUNCHER intent-filter), and it accepts no deep-link data, no
     custom scheme, reads no Intent extras - there is no attacker-controlled surface for
     another app to drive into a state it doesn't already start in. See
     docs/security/sast-triage.md. -->
<!-- nosemgrep: java.android.security.exported_activity.exported_activity -->
<activity
    ...
```

Gotcha hit while fixing this: Semgrep only honors a `nosemgrep` comment when the literal
`nosemgrep: <rule-id>` text is on the line immediately preceding the match. Placing it as the
*first* line of a longer explanatory comment block (with the explanation following on subsequent
lines, still above the tag) does not suppress the finding — only a block ending with the
`nosemgrep` line, directly adjacent to the matched line, works.

### 3. `package_managers.dependabot.dependabot-missing-cooldown.dependabot-missing-cooldown` — 6 findings — severity `MEDIUM`

> This Dependabot configuration does not set a cooldown period. Newly published packages can be
> malicious or unstable; cooldown delays proposing them for a configurable number of days.

One finding per `updates:` entry in `.github/dependabot.yml` (maven/backend, npm/website,
npm/admin, gradle/android-customer, gradle/android-driver, github-actions/root).

**Classification: real issue — fixed.** Added `cooldown: { default-days: 7 }` to every entry in
`.github/dependabot.yml`. Waiting 7 days after a version is published before Dependabot proposes
it closes the window where a newly published, briefly-malicious version could be picked up before
it's yanked.

### 4. `package_managers.npm.npm-missing-minimum-release-age.npm-missing-minimum-release-age` — 2 findings — severity `MEDIUM`

> This `.npmrc` does not set a minimum release age, or sets it too low. Add `min-release-age` to
> wait before resolving newly published package versions.

Files: `frontend/admin/.npmrc`, `frontend/driver-registration/.npmrc`.

**Classification: real issue — fixed.** Added `min-release-age=7` to both files, for the same
supply-chain reasoning as the Dependabot cooldown fix above. This is an npm ≥11.10 feature and is
silently ignored (not an error) on older npm versions. `frontend/website` has no `.npmrc` and was
not flagged by Semgrep, so none was added there — out of scope for this triage.

**Follow-up check (2026-10-17): the setting is currently inert in CI.** `frontend.yml`,
`admin-web-e2e.yml` and `driver-registration-e2e.yml` all pin `actions/setup-node` to `node-version:
22`, which GitHub Actions resolves to `22.23.3` (confirmed from the job logs — `Found in cache @
/opt/hostedtoolcache/node/22.23.3/x64`). Node 22.23.3 bundles **npm 10.9.9** (confirmed from
`nodejs.org`'s release manifest), below the npm ≥11.10 floor this rule requires. `min-release-age=7`
is silently ignored on every current CI run — not a bug in the setting itself, but the fix as
applied doesn't do anything yet. Bumping CI to an npm ≥11.10 (e.g. via `npm install -g npm@latest`
as an added workflow step, or a newer Node major once one ships with it) is a separate, undone
follow-up — flagged here, not actioned, since it wasn't part of this triage's scope.

## Rule pack coverage

Follow-up check (2026-10-17) of whether the `sast` job's configured packs
(`p/security-audit p/owasp-top-ten p/java p/typescript p/kotlin p/swift`) actually cover this
repo's three real stacks:

- **Java/Spring** (backend, `spring-boot-starter-web` per `backend/pom.xml`): covered. No separate
  `p/spring` pack exists in Semgrep's registry (confirmed — `--config p/spring` fails to resolve).
  Spring-specific taint rules ship inside `p/security-audit` under the `java.spring.*` namespace,
  confirmed by scanning a synthetic Spring controller with a tainted SQL string — it was caught by
  `java.spring.security.injection.tainted-sql-string.tainted-sql-string` (`ERROR`) with no pack
  changes needed.
- **Kotlin/Android** (`mobile/android-customer`, `mobile/android-driver`): partially covered. No
  separate `p/android` pack exists either (confirmed the same way). Android-specific rules (e.g.
  `java.android.security.exported_activity.exported_activity`, finding #2 above) ship under the
  `java.android.*` namespace inside `p/java`/`p/security-audit`, already loaded. Native-Kotlin
  rules from `p/kotlin` itself are thin on Semgrep's free/unauthenticated tier — a synthetic
  `WebView` sample with `javaScriptEnabled = true` + `loadUrl(url)` on an untrusted `url` was not
  flagged by any currently-loaded pack. Accepted as a known registry-tier limitation, not a missing
  pack-name gap, since there's no additional named pack to add for it.
- **TypeScript/React** (`frontend/admin`, `frontend/driver-registration`, `frontend/website`, all
  confirmed React apps per their `package.json`): **gap found and fixed.** `p/react` exists in the
  registry (confirmed it resolves, 4 rules on the free tier, including
  `typescript.react.security.audit.react-dangerouslysetinnerhtml.react-dangerouslysetinnerhtml` for
  `dangerouslySetInnerHTML` misuse) but was not in the job's `--config` list. Added
  `--config p/react` to the `sast` job's Semgrep scan step. Re-scanning the whole repo locally with
  the updated six-pack config (`p/security-audit p/owasp-top-ten p/java p/typescript p/kotlin
  p/swift p/react`) produced **0 new findings** — no further triage needed for this pass.

## SHA-pin verification

Follow-up check (2026-10-17) of the 32-finding SHA-pinning fix (finding #1 above): re-resolved
every unique `owner/repo@tag` this repo pins against GitHub's API (`gh api
repos/{owner}/{repo}/commits/{tag}`), and separately confirmed each pinned SHA exists as a real
commit inside that exact repo (`gh api repos/{owner}/{repo}/commits/{sha}`) — catching the case
where a SHA pin accidentally pointed at a commit from the wrong repository.

| ref | pinned SHA matches repo | still matches live tag |
|---|---|---|
| `actions/checkout@v4` | yes | yes |
| `actions/setup-java@v4` | yes | yes |
| `actions/setup-node@v4` | yes | yes |
| `actions/upload-artifact@v4` | yes | yes |
| `gradle/actions/setup-gradle@v4` | yes | yes |
| `gitleaks/gitleaks-action@v2` | yes | yes |
| `actions/dependency-review-action@v4` | yes | yes |
| `actions/setup-python@v5` | yes | yes |
| `github/codeql-action/{init,autobuild,analyze}@v3` | yes | **no** |

All 9 pinned SHAs belong to the repository they claim to pin — none points outside its own action's
repo. One has drifted: `github/codeql-action`'s `v3` tag has moved to a newer commit since this SHA
was resolved — expected, since that's exactly what pinning a tag freezes against. No action needed:
the `codeql` job that uses it is `if: false` (disabled — see that job's own comment in
`security.yml` for why), so this isn't live in any running workflow.

## Gate verification

The `sast` job's new severity gate (a dedicated step, no `continue-on-error`) was proven locally
both ways, since none of the 42 real findings above are `ERROR`/`HIGH`/`CRITICAL`:

- **Red**: a standalone `Bad.java` sample with a string-concatenated SQL query was scanned with
  the exact same Semgrep command as the job. It produced one `ERROR`-severity finding
  (`java.lang.security.audit.formatted-sql-string.formatted-sql-string`). Running the gate script
  against that output printed the finding as a `::error::` annotation and exited `1`.
- **Green**: with the sample removed, the same gate script run against this repo's real (0-finding)
  scan output exited `0`.

## Status

0 findings remain as of the fixes and suppressions above (confirmed by local re-scan with the
job's exact version and rule config). The gate now fails the job on any future `ERROR`/`HIGH`/
`CRITICAL` finding and reports (without blocking) anything lower.
