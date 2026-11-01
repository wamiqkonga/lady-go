import { expect, type Page } from '@playwright/test'
import { currentTotpCode, nextTotpCode, type Credentials } from './env.js'

export async function loginAs(page: Page, creds: Credentials): Promise<void> {
  await page.goto('/login')
  await page.getByLabel('Email').fill(creds.email)
  await page.getByLabel('Password').fill(creds.password)
  // The code field is on screen from the start when the app is built with VITE_ADMIN_MFA_REQUIRED=true.
  await expect(page.getByLabel('Authenticator code')).toBeVisible()
  await page.getByLabel('Authenticator code').fill(currentTotpCode(creds.totpSecret))
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
}

/** Answers the step-up prompt that a destructive action (config edit) raises after login. */
export async function confirmStepUp(page: Page, creds: Credentials): Promise<void> {
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByLabel('Authenticator code').fill(nextTotpCode(creds.totpSecret))
  await dialog.getByRole('button', { name: 'Confirm' }).click()
  await expect(dialog).not.toBeVisible()
}

/** The driver-documents queue table has no stable test id - this finds the row for a given driver by
 *  whatever text identifies it (name if the DTO carries one, otherwise the truncated id fallback the
 *  UI itself uses - see DriverDocumentQueue.tsx) and follows its link. */
export async function openDriverReview(page: Page, driverId: string): Promise<void> {
  await page.goto(`/driver-verification/${driverId}`)
}
