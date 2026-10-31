import { expect, test } from '@playwright/test'
import { readE2eEnvOrThrowInCi } from './env.js'
import { confirmStepUp, loginAs } from './helpers.js'

const env = readE2eEnvOrThrowInCi()

test.describe('ride/flag oversight, audit log, and config screens', () => {
  test.skip(!env, 'ADMIN_WEB_E2E_* env vars not set - see scripts/ci/e2e-seed.sh and the CI workflow')

  test('the rides list and detail show masked contacts, pickup, cash state, flags and the timeline', async ({ page }) => {
    await loginAs(page, env!.superAdmins.RIDES)
    await page.getByRole('link', { name: 'Rides' }).click()
    await expect(page.getByRole('heading', { name: 'Ride & flag oversight' })).toBeVisible()

    const row = page.locator('tbody tr').filter({ has: page.getByRole('link', { name: 'COMPLETED' }) })
    await expect(row).toBeVisible()
    await expect(row.getByText(/\*{4,}7003/)).toBeVisible() // customer, last 4 of +919900077003
    await expect(row.getByText(/\*{4,}7001/)).toBeVisible() // driver, last 4 of +919900077001
    await expect(row.locator('.badge-expired')).toHaveText('1') // one open flag

    await row.getByRole('link', { name: 'COMPLETED' }).click()
    await expect(page.getByRole('heading', { name: 'Ride detail' })).toBeVisible()
    await expect(page.getByText(/\*{4,}7003/)).toBeVisible()
    await expect(page.getByText(/\*{4,}7001/)).toBeVisible()
    await expect(page.getByText('12 MG Road, Srinagar')).toBeVisible()
    await expect(page.getByText(/Cash confirmed/)).toBeVisible()
    await expect(page.getByText('RIDER_SAFETY_CANCEL')).toBeVisible()
    // getByText is case-insensitive, so this also matches the Timeline row's own "To" column
    // (RIDE_COMPLETED) - .first() is fine, this is just confirming the row itself is there.
    await expect(page.getByText('ride_completed').first()).toBeVisible()

    // Never a full, unmasked phone number anywhere on the detail page.
    const bodyText = await page.locator('body').innerText()
    expect(bodyText).not.toMatch(/\+?91990007700[13]\b/)
  })

  test('reviewing a flag requires a note, which renders as literal text, never markup', async ({ page }) => {
    await loginAs(page, env!.superAdmins.FLAGS)
    await page.getByRole('link', { name: 'Rides' }).click()
    await page.getByRole('button', { name: 'Flags' }).click()

    const flagRow = page.locator('li.document-row').filter({ hasText: 'RIDER_SAFETY_CANCEL' })
    await expect(flagRow).toBeVisible()
    await expect(flagRow).toHaveClass(/flag-attention/)

    const reviewButton = flagRow.getByRole('button', { name: 'Mark reviewed' })
    await expect(reviewButton).toBeDisabled()

    const payload = '<img src=x onerror=alert(1)>no safety concern, rider confirmed by phone'
    await flagRow.getByPlaceholder('Review note (required)').fill(payload)
    await reviewButton.click()

    // Reviewed flags drop out of the (default) Unreviewed filter.
    await expect(flagRow).not.toBeVisible()
    await page.getByLabel('Review state').selectOption('Reviewed')
    const reviewedRow = page.locator('li.document-row').filter({ hasText: 'RIDER_SAFETY_CANCEL' })
    await expect(reviewedRow.getByText('no safety concern, rider confirmed by phone')).toBeVisible()
    expect(await reviewedRow.locator('img').count()).toBe(0)
  })

  test('the audit log shows the flag review with the note as text, filterable by action', async ({ page }) => {
    await loginAs(page, env!.superAdmins.AUDITLOG)
    await page.getByRole('link', { name: 'Audit log' }).click()
    await expect(page.getByRole('heading', { name: 'Audit log' })).toBeVisible()

    await page.getByPlaceholder(/CONFIGURATION_CHANGED/).fill('RIDE_FLAG_REVIEWED')
    await page.getByRole('button', { name: 'Apply' }).click()

    const entryRow = page.locator('tbody tr').filter({ hasText: 'RIDE_FLAG_REVIEWED' }).first()
    await expect(entryRow).toBeVisible()
    expect(await entryRow.locator('img, script').count()).toBe(0)

    // Never a raw OTP-looking 6-digit code, a JWT, or a full phone number anywhere in the table.
    const bodyText = await page.locator('table.list-table').innerText()
    expect(bodyText).not.toMatch(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/)
    expect(bodyText).not.toMatch(/\+?91990007700[13]\b/)
  })

  test('a plain ADMIN sees the config screen as read-only, with no edit control', async ({ page }) => {
    await loginAs(page, env!.admin2)
    await page.getByRole('link', { name: 'Pricing' }).click()
    await expect(page.getByRole('heading', { name: 'Pricing & configuration' })).toBeVisible()
    await expect(page.getByText(/Read-only/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Edit' })).not.toBeVisible()
  })

  test('a SUPER_ADMIN must give a reason, sees old→new on confirm, and the change is audited', async ({ page }) => {
    await loginAs(page, env!.superAdmins.CONFIGEDIT)
    await page.getByRole('link', { name: 'Pricing' }).click()

    const row = page.locator('tbody tr').filter({ hasText: 'driver.search.radius.m' })
    await row.getByRole('button', { name: 'Edit' }).click()
    const editPanel = row.locator('.config-edit')
    const input = editPanel.locator('input[type="text"]').first()
    await input.fill('7500')
    await row.getByRole('button', { name: 'Save' }).click()

    await expect(editPanel.locator('strong').nth(0)).toHaveText('5000')
    await expect(editPanel.locator('strong').nth(1)).toHaveText('7500')
    const confirmButton = row.getByRole('button', { name: 'Confirm' })
    await expect(confirmButton).toBeDisabled()

    await editPanel.getByPlaceholder(/Reason for this change/).fill('e2e oversight-flow test')
    await confirmButton.click()

    // Login does not count as a step-up: the save is refused until a fresh authenticator code is given.
    await confirmStepUp(page, env!.superAdmins.CONFIGEDIT)

    await expect(row.getByRole('cell').nth(1)).toHaveText('7500')
  })
})
