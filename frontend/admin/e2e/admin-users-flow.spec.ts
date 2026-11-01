import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import { readE2eEnvOrThrowInCi } from './env.js'
import { confirmStepUp, loginAs } from './helpers.js'

const env = readE2eEnvOrThrowInCi()

test.describe('staff accounts: invite and role change', () => {
  test.skip(!env, 'ADMIN_WEB_E2E_* env vars not set - see scripts/ci/e2e-seed.sh and the CI workflow')

  test('a SUPER_ADMIN invites a SUPPORT user, the one-time link sets a password, then changes the role', async ({ page }) => {
    const creds = env!.superAdmins.STAFFMGMT
    const email = `e2e.invitee.${Date.now()}@example.test`

    await loginAs(page, creds)
    await page.getByRole('link', { name: 'Staff' }).click()
    await expect(page.getByRole('heading', { name: 'Staff accounts' })).toBeVisible()

    const form = page.getByRole('form', { name: 'Invite a staff member' })
    await form.getByLabel('Full name').fill('E2E Invitee')
    await form.getByLabel('Email').fill(email)
    await form.getByLabel('Role').selectOption('SUPPORT')
    const send = form.getByRole('button', { name: 'Send invitation' })
    await expect(send).toBeDisabled() // a reason is required.
    await form.getByLabel('Reason (required)').fill('e2e staff-management test')
    await send.click()
    await confirmStepUp(page, creds) // login is not a step-up.

    await expect(page.getByRole('status')).toContainText(`Invitation sent to ${email}`)
    const row = page.locator('tbody tr').filter({ hasText: 'E2E Invitee' })
    await expect(row).toContainText('Invited')
    await expect(row).toContainText('SUPPORT')
    await expect(row).not.toContainText(email) // the list shows a masked address only.

    // The dev email sender logs the message; CI exposes that log as BACKEND_LOG. Follow the link when it is there.
    const logPath = process.env.BACKEND_LOG
    if (logPath) {
      const line = readFileSync(logPath, 'utf8').split('\n').reverse().find((l) => l.includes(`to=${email}`))
      const link = line?.match(/https?:\/\/\S+\/reset-password#token=[A-Za-z0-9_-]+/)?.[0]
      expect(link, 'invite link in the dev email log').toBeTruthy()
      const other = await page.context().newPage()
      await other.goto(link!)
      await other.getByLabel('New password (at least 12 characters)').fill('E2e-invitee-passphrase-1')
      await other.getByLabel('Repeat the new password').fill('E2e-invitee-passphrase-1')
      await other.getByRole('button', { name: /password/i }).click()
      await expect(other.getByRole('status')).toContainText('Your password was changed')
      await other.goto(link!) // single use: the same link must not work twice.
      await other.getByLabel('New password (at least 12 characters)').fill('E2e-invitee-passphrase-2')
      await other.getByLabel('Repeat the new password').fill('E2e-invitee-passphrase-2')
      await other.getByRole('button', { name: /password/i }).click()
      await expect(other.getByRole('alert')).toContainText('invalid, expired or already used')
      await other.close()
    }

    await row.getByRole('link', { name: 'E2E Invitee' }).click()
    await expect(page.getByRole('heading', { name: 'E2E Invitee' })).toBeVisible()
    await page.getByRole('button', { name: 'Change role' }).click()
    await page.getByLabel('New role').selectOption('ADMIN')
    const confirm = page.getByRole('button', { name: 'Confirm change role' })
    await expect(confirm).toBeDisabled()
    await page.getByLabel('Reason (required)').fill('e2e promotion')
    await confirm.click() // the step-up from the invite is still fresh.

    await expect(page.getByRole('group', { name: 'Change role' })).not.toBeVisible()
    await expect(page.locator('dd', { hasText: /^ADMIN$/ })).toBeVisible()
  })
})
