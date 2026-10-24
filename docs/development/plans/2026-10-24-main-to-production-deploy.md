# Plan: push to `main` goes live (backend auto-deploy)

Status: **DRAFT, waiting for Wamiq's approval. No code or AWS change has been made.**
Date: 2026-10-24. Author: Claude (Opus-level planning; build tasks below name the model to use).

## 1. Goal and 60-day target

**Goal.** When code is merged into `main`, it is tested, built, and running on `api.ladygoride.com` within about 10 minutes, with no manual steps on the server, and with an automatic rollback if the new version is unhealthy.

**Why this serves the 60-day adoption number.** Success is how many people use Lady Go in the first 60 days. Today the live server runs the 23 Oct build, so the driver app account area (earnings, rides, help) would error against live. Every day a finished feature sits undeployed is a day users cannot use it. A one-step, reversible deploy lets us ship fixes the same day a driver reports a problem.

**Targets for this work (proposed, edit if you disagree):**
- Merge to `main` to live: under 10 minutes.
- A bad deploy rolls itself back in under 3 minutes.
- Any person can answer "what version is live?" in 10 seconds (one URL).
- Adoption metric we track alongside: approved drivers and completed rides per week (you give the launch target number; see section 11).

## 2. What exists today (verified 24 Oct)

| Item | Fact |
|---|---|
| Server | One EC2 box `ladygo-api` (`i-06120241f85e1f72d`, t4g.small, arm64, Ubuntu 24.04, region ap-south-1) |
| Running | Docker Compose: backend, postgres, redis, caddy (HTTPS for `api.ladygoride.com`) |
| Live backend | Image `ladygo-backend:local` built 23 Oct 17:55 UTC. Missing 4 backend commits from 24 Oct |
| How deploys work | By hand: build on a Mac, `docker save \| ssh ... docker load`, `docker compose up -d backend` |
| Version visible? | No. `/actuator/info` returns `{}` and the image tag `local` has no commit info |
| CI | GitHub Actions run tests only (`backend.yml` etc.). Nothing deploys |
| `main` | At 4 Oct, **133 commits behind `develop`**. Not production today |
| AWS | No ECR repository. Server has IAM role `ladygo-api-profile`. SSM Run Command is not enabled on the server. SSH (port 22) is open to the whole internet |
| Website and admin | `ladygoride.com` resolves to `103.191.208.227`, a different host from the API. Where it runs and how it is deployed is **not known to me** |

## 3. Users and their problem

- **Primary user: you (and later any teammate).** Problem: deploying means building on your Mac, copying a big file over SSH, and typing server commands. It is slow, easy to get wrong, and nobody can tell what is live.
- **End users: drivers and riders.** Problem: features and fixes reach them late, or never, and a mistaken manual deploy can take the API down.

## 4. Scope

**In scope (this plan)**
1. Backend (Spring Boot API) auto-deploys from `main`.
2. Live version visible at `/actuator/info` (git SHA and build time).
3. Automatic database backup before each deploy.
4. Health check after deploy, automatic rollback on failure, manual rollback command.
5. Branch protection so only tested code reaches `main`.
6. Close public SSH (port 22) once SSM works.

**Out of scope (separate plans, ask me later)**
- Website (`frontend/website`) and admin (`frontend/admin`) deploys. They live on a different host and I need to know which (section 12, question 2).
- Mobile app releases (Play Store / App Store).
- A staging environment (no staging server exists; costs money; recommended before ride booking opens, see section 10).
- Zero-downtime blue/green. A restart takes about 20-30 seconds; acceptable at current traffic.

## 5. Chosen design (plain language)

Think of it as a conveyor belt. You merge to `main`. GitHub does the rest:

```
merge to main
   -> GitHub runs all tests (mvn verify)            [fails: stop, nothing deployed]
   -> GitHub builds a Docker image for the arm64 server, tags it with the git SHA
   -> image is pushed to ECR (AWS's private image store)
   -> GitHub asks the server (through AWS SSM) to:
        1. back up the database
        2. pull the new image
        3. restart only the backend container
        4. wait up to 2 minutes for /actuator/health = UP and /actuator/info = new SHA
        5. if unhealthy: put the previous image back automatically
```

