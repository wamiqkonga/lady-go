import { expect, test } from '@playwright/test'
import { readE2eEnvOrThrowInCi } from './env.js'
import { confirmStepUp, loginAs } from './helpers.js'

const env = readE2eEnvOrThrowInCi()

test.describe('customers screen', () => {
  test.skip(!env, 'ADMIN_WEB_E2E_* env vars not set - see scripts/ci/e2e-seed.sh and the CI workflow')

  // Uses the seeded ride-oversight customer (+919900077003): suspended with a reason and a step-up, then
  // re-activated, so the account is back to ACTIVE for every other spec.
  test('search, suspend with a reason and step-up, then re-activate', async ({ page }) => {
    await loginAs(page, env!.superAdmins.CUSTOMERS)
    await page.getByRole('link', { name: 'Customers' }).click()
    await expect(page.getByRole('heading', { name: 'Customers' })).toBeVisible()

    await page.getByPlaceholder(/Name, phone or customer id/).fill('7003')
    await page.getByRole('button', { name: 'Search' }).click()
    const row = page.locator('tbody tr').filter({ hasText: /\*{4,}7003/ })
    await expect(row).toHaveCount(1)
    // Never a full phone number.
    expect(await page.locator('body').innerText()).not.toMatch(/\+?91990007700\d/)

    await row.getByRole('link').click()
    await expect(page.getByText('ACTIVE', { exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Ride history' })).toBeVisible()
    await expect(page.locator('tbody tr').filter({ hasText: 'COMPLETED' })).toHaveCount(1)

    // A reason is required before the button is usable.
    await page.getByRole('button', { name: 'Suspend customer' }).click()
    const confirm = page.getByRole('button', { name: 'Suspend customer' })
    await expect(confirm).toBeDisabled()
    await page.getByLabel(/Reason/).fill('e2e: repeated abuse')
    await confirm.click()
    await confirmStepUp(page, env!.superAdmins.CUSTOMERS)
    await expect(page.locator('.status-badge')).toHaveText('SUSPENDED')

    await page.getByRole('button', { name: 'Re-activate customer' }).click()
    await page.getByLabel(/Reason/).fill('e2e: appeal accepted')
    await page.getByRole('button', { name: 'Re-activate customer' }).click()
    await expect(page.locator('.status-badge')).toHaveText('ACTIVE')

    // Both actions are in the audit log.
    await page.getByRole('link', { name: 'Audit log' }).click()
    await page.getByPlaceholder(/CONFIGURATION_CHANGED/).fill('CUSTOMER_SUSPENDED')
    await page.getByRole('button', { name: 'Apply' }).click()
    await expect(page.locator('tbody tr').filter({ hasText: 'CUSTOMER_SUSPENDED' }).first()).toBeVisible()
  })
})
