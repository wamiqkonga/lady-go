import { expect, test } from '@playwright/test'
import { readE2eEnvOrThrowInCi } from './env.js'
import { loginAs } from './helpers.js'

const env = readE2eEnvOrThrowInCi()

test.describe('safety queue', () => {
  test.skip(!env, 'ADMIN_WEB_E2E_* env vars not set - see scripts/ci/e2e-seed.sh and the CI workflow')

  test('an open SOS is acknowledged, noted and resolved with an outcome', async ({ page }) => {
    test.setTimeout(60_000)
    await loginAs(page, env!.superAdmins.SAFETY)

    // The navigation badge counts the unresolved incident.
    await expect(page.getByRole('navigation', { name: 'Admin' }).getByLabel('1 unresolved')).toBeVisible()

    // A critical SOS shows a red banner on every page, with a link to the queue.
    const banner = page.locator('.sos-banner')
    await expect(banner).toContainText('SOS open: 1')
    await banner.getByRole('link', { name: 'Open the queue' }).click()
    await expect(page).toHaveURL(/\/safety$/)
    await expect(page.getByRole('heading', { name: 'Safety', exact: true })).toBeVisible()
    await expect(page.getByRole('alert').filter({ hasText: 'call 112' })).toBeVisible()
    const row = page.locator('tbody tr').filter({ has: page.getByRole('link', { name: 'SOS' }) })
    await expect(row.getByText('CRITICAL')).toBeVisible()
    await expect(row.getByText(/\*{4,}7003/)).toBeVisible() // the rider, last 4 of +919900077003

    await row.getByRole('link', { name: 'SOS' }).click()
    await expect(page).toHaveURL(new RegExp(`/safety/${env!.safetyIncidentId}$`))
    await expect(page.getByText('E2E: the driver took a wrong turn')).toBeVisible()
    // Never a full phone number anywhere on the page.
    expect(await page.locator('body').innerText()).not.toMatch(/\+?919900077003/)

    await page.getByRole('button', { name: 'Acknowledge' }).click()
    await expect(page.getByText('Incident acknowledged.')).toBeVisible()
    await expect(page.getByText(/Acknowledged/).first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Acknowledge' })).not.toBeVisible()

    await page.getByLabel('Add a note').fill('Called the rider, she is safe.')
    await page.getByRole('button', { name: 'Add note' }).click()
    await expect(page.getByText('Called the rider, she is safe.')).toBeVisible()

    // Resolving needs an outcome.
    const resolve = page.getByRole('button', { name: 'Resolve incident' })
    await expect(resolve).toBeDisabled()
    // "Other" cannot be resolved without a note.
    await page.getByLabel('Resolve with outcome').selectOption('OTHER')
    await resolve.click()
    await expect(page.getByText(/required when the outcome is Other/)).toBeVisible()
    await page.getByLabel('Resolve with outcome').selectOption('FALSE_ALARM')
    await resolve.click()
    // The banner and menu badge update at once, not after the 30 s refresh.
    await expect(page.getByText('Incident resolved.')).toBeVisible()
    await expect(banner).toBeHidden({ timeout: 5_000 })
    await expect(page.getByText(/Resolved/).first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Resolve incident' })).not.toBeVisible()

    // It leaves the open queue and appears under Resolved.
    await page.getByRole('link', { name: '← Safety queue' }).click()
    await expect(page.getByText('No open safety incidents.')).toBeVisible()
    await page.getByRole('button', { name: 'Resolved' }).click()
    await expect(page.getByRole('link', { name: 'SOS' })).toBeVisible()
  })
})
