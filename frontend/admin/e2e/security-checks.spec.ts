import { expect, test } from '@playwright/test'
import { currentTotpCode, freshTotpCodeForLogin, readE2eEnv } from './env.js'
import { loginAs } from './helpers.js'

const env = readE2eEnv()

test.describe('negative security checks', () => {
  test.skip(!env, 'ADMIN_WEB_E2E_* env vars not set - see scripts/ci/e2e-seed.sh and the CI workflow')

  test('a wrong MFA code is rejected', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel('Email').fill(env!.superAdmin.email)
    await page.getByLabel('Password').fill(env!.superAdmin.password)
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page.getByLabel('Authenticator code')).toBeVisible()

    const realCode = currentTotpCode(env!.superAdmin.totpSecret)
    const wrongCode = String((Number(realCode) + 1) % 1_000_000).padStart(6, '0')
    await page.getByLabel('Authenticator code').fill(wrongCode)
    await page.getByRole('button', { name: 'Sign in' }).click()

    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page).not.toHaveURL(/\/dashboard$/)
  })

  test('a replayed MFA code is rejected on the second use', async ({ page, context }) => {
    const code = await freshTotpCodeForLogin(env!.superAdmin.totpSecret)

    await page.goto('/login')
    await page.getByLabel('Email').fill(env!.superAdmin.email)
    await page.getByLabel('Password').fill(env!.superAdmin.password)
    await page.getByRole('button', { name: 'Sign in' }).click()
    await page.getByLabel('Authenticator code').fill(code)
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page).toHaveURL(/\/dashboard$/)

    // A second, independent session attempting to log in with the exact same (already-accepted)
    // code must fail - TOTP replay rejection (AdminAuthService.updateMfaLastUsedStepIfNewer).
    const secondPage = await context.newPage()
    await secondPage.goto('/login')
    await secondPage.getByLabel('Email').fill(env!.superAdmin.email)
    await secondPage.getByLabel('Password').fill(env!.superAdmin.password)
    await secondPage.getByRole('button', { name: 'Sign in' }).click()
    await secondPage.getByLabel('Authenticator code').fill(code)
    await secondPage.getByRole('button', { name: 'Sign in' }).click()

    await expect(secondPage.getByRole('alert')).toBeVisible()
    await expect(secondPage).not.toHaveURL(/\/dashboard$/)
  })

  test('a document view URL expires and stops working', async ({ page, request }) => {
    await loginAs(page, env!.superAdmin)
    await page.goto(`/driver-verification/${env!.secondaryDriverId}`)

    const firstView = page.locator('li.document-row').first().getByRole('button', { name: 'View' })
    await firstView.click()
    const image = page.locator('li.document-row').first().locator('img')
    await expect(image).toBeVisible()
    const url = await image.getAttribute('src')
    expect(url).toBeTruthy()

    // The seed step shortens verification.document.view_url.ttl.seconds for this suite specifically
    // (scripts/ci/e2e-seed.sh) - a real 60s+ wait per check is not viable across this whole suite.
    await page.waitForTimeout(8_000)
    const response = await request.get(url!)
    expect(response.ok()).toBe(false)
  })

  test('tampering with the driver id in the URL shows an error, not another driver\'s data', async ({ page }) => {
    await loginAs(page, env!.superAdmin)
    await page.goto(`/driver-verification/${env!.unknownDriverId}`)

    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByText('JK01AB1234')).not.toBeVisible()
  })

  test('the refresh-token cookie is HttpOnly+Secure, the CSRF cookie is Secure but readable', async ({
    page,
    context,
  }) => {
    await loginAs(page, env!.superAdmin)
    const cookies = await context.cookies()
    const refreshCookie = cookies.find((c) => c.name === 'ladygo_admin_rt')
    const csrfCookie = cookies.find((c) => c.name === 'ladygo_admin_csrf')

    expect(refreshCookie).toBeTruthy()
    expect(refreshCookie!.httpOnly).toBe(true)
    expect(refreshCookie!.secure).toBe(true)
    expect(refreshCookie!.sameSite).toBe('Strict')

    expect(csrfCookie).toBeTruthy()
    expect(csrfCookie!.httpOnly).toBe(false)
    expect(csrfCookie!.secure).toBe(true)
  })

  test('no access or refresh token ever reaches localStorage or sessionStorage', async ({ page }) => {
    await loginAs(page, env!.superAdmin)
    const storageDump = await page.evaluate(() => ({
      local: { ...localStorage },
      session: { ...sessionStorage },
    }))
    const serialized = JSON.stringify(storageDump)
    expect(serialized).not.toContain('eyJ') // common base64url JWT header prefix ("{"...)
    expect(Object.keys(storageDump.local)).toHaveLength(0)
    expect(Object.keys(storageDump.session)).toHaveLength(0)
  })

  test('markup in a rejection reason renders as literal text, never as markup', async ({ page }) => {
    await loginAs(page, env!.superAdmin)
    await page.goto(`/driver-verification/${env!.secondaryDriverId}`)

    const pendingRow = page
      .locator('li.document-row')
      .filter({ has: page.getByRole('button', { name: 'Reject' }) })
      .first()
    const payload = '<img src=x onerror=alert(1)>still just text'

    let dialogFired = false
    page.once('dialog', (dialog) => {
      if (dialog.type() === 'alert') dialogFired = true
      dialog.accept(payload)
    })
    await pendingRow.getByRole('button', { name: 'Reject' }).click()

    const noteLocator = page.locator('.review-note').filter({ hasText: 'still just text' })
    await expect(noteLocator).toBeVisible()
    expect(await noteLocator.locator('img').count()).toBe(0)
    expect(dialogFired).toBe(false)
  })

  test('a plain ADMIN cannot update config; SUPER_ADMIN can', async ({ page }) => {
    await loginAs(page, env!.admin)
    const adminAttempt = await page.evaluate(async (apiBaseUrl: string) => {
      const token = (window as unknown as { __ladygoAccessToken: () => string | null }).__ladygoAccessToken()
      const auth = { Authorization: `Bearer ${token}` }
      const listResponse = await fetch(`${apiBaseUrl}/api/v1/admin/config`, { headers: auth })
      // GET /admin/config returns data as a plain array, not the {content: [...]} page envelope
      // other list endpoints use - and each entry's key field is configKey, not key.
      const listBody = (await listResponse.json()) as { data?: Array<{ id: string; configKey: string }> }
      if (!listResponse.ok || !listBody.data) {
        throw new Error(`GET /admin/config as ADMIN failed: ${listResponse.status} ${JSON.stringify(listBody)}`)
      }
      const entry = listBody.data.find((e) => e.configKey === 'ride.gate.pickup_radius.m')!
      const response = await fetch(`${apiBaseUrl}/api/v1/admin/config/${entry.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', ...auth },
        body: JSON.stringify({ value: '500' }),
      })
      return { status: response.status, entryId: entry.id as string }
    }, env!.apiBaseUrl)
    expect(adminAttempt.status).toBe(403)

    await page.context().clearCookies()
    await loginAs(page, env!.superAdmin)
    const superAdminStatus = await page.evaluate(
      async ({ apiBaseUrl, entryId }: { apiBaseUrl: string; entryId: string }) => {
        const token = (window as unknown as { __ladygoAccessToken: () => string | null }).__ladygoAccessToken()
        const response = await fetch(`${apiBaseUrl}/api/v1/admin/config/${entryId}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
          body: JSON.stringify({ value: '500' }),
        })
        return response.status
      },
      { apiBaseUrl: env!.apiBaseUrl, entryId: adminAttempt.entryId },
    )
    expect(superAdminStatus).toBe(200)
  })
})
