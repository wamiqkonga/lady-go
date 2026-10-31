# Plan: deploy the websites automatically from GitHub

Status: **DRAFT, waiting for Wamiq's approval. Nothing has been built or changed.**
Date: 2026-10-25. Replaces the GitLab plan for websites (GitHub is now public, so Actions minutes are free).

## 1. Goal and 60-day target

**Goal.** A merge to `main` that changes a website (`frontend/website` or `frontend/admin`) tests it, builds it for production, and uploads it to MilesWeb on its own, checks the live site shows the new version, and can be rolled back in one click.

**Why it serves the 60-day number.** Drivers sign up through the website. Every sign-up problem (the phone format, the silent "code sent") took a manual build and upload to fix. Automatic deploys mean a fix reaches drivers within minutes of the merge, and nobody can forget the `VITE_API_BASE_URL` setting that would silently point the live site at `localhost`.

**Targets (edit if you disagree):** merge to live under 10 minutes; one-click rollback; a wrong build can never reach the site unnoticed (the pipeline compares what is live with what it built).

## 2. What exists today (checked 25 Oct)

| Site | Served from | Live build | Matches `main`? |
|---|---|---|---|
| `ladygoride.com` (marketing + driver sign-up) | `/home/mruptzat/public_html/ladygoride.com` | built from the phone-fix branch, uploaded by hand 25 Oct | Same source; only the dependency security updates are missing |
| `admin.ladygoride.com` (admin panel) | `/home/mruptzat/public_html/admin.ladygoride.com` | uploaded by hand 19 Oct | Same behaviour; changes since are type files, a dev-server-only fix, and the dependency updates |
| `driver.ladygoride.com` | `/home/mruptzat/public_html/driver.ladygoride.com` | **old copy of the website** (build from 19 Oct, before the email field and wizard) | **No: stale** |
| `api.ladygoride.com` | AWS server | deployed automatically (34290d5) | Yes |

Host facts: LiteSpeed on a shared cPanel account that also hosts other businesses' sites. **Plain FTP on port 21 has no encryption** (the server answers `AUTH TLS` with 504) and SFTP/SSH is closed, so FTP is **not** used. cPanel's **Web Disk (WebDAV over HTTPS, port 2078)** is open with a valid Let's Encrypt certificate and supports `PUT` and `MKCOL`; that is the upload route. Each site folder already has its own `.htaccess` (single-page routing and security headers) which the pipeline must never touch.

## 3. What is pending before the websites are fully live

| # | Item | Impact | Fix |
|---|---|---|---|
| P1 | **`www.ladygoride.com` shows a browser security warning.** Its certificate does not include the `www` name. | Anyone typing `www.` sees a scary warning and leaves. | Re-issue the certificate with the `www` name (cPanel AutoSSL), ticket W1 |
| P2 | **`driver.ladygoride.com` serves an old copy of the site** (the one without the email field and wizard). | A driver using that address gets the old, broken sign-in. | Decide: redirect it to `ladygoride.com/driver`, or deploy the same build there (question Q1) |
| P3 | `robots.txt` and `sitemap.xml` return the home page instead of real files. | Weak search-engine behaviour; Google may not index pages properly. | Add real files to `frontend/website/public`; they then deploy with the site |
| P4 | Website and admin deploys are manual. | Slow fixes, easy mistakes. | This plan |
| P5 | Live website build lacks the latest dependency security updates. | Low (build-time tools only). | The first automatic run ships them |
| P6 | Not yet reviewed by a person: the wording of Privacy, Terms, Safety, FAQ, Contact pages. My scan found no placeholder text, but I did not judge the content. | Legal and trust risk if the text is wrong. | Wamiq reviews each page before launch |

Already fine: HTTPS and security headers (HSTS, frame blocking, content-security policy) are set on the live site; the API accepts all four web origins; the email sign-in works; the redirect from `http` works.

## 4. Scope

**In:** automatic deploy of `frontend/website` to `ladygoride.com` and `frontend/admin` to `admin.ladygoride.com`; the decision for `driver.`; real `robots.txt` and `sitemap.xml`; the `www` certificate.
**Out:** the backend pipeline (done), mobile apps, a staging site, content writing.

## 5. Design (plain language)

```
merge to main (files under frontend/website or frontend/admin changed)
  -> for each changed site: install, lint, typecheck, tests, production build
        with VITE_API_BASE_URL=https://api.ladygoride.com, security-policy check
  -> upload to MilesWeb over HTTPS (cPanel Web Disk), using an account that can only see that site's folder
        1. new asset files first (old ones stay)
        2. keep a copy of the current index.html as index.html.prev
        3. replace index.html last
  -> check from outside: the live page and every file it uses are compared byte for byte with what was built
  -> if the check fails: put index.html.prev back and fail the run
```

- **Why Web Disk accounts, not FTP and not the cPanel API token I used by hand:** FTP here is unencrypted (passwords would cross the internet in clear text). A cPanel token is all-powerful for the whole account, which also hosts other businesses. A cPanel **Web Disk** account is encrypted (HTTPS) and can be locked to one folder, so a leaked password can only change one site. One account per site.
- **Where passwords live:** as **GitHub Environment secrets** in an environment called `website-production`, limited to the `main` branch (free for public repos), so no branch or fork can read them.
- **Never touched:** `.htaccess`, `.user.ini`, `php.ini`, `.well-known`, `cgi-bin`, and the other sites.
- **Old assets are kept** so a half-finished upload never breaks the live page; a later cleanup job can remove files not referenced by the current page.
- **Rollback:** revert the change on `main` (that push deploys the old code), or restore `index.html.prev` by hand in cPanel File Manager. `index.html.prev` is overwritten on every deploy, so roll back before re-running the same build.
- **Workflow safety:** pinned actions, values passed through environment variables, no `pull_request_target`, runs only on `main` and by hand. Review fixes built in: the install and build run in a separate job with **no secrets** (third-party package code can never read the passwords), and the deploy job runs only the upload script from a fresh checkout; each site uses only its own Web Disk account; a build containing `.htaccess`, `.ini` or `.php` files is refused; a failed `index.html` upload triggers a restore; every push deploys both sites so a skipped run cannot lose a change; passwords with `\` or `"` are escaped for curl.

