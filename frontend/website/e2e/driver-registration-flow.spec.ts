import { expect, test } from '@playwright/test'
import { readE2eEnvOrThrowInCi } from './env.js'
import { loginAs } from './helpers.js'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import fs from 'node:fs'
import os from 'node:os'

const env = readE2eEnvOrThrowInCi()
const here = path.dirname(fileURLToPath(import.meta.url))
const VALID_IMAGE = path.join(here, 'fixtures/valid-document.png')
const NOT_AN_IMAGE = path.join(here, 'fixtures/not-an-image.txt')

function section(page: import('@playwright/test').Page, label: string) {
  return page.locator('.section').filter({ has: page.getByRole('heading', { name: label, exact: true }) })
}

/** The wizard shows one step at a time; jump to a step through the "All steps" list, the way a
 *  captain revisiting a step would. */
async function goToStep(page: import('@playwright/test').Page, title: string) {
  const list = page.locator('details.step-list')
  if (!(await list.evaluate((el) => (el as HTMLDetailsElement).open))) {
    await list.locator('summary').click()
  }
  await list.getByRole('button', { name: new RegExp(`^${title.replace(/[()]/g, '\\$&')}`) }).click()
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
}

async function startApplication(page: import('@playwright/test').Page) {
  await page.getByRole('combobox').selectOption('Srinagar')
  await page.getByRole('button', { name: 'Start application' }).click()
  await expect(page.getByRole('heading', { name: 'Your vehicle' })).toBeVisible()
}

async function next(page: import('@playwright/test').Page) {
  await page.getByRole('button', { name: 'Continue' }).click()
}

