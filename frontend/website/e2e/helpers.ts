import { expect, type Page } from '@playwright/test'
import { latestOtpCodeFor } from './env.js'

/** Polls the backend log briefly - the log line is written synchronously in the same request as the
 *  "code sent" response, but the file write and this process's read are still two different
 *  processes, so a handful of short retries is just normal cross-process timing, not a real race. */
async function waitForOtpCode(phone: string, backendLogPath: string): Promise<string> {
  for (let attempt = 0; attempt < 20; attempt++) {
    try {
      return latestOtpCodeFor(phone, backendLogPath)
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 250))
    }
  }
  return latestOtpCodeFor(phone, backendLogPath)
}

export async function loginAs(page: Page, phone: string, backendLogPath: string): Promise<void> {
  await page.goto('/captain/registration')
  await page.getByLabel('Phone number').fill(phone)
  // Required by the form; the e2e backend uses the SMS channel (logged code), which ignores it.
  await page.getByLabel('Email').fill('e2e-driver@ladygo.test')
  await page.getByRole('button', { name: 'Send code' }).click()
  await expect(page.getByLabel('Code')).toBeVisible()

  const code = await waitForOtpCode(phone, backendLogPath)
  await page.getByLabel('Code').fill(code)
  await page.getByRole('button', { name: 'Continue' }).click()
  await passPasswordStep(page)
  await expect(page.getByRole('heading', { name: 'My application' })).toBeVisible()
}

export const E2E_PASSWORD = 'e2e quiet river lantern 42'

/** After the code, a captain with no password is asked to create one. A new sign-up cannot skip it; a captain who
 *  already has an application can tap "Not now". Does nothing when the step does not appear. */
export async function passPasswordStep(page: Page): Promise<void> {
  const newPassword = page.getByLabel('New password')
  const dashboard = page.getByRole('heading', { name: 'My application' })
  await expect(newPassword.or(dashboard)).toBeVisible()
  if (!(await newPassword.isVisible())) return
  const notNow = page.getByRole('button', { name: 'Not now' })
  if (await notNow.isVisible()) {
    await notNow.click()
    return
  }
  await newPassword.fill(E2E_PASSWORD)
  await page.getByLabel('Type it again').fill(E2E_PASSWORD)
  await page.getByRole('button', { name: 'Save password and continue' }).click()
}