**Why ECR + SSM, not "SSH from GitHub"**
- No long-lived password or SSH key stored in GitHub. GitHub proves who it is to AWS using a short-lived token (OIDC).
- We can then **close port 22 to the internet**, removing the most common way servers get attacked.
- Cost: ECR storage for a few small images is cents per month. SSM is free.
- Trade-off: more one-time AWS setup (tickets below). Fallback if you prefer fewer AWS changes: GitHub deploys over SSH with a dedicated key. Simpler to set up, but port 22 stays reachable from GitHub's changing IP ranges, so it is less secure. I recommend the SSM route.

**Image naming.** Images are tagged with the commit SHA (for example `ladygo-backend:6306330`), never `latest` or `local`. The compose file reads the tag from a variable `BACKEND_IMAGE`. Rollback means "point at the previous tag".

## 6. HIGH-RISK items (flagged)

1. **First merge deploys 133 commits at once**, including 4 backend commits never run in production (earnings, ratings, back-fills, support contact). Mitigation: do the first deploy by hand-triggered run with the DB backup, and check the env var below before it.
2. **Database migrations (Liquibase) run on boot and cannot be undone by swapping the image.** A rollback restores old code, but a migration that dropped or renamed a column stays applied. Mitigation: pre-deploy DB backup; a rule that migrations must be backward compatible (add, never drop/rename in the same release); the rollback step documents when a restore is needed.
3. **Server layout assumptions.** The deploy script expects `docker-compose.yml`, `.env` and `backup-db.sh` in `/opt/ladygo`. (Checked: `a564de1` needs no new `.env` variable; the support numbers are optional admin settings in the database.) Mitigation: ticket T1 checks the layout before the first deploy; the script refuses to deploy without a working backup; auto-rollback also protects us.
4. **Production change.** All work touches the live server and AWS permissions. Every AWS ticket is least-privilege and reviewed before running.
5. **No unbounded DELETE/UPDATE and no hardcoded credentials** are introduced. Secrets stay in `/opt/ladygo/.env` on the server and GitHub holds no AWS keys. No money movement is touched (payments are not live).
6. **Auto-deploy means a bad merge reaches users fast.** Mitigation: branch protection (tests must pass), health-gated rollback, and an optional "approve before deploy" gate (section 12, question 3).

## 7. UI/UX flow (for you, the operator)

1. Open a pull request from `develop` into `main`. GitHub shows green or red checks.
2. Click Merge. Open the repo's **Actions** tab: a run called "Deploy backend" starts.
3. Watch it: Test, Build, Push, Deploy, Verify. Each is a green tick or red cross.
4. Success: the last step prints "Live version: `<sha>`". You can also open `https://api.ladygoride.com/actuator/info`.
5. Failure: the run goes red, the server is put back on the previous version automatically, and GitHub emails you.
6. Manual rollback: Actions tab, "Deploy backend", **Run workflow**, enter an older SHA, click the green button.

## 8. Data and API design

- **New API surface:** none for users. `/actuator/info` (already public, already returns `{}`) will return `{"git":{"commit":{"id":"..."}},"build":{"time":"..."}}`. This reveals only a commit ID and time, no secrets. Check it in the security review.
- **Backend change:** add Spring Boot build-info and git-properties generation in `pom.xml`; enable the `info` contributors in `application-prod` config.
- **Dockerfile:** pass the SHA in as a build argument so it lands in the image.
- **Compose change** (`infrastructure/prod/docker-compose.yml`): `image: ${BACKEND_IMAGE:?missing}` instead of `ladygo-backend:local`.
- **Server scripts** (new, `infrastructure/prod/`): `deploy.sh` (backup, pull, swap, verify, rollback) and a small file `/opt/ladygo/current-image` remembering the running and previous tags. The existing `backup-db.sh` is reused.
- **New workflow:** `.github/workflows/deploy-backend.yml`, triggered by push to `main` when `backend/**` or `infrastructure/prod/**` changes, plus manual run.

## 9. Security and compliance

