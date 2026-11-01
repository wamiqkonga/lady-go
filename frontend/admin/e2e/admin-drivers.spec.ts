import { expect, test } from '@playwright/test'
import { readE2eEnvOrThrowInCi } from './env.js'
import { confirmStepUp, loginAs } from './helpers.js'

const env = readE2eEnvOrThrowInCi()

test.describe('Drivers screen', () => {
  test.skip(!env, 'ADMIN_WEB_E2E_* env vars not set - see scripts/ci/e2e-seed.sh and the CI workflow')

  test('lists and searches drivers, then suspends and re-activates one with a reason and a step-up', async ({ page }) => {
    test.setTimeout(90_000)
    const creds = env!.superAdmins.DRIVERS
    await loginAs(page, creds)
    await page.getByRole('link', { name: 'Drivers' }).click()
    await expect(page.getByRole('heading', { name: 'Drivers' })).toBeVisible()

    // The list shows the seeded captains; the phone is always masked.
    await expect(page.locator('tbody tr').first()).toBeVisible()
    expect(await page.locator('body').innerText()).not.toMatch(/\+?919900077104/)

    // Search by name narrows the list; a search with no match says so.
    await page.getByLabel('Search').fill('Suspendable')
    await page.getByRole('button', { name: 'Search' }).click()
    const row = page.locator('tbody tr').filter({ hasText: 'E2E Suspendable Captain' })
    await expect(row).toHaveCount(1)
    await expect(row.getByText(/\*{4,}7104/)).toBeVisible()
    await page.getByLabel('Search').fill('no-such-captain-xyz')
    await page.getByRole('button', { name: 'Search' }).click()
    await expect(page.getByText('No drivers match.')).toBeVisible()

    // Search by id opens straight to the detail page.
    await page.getByLabel('Search').fill(env!.suspendDriverId)
    await page.getByRole('button', { name: 'Search' }).click()
    await page.getByRole('link', { name: 'E2E Suspendable Captain' }).click()
    await expect(page.getByRole('heading', { name: 'E2E Suspendable Captain' })).toBeVisible()
    await expect(page.getByText(/APPROVED/).first()).toBeVisible()
    await expect(page.getByRole('link', { name: 'Open document review' })).toHaveAttribute(
      'href',
      `/driver-verification/${env!.suspendDriverId}`,
    )

    // Suspend needs a reason first, then the authenticator step-up.
    await page.getByRole('button', { name: 'Suspend driver' }).click()
    await page.getByRole('button', { name: 'Confirm suspend' }).click()
    await expect(page.getByRole('alert')).toContainText('Enter a reason')
    await page.getByLabel('Reason').fill('e2e: complaint verified')
    await page.getByRole('button', { name: 'Confirm suspend' }).click()
    await confirmStepUp(page, creds)
    await expect(page.getByText(/SUSPENDED · SUSPENDED/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Suspend driver' })).toHaveCount(0)

    // The status filter finds the suspended driver in the list.
    await page.getByRole('link', { name: '← Drivers' }).click()
    await page.getByLabel('Status').selectOption('SUSPENDED')
    await page.getByLabel('Search').fill('Suspendable')
    await page.getByRole('button', { name: 'Search' }).click()
    await expect(page.locator('tbody tr').filter({ hasText: 'E2E Suspendable Captain' })).toHaveCount(1)

    // Re-activate: the step-up from a moment ago is still fresh, so only the reason is asked.
    await page.getByRole('link', { name: 'E2E Suspendable Captain' }).click()
    await page.getByRole('button', { name: 'Re-activate driver' }).click()
    await page.getByLabel('Reason').fill('e2e: cleared after review')
    await page.getByRole('button', { name: 'Confirm activate' }).click()
    await expect(page.getByText(/APPROVED · OFFLINE/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Suspend driver' })).toBeVisible()
  })
})
