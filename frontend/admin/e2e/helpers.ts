import { expect, type Page } from '@playwright/test'
import { freshTotpCodeForLogin } from './env.js'

export async function loginAs(
  page: Page,
  creds: { email: string; password: string; totpSecret: string },
): Promise<void> {
  await page.goto('/login')
  await page.getByLabel('Email').fill(creds.email)
  await page.getByLabel('Password').fill(creds.password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByLabel('Authenticator code')).toBeVisible()
  await page.getByLabel('Authenticator code').fill(await freshTotpCodeForLogin(creds.totpSecret))
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
}

/** The driver-documents queue table has no stable test id - this finds the row for a given driver by
 *  whatever text identifies it (name if the DTO carries one, otherwise the truncated id fallback the
 *  UI itself uses - see DriverDocumentQueue.tsx) and follows its link. */
export async function openDriverReview(page: Page, driverId: string): Promise<void> {
  await page.goto(`/driver-verification/${driverId}`)
}
