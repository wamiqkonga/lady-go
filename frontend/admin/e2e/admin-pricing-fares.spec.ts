import { expect, test, type Page } from '@playwright/test'
import { readE2eEnvOrThrowInCi } from './env.js'
import { confirmStepUp, loginAs } from './helpers.js'

const env = readE2eEnvOrThrowInCi()

/** The figures typed here exist only in this spec and the throwaway CI database - never in a seed or migration. */
const FIRST = {
  'Base fare (rupees)': '10', 'Per kilometre (rupees)': '5', 'Per minute (rupees)': '1',
  'Minimum fare (rupees)': '20', 'Cancellation fee (rupees)': '10', 'Service fee, flat (rupees)': '0', 'Service fee (percent)': '5',
}

async function review(page: Page, reason: string) {
  await expect(page.getByRole('table', { name: 'Sample fares' })).toBeVisible()
  await page.getByRole('button', { name: 'Review change' }).click()
  await page.getByLabel('Reason (required)').fill(reason)
}

function localInput(daysAhead: number): string {
  const d = new Date(Date.now() + daysAhead * 86_400_000)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

test.describe('Pricing > Fares', () => {
  test.skip(!env, 'ADMIN_WEB_E2E_* env vars not set - see scripts/ci/e2e-seed.sh and the CI workflow')

  test('set the first fare, make a large change, reject three decimals, schedule and cancel, then check a phone width', async ({ page }) => {
    const creds = env!.superAdmins.FARES
    await loginAs(page, creds)
    await page.getByRole('navigation', { name: 'Admin' }).getByRole('link', { name: 'Pricing' }).click()
    await expect(page.getByRole('link', { name: 'Fares' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Settings' })).toBeVisible()

    const card = page.getByRole('region', { name: 'Two-wheeler fare' })
    await expect(card.getByText('No fare set: riders cannot book')).toBeVisible()

    // first fare
    await card.getByRole('button', { name: 'Set the first fare' }).click()
    for (const [label, value] of Object.entries(FIRST)) await page.getByLabel(label).fill(value)
    await review(page, 'e2e first fare')
    await page.getByRole('button', { name: 'Save fare' }).click()
    await confirmStepUp(page, creds)
    await expect(card.getByText('No fare set: riders cannot book')).toBeHidden()
    await expect(card.getByText('In force since')).toBeVisible()
    await expect(card.getByRole('table', { name: 'Fare history' }).getByRole('row')).toHaveCount(2)

    // a three-decimal rupee value is refused on screen
    await card.getByRole('button', { name: 'Change fare' }).click()
    await page.getByLabel('Base fare (rupees)').fill('10.555')
    await expect(page.getByText('Use at most two decimals (whole paise only).')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Review change' })).toBeDisabled()

    // large change: 10 -> 25 rupees base fare needs the explicit confirmation
    await page.getByLabel('Base fare (rupees)').fill('25')
    await expect(page.getByText(/Large change: more than 50 percent/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Review change' })).toBeDisabled()
    await page.getByLabel('I confirm this large change').check()
    await review(page, 'e2e large change')
    await page.getByRole('button', { name: 'Save fare' }).click()
    await expect(card.getByRole('table', { name: 'Fare history' }).getByRole('row')).toHaveCount(3)
    await expect(card.getByText('Rs 25.00').first()).toBeVisible()

    // schedule a small change for tomorrow, then cancel it
    await card.getByRole('button', { name: 'Change fare' }).click()
    await page.getByLabel('Base fare (rupees)').fill('26')
    await page.getByLabel('At a later time').check()
    await page.getByLabel('Start time').fill(localInput(1))
    await review(page, 'e2e scheduled change')
    await page.getByRole('button', { name: 'Save fare' }).click()
    await expect(card.getByText('Scheduled change', { exact: true })).toBeVisible()
    await expect(card.getByRole('button', { name: 'Change fare' })).toBeHidden()

    await card.getByRole('button', { name: 'Cancel scheduled change' }).click()
    await card.getByLabel('Reason (required)').fill('e2e cancel')
    await card.getByRole('button', { name: 'Confirm cancel scheduled change' }).click()
    await expect(card.getByText('Scheduled change', { exact: true })).toBeHidden()
    await expect(card.getByRole('button', { name: 'Change fare' })).toBeVisible()

    // phone width: no sideways page scroll, history shows as cards (header row hidden)
    await page.setViewportSize({ width: 375, height: 812 })
    await expect(card.getByRole('table', { name: 'Fare history' })).toBeVisible()
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
    expect(overflow).toBeLessThanOrEqual(1)
    await expect(card.locator('.fare-history thead')).toHaveCSS('position', 'absolute')
  })
})
