# CI secrets

Repo secrets used by `.github/workflows/`, and how to set each one up. None of these block CI if
unset — every job that needs one is written to skip cleanly (see each job's own comment in the
workflow file for the exact mechanism) rather than fail.

## `NVD_API_KEY`

Used by: `security.yml`'s `owasp-dependency-check` job.

Without this secret, the job is skipped entirely (logged as a `::notice::`, not a failure) - confirmed
in CI: `dependency-check-maven` 13.x hard-rejects an empty/missing key rather than falling back to slow
unauthenticated NVD access the way older versions did, so there's no degraded-but-working mode to fall
back to; it's "configured and useful" or "skipped," nothing in between.

**To enable:**
1. Request a free key at <https://nvd.nist.gov/developers/request-an-api-key> (an email address is all
   that's needed; the key arrives by email, usually within a few minutes).
2. In the repo: Settings → Secrets and variables → Actions → New repository secret.
3. Name: `NVD_API_KEY`. Value: the key from step 1.

No rotation schedule required by NVD as of this writing; if the key ever stops working, request a new
one and replace the secret value - no other change needed.

## `GITHUB_TOKEN`

Used by: `secret-scan` (gitleaks). Auto-provided by GitHub Actions for every workflow run - nothing to
set up, listed here only so this doc is a complete list of every secret referenced anywhere in
`.github/workflows/`.