- GitHub to AWS uses OIDC with a role restricted to this repository and the `main` branch only. No access keys stored.
- The deploy role may only: push to the one ECR repo, and send the one SSM document to this one instance. Nothing else.
- Server role gets read-only ECR pull and the SSM managed-instance policy. It already has S3 and SES access; unchanged.
- Close SSH to the world after SSM is verified (keep a way in: restrict port 22 to your own IP, or use SSM Session Manager).
- Actions pinned to commit SHAs (as the existing workflows already do). Run the `/security-review` skill on the workflow and scripts before merge.
- Branch protection on `main`: pull request required, backend/frontend checks must pass, no force-push.
- Logs: the deploy script must not echo secrets or the contents of `.env`.

## 10. Build steps (in order, each followed by a real user-level run)

| # | Task | Model | Why this model |
|---|---|---|---|
| B1 | Add build-info and git SHA to the backend and `/actuator/info`; Dockerfile build arg. Test: run locally, open `/actuator/info`, see the SHA | Sonnet 5.5 | Small feature code |
| B2 | Change compose to `${BACKEND_IMAGE}`; write `deploy.sh` (backup, pull, swap, health check, rollback) and a test script that fakes a failing health check | Sonnet 5.5 | Scripting and tests |
| B3 | Write `deploy-backend.yml` (test, build arm64, push to ECR, SSM deploy, verify). Build runs on GitHub's arm64 runner if the repo allows it, otherwise QEMU emulation (slower, about 10-15 min) | Sonnet 5.5 | CI authoring |
| B4 | Review of B1-B3 (`/code-review`, `/security-review`, `verification-before-completion`) | Opus 5.5 | Reviewing prod and security paths |
| B5 | Docs: update `infrastructure/prod/README.md` (new deploy and rollback), drop the manual `docker save` section | Haiku 5.5 | Mechanical edit |

Test steps as a user would:
1. **Local:** build the image, start the stack, hit `/actuator/info` and `/actuator/health`.
2. **Failure drill:** deploy an image built to fail its health check; confirm the server returns to the previous image and the run goes red.
3. **Real run:** first live deploy (section 10b) then call the new endpoints from the driver app on a phone-sized screen and confirm earnings and help tabs load.
4. **Rollback drill:** run the manual rollback to the previous SHA and confirm `/actuator/info` shows it.

**10b. First live deploy (manual trigger, not on merge):** after T1-T6 are done and B1-B5 reviewed, merge `develop` to `main`, with branch protection already on. Watch the run end to end. Keep this SSH session open as a backup.

**Staging (recommended later, not now):** there is no staging server. Before ride booking (money) opens, add a small second instance or a second compose project so risky changes can be tried first. I will raise this again then.

## 11. Deploy, rollback, launch and measurement

- **Deploy:** as in section 5. **Rollback:** automatic on failed health check; manual by re-running the workflow with an older SHA. If a migration is the cause, restore the pre-deploy backup (steps in the new README).
- **Launch note:** this is internal tooling, so no marketing. The user-facing win is that the driver account area goes live; announce it to current drivers once the first deploy passes.
- **How we measure the 60-day target:** the deploy pipeline reports lead time and failure count (visible in Actions). Adoption is measured separately: approved drivers, active drivers per week, completed rides per week. I suggest a weekly adoption report with `/schedule` and a dashboard artifact once you give me the numbers and where the data lives (the database). **I need your 60-day target number from you** (for example "200 approved drivers").

## 12. Questions I need answered before building

1. **Approach:** SSM + ECR (recommended) or SSH from GitHub (fewer AWS steps, less secure)?
2. **Website and admin:** where is `ladygoride.com` hosted (the `103.191.208.227` host) and how is it deployed today? I will plan those as a second phase.
3. **Gate:** deploy immediately on merge (what you asked for), or add a one-click "approve" in GitHub for the first few weeks? I recommend immediate deploy, protected by tests and auto-rollback.
4. **Your 60-day adoption target number.**

## 13. Web Admin tickets (drafted for you to paste and run; staging first does not apply, there is no staging, so each says "production, low-risk")

Follow the Dev Desk lifecycle: Ready for test, In testing/UAT, Test Failed, Ready To Deploy.