## 6. HIGH-RISK items

1. **Passwords in CI.** Two long-lived passwords stored in GitHub. Mitigation: folder-limited Web Disk accounts, Environment secrets limited to `main`, password rotation steps in the README.
2. **Shared hosting account.** Other businesses' sites are in the same cPanel account. The Web Disk accounts must be created with the directory set to exactly the site folder; test by listing the account's root and confirming the other sites are not visible.
3. **Uploading over the live site.** Mitigation: assets first, `index.html` last, old files kept, automatic restore on a failed check.
4. **Wrong build target.** The build must use the real API address. Mitigation: the pipeline sets it, and the upload script refuses a build whose `index.html` mentions `localhost` or whose security policy and code lack the real API address. (Built code keeps `localhost` as an unused fallback string, so that text alone is not a reason to refuse.)
5. **A mistake in `www`/certificate work affects the whole account.** Mitigation: run AutoSSL only for the Lady Go domains and re-check all three Lady Go names afterwards.

## 7. Build steps and models

| # | Task | Model |
|---|---|---|
| B1 | `deploy-web.yml`: separate build (no secrets) and deploy (secrets) jobs, HTTPS upload, check, restore | Sonnet 5.5 |
| B2 | `scripts/ci/deploy-web.sh`: the upload and check logic, testable offline, with tests that fake the Web Disk host and curl | Sonnet 5.5 |
| B3 | Real `robots.txt` and `sitemap.xml` in the website's `public` folder | Haiku 5.5 |
| B4 | Review of workflow and script (`/security-review`, `/code-review`) | Opus 5.5 |
| B5 | Docs: how to rotate the Web Disk passwords, how to roll back, update the prod README | Haiku 5.5 |

Test as a user: first run by hand with the manual trigger, then open the live site in a private window on a phone-width screen, sign in with a fresh phone and email, and confirm the new asset names are served. Also run a failure drill: point the check at a wrong build and confirm `index.html.prev` is restored.

## 8. Web Admin tickets (for you to run; production, medium risk)

**W1. Fix the `www` certificate**
- Steps: cPanel, **SSL/TLS Status**, tick `ladygoride.com` and `www.ladygoride.com`, click **Run AutoSSL**. If `www.ladygoride.com` is not listed, add it as an alias in **Domains** (Aliases) pointing at the same folder.
- Acceptance: `https://www.ladygoride.com` loads with no warning. Owner: Wamiq. Risk: low.

**W2. Create the Web Disk account for the website**
- Steps: cPanel, **Files, Web Disk, Create an Account**: username `deploy-web`, domain `ladygoride.com`, strong generated password (save in your password manager), **Directory = `public_html/ladygoride.com`**, Permissions **Read-Write**, leave "Enable Digest Authentication" **off**.
- Acceptance: Web Disk lists it with that directory. Claude then tests it (read-only) and confirms only that site's files are visible. Owner: Wamiq. Risk: medium.

**W3. Create the Web Disk account for the admin site** (same as W2)
- Username `deploy-admin`, domain `ladygoride.com`, **Directory = `public_html/admin.ladygoride.com`**.

**W4. GitHub environment and secrets**
- Steps: repo Settings, Environments, New environment `website-production`; Deployment branches: Selected, `main` only. Add secrets `WEBDAV_HOST` (`emphasis.herosite.pro`), `WEBDAV_WEB_USER` (`deploy-web@ladygoride.com`), `WEBDAV_WEB_PASSWORD`, `WEBDAV_ADMIN_USER` (`deploy-admin@ladygoride.com`), `WEBDAV_ADMIN_PASSWORD`. Claude supplies a command that reads each password from your keyboard so it is never printed.
- Acceptance: secrets listed (names only) in the environment. Owner: Wamiq. Risk: medium.

**W5. `driver.ladygoride.com`**: depends on Q1 below.

## 9. Measuring the 60-day target

Deploy count, time to live, and failed-deploy count from GitHub Actions; adoption from the database (drivers who sign in, drivers who finish registration, approved drivers) weekly. I still need your 60-day target number.

## 10. Decisions and open question

- **Q1 decided (25 Oct): `driver.ladygoride.com` redirects permanently (301) to `https://ladygoride.com/driver`**, keeping the rest of the path (so `driver.ladygoride.com/login` lands on `ladygoride.com/driver/login`). Done in cPanel Redirects (ticket W5), not by the pipeline. The old files in that folder stay untouched.
- **Q2 decided (25 Oct): website and admin both deploy automatically** (W2 and W3 both needed).
- **Still open:** your 60-day adoption target number.

**W5. Redirect `driver.ladygoride.com`**
- Steps: cPanel, **Domains**, **Redirects**: type Permanent (301), source `driver.ladygoride.com`, destination `https://ladygoride.com/driver`, redirect with or without www: only the exact domain, **Wild Card Redirect** on. Do not edit `.htaccess` by hand.
- Acceptance: `curl -sI https://driver.ladygoride.com/login` shows `301` and `location: https://ladygoride.com/driver/login`. Owner: Wamiq. Risk: low.
