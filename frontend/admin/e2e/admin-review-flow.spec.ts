import { expect, test } from '@playwright/test'
import { readE2eEnvOrThrowInCi } from './env.js'
import { loginAs, openDriverReview } from './helpers.js'

const env = readE2eEnvOrThrowInCi()

test.describe('driver review flow', () => {
  test.skip(!env, 'ADMIN_WEB_E2E_* env vars not set - see scripts/ci/e2e-seed.sh and the CI workflow')

  test('logs in with MFA and reaches the dashboard', async ({ page }) => {
    await loginAs(page, env!.superAdmins.LOGIN)
    await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible()
  })

  test('the captain queue lists the seeded captain with item counts and opens the review page', async ({ page }) => {
    await loginAs(page, env!.superAdmins.QUEUE)
    await page.getByRole('link', { name: 'Verification' }).click()
    await expect(page.getByRole('heading', { name: 'Driver verification' })).toBeVisible()

    // One row per captain, not per document: the seeded captain appears once, with a wait time and item counts.
    const row = page.locator('tr', { has: page.locator(`a[href="/driver-verification/${env!.driverId}"]`) })
    await expect(row).toHaveCount(1)
    await expect(row).toContainText(/\d+ (pending|approved|rejected|missing)/)
    await expect(row.locator('.age-badge')).toBeVisible()

    // A search that matches nobody says so plainly instead of showing an empty table.
    await page.getByLabel('Search').fill('no-such-captain-zzz')
    await page.getByRole('button', { name: 'Search' }).click()
    await expect(page.getByText('No captains match these filters.')).toBeVisible()

    // Searching by the driver id finds exactly the seeded captain, and Review opens the review page.
    await page.getByLabel('Search').fill(env!.driverId)
    await page.getByRole('button', { name: 'Search' }).click()
    await page.getByRole('link', { name: 'Review' }).click()
    await expect(page).toHaveURL(new RegExp(`/driver-verification/${env!.driverId}$`))
    await expect(page.getByText('JK01AB1234')).toBeVisible()
  })

  test('the document queue is still reachable from the Documents tab', async ({ page }) => {
    await loginAs(page, env!.superAdmins.QUEUE)
    await page.getByRole('link', { name: 'Verification' }).click()
    await page.getByRole('link', { name: 'Documents' }).click()
    await expect(page).toHaveURL(/\/driver-verification\/documents$/)
    // Every row for this captain's several documents links to the same driver id - any one proves it is listed.
    await expect(page.locator(`a[href="/driver-verification/${env!.driverId}"]`).first()).toBeVisible()
  })

  test('the captain queue fits a 375px phone with no sideways scrolling', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 })
    await loginAs(page, env!.superAdmins.QUEUE)
    await page.goto('/driver-verification')
    await expect(page.locator(`a[href="/driver-verification/${env!.driverId}"]`).first()).toBeVisible()
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(overflow).toBeLessThanOrEqual(0)
  })

  test('views a watermarked document, approves every document and the vehicle, and approves the driver', async ({ page }) => {
    // Deliberately no rejection here: once a required document type is REJECTED, there is no "re-
    // approve" button in the UI (by design - only a fresh resubmission from the driver clears it), so
    // a driver can never reach "Ready for approval" again in the same session. The reject-with-reason
    // behaviour (status flips, note renders as literal text) is covered separately by security-
    // checks.spec.ts's "markup in a rejection reason" test, against the secondary driver, which this
    // test never touches.
    test.setTimeout(60_000)
    await loginAs(page, env!.superAdmins.REVIEW)
    await openDriverReview(page, env!.driverId)

    await expect(page.getByText('JK01AB1234')).toBeVisible()

    const documentRows = page.locator('li.document-row')
    const firstView = documentRows.first().getByRole('button', { name: 'View' })
    await firstView.click()
    const image = documentRows.first().locator('img')
    await expect(image).toBeVisible()
    await expect(image).toHaveAttribute('src', /driver-document-derivatives/)

    const approveVehicle = page.getByRole('button', { name: 'Approve vehicle' })
    if (await approveVehicle.isVisible()) {
      await approveVehicle.click()
    }

    for (const row of await documentRows.all()) {
      const approveButton = row.getByRole('button', { name: 'Approve' })
      if (await approveButton.isVisible()) {
        await approveButton.click()
      }
    }

    await expect(page.getByText('Ready for approval')).toBeVisible({ timeout: 15_000 })
    await page.getByRole('button', { name: 'Approve driver' }).click()
    // Every document row and the vehicle line already say "APPROVED" too by this point, so a bare
    // getByText('APPROVED') is ambiguous - the driver's own status line is the first .muted paragraph
    // on the page (DriverReview.tsx renders it before the vehicle panel).
    await expect(page.locator('p.muted').first()).toContainText('APPROVED')
  })

  test('idle timeout signs the admin out automatically', async ({ page }) => {
    test.setTimeout(100_000)
    await loginAs(page, env!.superAdmins.IDLE)
    // idleTimeout.ts's 15-minute default is overridden short for this whole suite (VITE_E2E_IDLE_
    // TIMEOUT_MS, set by the CI workflow) - a real 15-minute wait is not viable in CI. Set generously
    // above the other tests' own normal action-to-action gaps (network round trips to a real backend)
    // so only a genuinely idle page - like this explicit no-interaction wait - triggers it.
    await expect(page).toHaveURL(/\/login$/, { timeout: 75_000 })
  })

  test('sign out clears the session and blocks returning to a protected page', async ({ page }) => {
    await loginAs(page, env!.superAdmins.SIGNOUT)
    await page.getByRole('button', { name: 'Sign out' }).click()
    await expect(page).toHaveURL(/\/login$/)

    await page.goto('/dashboard')
    await expect(page).toHaveURL(/\/login$/)
  })
})