**T1. Check the server layout before the first deploy** (revised: `a564de1` needs no `.env` change; the support phone and WhatsApp numbers are optional admin settings stored in the database, `support.phone` and `support.whatsapp`, and the Help screen simply hides them until set)
- Why: the deploy script expects `docker-compose.yml`, `.env` and `backup-db.sh` in `/opt/ladygo`.
- Steps: SSH in and run `ls -l /opt/ladygo` and `cat /etc/cron.d/ladygo-backup`.
- Acceptance: the three files exist, `backup-db.sh` is executable, `.env` is mode `600`.
- Owner: Wamiq. Environment: production. Risk: none (read-only).

**T2. Create the ECR repository**
- Why: stores the built images.
- Steps: AWS Console, region ap-south-1, ECR, Create repository, private, name `ladygo-backend`, tag immutability on, scan on push on. Add a lifecycle rule: keep the last 20 images.
- Acceptance: repository exists; immutability shows "Enabled".
- Owner: Wamiq. Environment: production AWS account. Risk: low.

**T3. Let the server use SSM and pull from ECR**
- Why: lets GitHub run the deploy without SSH.
- Steps: IAM, Roles, `ladygo-api-profile` role, Add permissions: attach `AmazonSSMManagedInstanceCore` and `AmazonEC2ContainerRegistryReadOnly`. Wait 5 minutes. EC2, Instances, check the instance appears in Systems Manager, Fleet Manager as "Online".
- Acceptance: instance is Online in Fleet Manager; on the server `docker login` to ECR works (Claude provides the one-line command).
- Owner: Wamiq. Environment: production. Risk: low-medium (adds permissions to the live server role).

**T4. Create the GitHub-to-AWS deploy role**
- Why: lets only this repo's `main` branch deploy.
- Steps: IAM, Identity providers, add `token.actions.githubusercontent.com` (audience `sts.amazonaws.com`). Create role with a trust policy limited to the repo and `refs/heads/main`. Attach a policy allowing ECR push to the one repository and `ssm:SendCommand` and `ssm:GetCommandInvocation` on the one instance. Claude will supply the exact JSON.
- Acceptance: role ARN given to Claude; policy shows no `*` resources except where AWS requires it.
- Owner: Wamiq. Environment: production AWS. Risk: medium (security boundary), review the JSON before saving.

**T5. GitHub settings**
- Why: protect `main`.
- Steps: repo Settings, Branches, add rule for `main`: require a pull request, require status checks (Backend, Frontend), block force pushes. Settings, Environments, create `production`, then under "Deployment branches and tags" choose "Selected branches and tags" and allow only `main` (this is the real gate: the AWS role trusts the environment, not the branch). Add reviewers only if you chose the approval gate. Add the role ARN as a repository variable `AWS_DEPLOY_ROLE_ARN`.
- Acceptance: a test push straight to `main` is rejected.
- Owner: Wamiq. Risk: low.

**T6. Close public SSH (after the first successful SSM deploy and rollback drill)**
- Why: removes internet-wide access to port 22.
- Steps: EC2, Security Groups, the group on `ladygo-api`, Edit inbound rules, change port 22 source from `0.0.0.0/0` to My IP (or delete the rule and use SSM Session Manager).
- Acceptance: SSH from a phone hotspot is refused; SSM Session Manager still opens a shell.
- Owner: Wamiq. Risk: medium (you could lock yourself out; do T3 and test SSM Session Manager first).

## 14. Where Claude features help

- `brainstorming`: already done in this plan; `writing-plans` covered by this document.
- `test-driven-development`: for `deploy.sh` rollback logic (write the failing-health-check test first).
- `/security-review` and `/code-review`: required before merge, because this controls production.
- `/loop` or `/schedule`: after launch, a weekly adoption report from the database.
- Parallel sessions: B1 (backend) and B3 (workflow) are independent, so they can run side by side once you approve.

## 15. Update (24 Oct): free-plan GitHub limits

The repo is private on GitHub Free, so branch protection rules are **not enforced** and environment deployment-branch rules are not available. The branch gate therefore lives in AWS: the deploy job has no `environment:`, and the role `ladygo-github-deploy` trusts only `repo:Lady-Go/lady-go:ref:refs/heads/main`. Anyone who can push to `main` can still deploy, so keep write access small until the org is on a paid plan (GitHub Team) where protection and required reviews are enforced. The tests still gate every deploy (the deploy job needs the test job to pass).
