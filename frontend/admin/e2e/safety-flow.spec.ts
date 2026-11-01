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

    await page.getByRole('link', { name: 'Safety' }).click()
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
    await expect(page.getByText(/Acknowledged/).first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Acknowledge' })).not.toBeVisible()

    await page.getByLabel('Add a note').fill('Called the rider, she is safe.')
    await page.getByRole('button', { name: 'Add note' }).click()
    await expect(page.getByText('Called the rider, she is safe.')).toBeVisible()

    // Resolving needs an outcome.
    const resolve = page.getByRole('button', { name: 'Resolve incident' })
    await expect(resolve).toBeDisabled()
    await page.getByLabel('Resolve with outcome').selectOption('FALSE_ALARM')
    await resolve.click()
    await expect(page.getByText(/Resolved/).first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Resolve incident' })).not.toBeVisible()

    // It leaves the open queue and appears under Resolved.
    await page.getByRole('link', { name: '← Safety queue' }).click()
    await expect(page.getByText('No open safety incidents.')).toBeVisible()
    await page.getByRole('button', { name: 'Resolved' }).click()
    await expect(page.getByRole('link', { name: 'SOS' })).toBeVisible()
  })
})