test.describe('captain registration - full flow', () => {
  test.skip(!env, 'DRIVER_REG_E2E_* env vars not set - see scripts/ci/driver-registration-e2e-seed.sh')

  test('happy path: login, city, vehicle, every document, selfie, declaration reach the done screen', async ({ page }) => {
    test.setTimeout(90_000)
    await loginAs(page, env!.phones.HAPPY_PATH, env!.backendLog)

    await expect(page.getByText('Which city will you drive in?')).toBeVisible()
    await startApplication(page)

    // Nothing is done yet: the status must not claim "Under review" and Continue is locked.
    await expect(page.getByText('In progress')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Continue' })).toBeDisabled()

    await page.getByLabel('Registration number').fill('jk01hp' + (Date.now() % 10000))
    await page.getByRole('button', { name: 'Save vehicle' }).click()
    await expect(page.getByText(/JK01HP/)).toBeVisible()
    await next(page)

    const documents = [
      'Driving licence', 'Vehicle registration (RC) photo', 'Vehicle photo (plate visible)',
      'Insurance', 'Fitness certificate', 'Pollution certificate',
    ]
    for (const label of documents) {
      await expect(page.getByRole('heading', { name: label, exact: true })).toBeVisible()
      const sectionLocator = section(page, label)
      await sectionLocator.locator('input[type="file"]').setInputFiles(VALID_IMAGE)
      if (label === 'Driving licence') {
        await sectionLocator.getByLabel('Document number').fill('DL-E2E-0001')
      }
      if (label === 'Driving licence' || label === 'Insurance') {
        const future = new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10)
        await sectionLocator.getByLabel('Expiry date').fill(future)
      }
      await sectionLocator.getByRole('button', { name: 'Upload' }).click()
      await expect(sectionLocator.getByText('Pending review')).toBeVisible()
      await next(page)
    }

    // Selfie, via the file-input fallback - the camera path is covered by component tests
    // (getUserMedia is unreliable headless without extra browser flags this suite doesn't need).
    const selfieSection = section(page, 'Selfie')
    await selfieSection.locator('input[type="file"]').setInputFiles(VALID_IMAGE)
    await expect(selfieSection.getByAltText('Selfie preview')).toBeVisible()
    await selfieSection.getByRole('button', { name: 'Upload' }).click()
    await expect(selfieSection.getByText('Pending review')).toBeVisible()
    await next(page)

    const declarationSection = section(page, 'Self-declaration')
    await expect(declarationSection.getByRole('button', { name: 'I agree' })).toBeDisabled()
    await declarationSection.getByLabel(/I have read and understood/).check()
    await declarationSection.getByRole('button', { name: 'I agree' }).click()
    await expect(declarationSection.getByText(/Accepted/)).toBeVisible()
    await next(page)

    await expect(page.getByRole('heading', { name: 'Application received' })).toBeVisible()
    await expect(page.getByText('Under review').first()).toBeVisible()
    await expect(page.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '9')
  })

  test('a non-image file is rejected client-side with no status change', async ({ page }) => {
    await loginAs(page, env!.phones.WRONG_FILE_TYPE, env!.backendLog)
    await startApplication(page)
    await goToStep(page, 'Vehicle registration (RC) photo')

    const sectionLocator = section(page, 'Vehicle registration (RC) photo')
    await sectionLocator.locator('input[type="file"]').setInputFiles(NOT_AN_IMAGE)

    await expect(sectionLocator.getByRole('alert')).toHaveText('Only JPEG or PNG images are accepted.')
    await expect(sectionLocator.getByRole('button', { name: 'Upload' })).toBeDisabled()
    await expect(page.getByRole('button', { name: 'Continue' })).toBeDisabled()
  })

  test('an oversized file is rejected client-side with no status change', async ({ page }) => {
    await loginAs(page, env!.phones.OVERSIZED_FILE, env!.backendLog)
    await startApplication(page)
    await goToStep(page, 'Insurance')

    const oversizedPath = path.join(os.tmpdir(), `oversized-${Date.now()}.png`)
    // A real (if minimal) PNG header followed by padding, not just random bytes - the size check
    // must reject it before any decodability check even runs.
    const validBytes = fs.readFileSync(VALID_IMAGE)
    fs.writeFileSync(oversizedPath, Buffer.concat([validBytes, Buffer.alloc(9 * 1024 * 1024)]))

    const sectionLocator = section(page, 'Insurance')
    await sectionLocator.locator('input[type="file"]').setInputFiles(oversizedPath)

    await expect(sectionLocator.getByRole('alert')).toContainText('too large')
    fs.unlinkSync(oversizedPath)
  })

  test('an already-expired expiry date is rejected by the server, not silently accepted', async ({ page }) => {
    await loginAs(page, env!.phones.EXPIRED_INSURANCE, env!.backendLog)
    await startApplication(page)
    await goToStep(page, 'Insurance')

    const sectionLocator = section(page, 'Insurance')
    await sectionLocator.locator('input[type="file"]').setInputFiles(VALID_IMAGE)
    const yesterday = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString().slice(0, 10)
    // The date picker's own min blocks past dates in the UI; set the value directly so the
    // server-side check (the real authority) is what gets exercised.
    await sectionLocator.getByLabel('Expiry date').evaluate((el, value) => {
      const input = el as HTMLInputElement
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
      setter.call(input, value)
      input.dispatchEvent(new Event('input', { bubbles: true }))
    }, yesterday)
    await sectionLocator.getByRole('button', { name: 'Upload' }).click()

    await expect(sectionLocator.getByRole('alert')).toContainText('already expired')
    await expect(sectionLocator.getByText('Pending review')).not.toBeVisible()
    await expect(page.getByRole('button', { name: 'Continue' })).toBeDisabled()
  })

  test('a rejected document shows the reviewer note and can be resubmitted', async ({ page }) => {
    await loginAs(page, env!.phones.REJECT_RESUBMIT, env!.backendLog)

    // The seeded captain has no vehicle yet, so the wizard resumes on the first unfinished step.
    await expect(page.getByRole('heading', { name: 'Your vehicle' })).toBeVisible()
    await goToStep(page, 'Driving licence')

    const sectionLocator = section(page, 'Driving licence')
    await expect(sectionLocator.getByText('Rejected')).toBeVisible()
    await expect(sectionLocator.getByText('Photo is blurry, please retake.')).toBeVisible()
    // The note renders as literal text, never markup - no element the string could have created.
    expect(await sectionLocator.locator('.review-note').innerHTML()).not.toContain('<script')

    await sectionLocator.locator('input[type="file"]').setInputFiles(VALID_IMAGE)
    await sectionLocator.getByLabel('Document number').fill('DL-E2E-RESUBMIT')
    const future = new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10)
    await sectionLocator.getByLabel('Expiry date').fill(future)
    await sectionLocator.getByRole('button', { name: 'Resubmit' }).click()

    await expect(sectionLocator.getByText('Pending review')).toBeVisible()
    await expect(sectionLocator.getByText('Rejected')).not.toBeVisible()
    await expect(page.getByRole('button', { name: 'Continue' })).toBeEnabled()
  })
})
