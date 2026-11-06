import { expect, test, type Locator } from '@playwright/test'
import { readE2eEnvOrThrowInCi } from './env.js'
import { loginAs } from './helpers.js'

const env = readE2eEnvOrThrowInCi()

test.describe('dashboard', () => {
  test.skip(!env, 'ADMIN_WEB_E2E_* env vars not set - see scripts/ci/e2e-seed.sh and the CI workflow')

  function value(tile: Locator): Locator {
    return tile.locator('.tile-value')
  }

  test('shows live figures for the seeded pilot, links each tile and keeps personal data off the page', async ({ page }) => {
    test.setTimeout(60_000)
    await loginAs(page, env!.superAdmins.DASHBOARD)
    await expect(page.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible()

    // Real numbers, not placeholders or "Unavailable".
    const drivers = page.getByRole('main').getByRole('link', { name: /^Active drivers/ })
    await expect(value(drivers)).toHaveText(/^\d+$/)
    await expect(page.getByText('Unavailable')).toHaveCount(0)
    await expect(page.getByText('Loading…')).toHaveCount(0)
    const customers = page.getByRole('main').getByRole('link', { name: /^Customers/ })
    expect(Number(await value(customers).innerText())).toBeGreaterThanOrEqual(1) // the seeded rider
    // The seed completed one cash ride; cash fares are shown as cash, never as revenue.
    const cash = page.getByRole('main').getByRole('link', { name: /^Cash fares collected/ })
    await expect(cash).toContainText('Not Lady Go revenue')
    await expect(value(cash)).toContainText('₹')
    await expect(page.getByText(/^Revenue/)).toHaveCount(0)

    await expect(value(page.getByRole('main').getByRole('link', { name: /^Driver verification requests/ }))).toHaveText(/^\d+$/)
    await expect(page.getByRole('heading', { name: 'Rides completed per week' })).toBeVisible()
    await expect(page.locator('.week-bars li')).toHaveCount(8)

    // Counts and timestamps only: the seeded rider's number never appears on this page.
    expect(await page.locator('body').innerText()).not.toMatch(/9900077003/)

    // Each tile leads to its screen.
    await page.getByRole('main').getByRole('link', { name: /^Open safety incidents/ }).click()
    await expect(page).toHaveURL(/\/safety$/)
    await page.goBack()
    await page.getByRole('main').getByRole('link', { name: /^Driver verification requests/ }).click()
    await expect(page).toHaveURL(/\/driver-verification$/)
    await page.goBack()
    await page.getByRole('main').getByRole('link', { name: /^Customers/ }).click()
    await expect(page).toHaveURL(/\/customers$/)
    await page.goBack()
    await page.getByRole('main').getByRole('link', { name: /^Active rides/ }).click()
    await expect(page).toHaveURL(/\/live-map$/)
  })

  test('reads cleanly at phone width with no sideways scrolling', async ({ page }) => {
    test.setTimeout(60_000)
    await page.setViewportSize({ width: 375, height: 812 })
    await loginAs(page, env!.superAdmins.DASHBOARDMOBILE)
    await expect(page.getByRole('heading', { name: 'Rides completed per week' })).toBeVisible()
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(overflow).toBeLessThanOrEqual(0)
    await expect(page.locator('.week-bars li').first()).toBeVisible()
  })
})
