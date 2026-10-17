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

test.describe('driver registration - full flow', () => {
  test.skip(!env, 'DRIVER_REG_E2E_* env vars not set - see scripts/ci/driver-registration-e2e-seed.sh')

  test('happy path: login, city, vehicle, every document, selfie, declaration reach PENDING_REVIEW', async ({ page }) => {
    test.setTimeout(90_000)
    await loginAs(page, env!.phones.HAPPY_PATH, env!.backendLog)

    await expect(page.getByText('Which city will you drive in?')).toBeVisible()
    await page.getByRole('combobox').selectOption('Srinagar')
    await page.getByRole('button', { name: 'Start application' }).click()

    await expect(page.getByRole('heading', { name: 'Vehicle', exact: true })).toBeVisible()
    await page.getByPlaceholder('Registration number').fill('JK01HP' + Date.now() % 10000)
    await page.getByRole('button', { name: 'Save vehicle' }).click()
    await expect(page.getByText(/JK01HP/)).toBeVisible()

    const documentLabels = [
      'Driving licence', 'Vehicle registration (RC) photo', 'Vehicle photo (plate visible)',
      'Insurance', 'Fitness certificate', 'Pollution certificate',
    ]
    for (const label of documentLabels) {
      const sectionLocator = section(page, label)
      await sectionLocator.locator('input[type="file"]').setInputFiles(VALID_IMAGE)
      if (label === 'Driving licence') {
        await sectionLocator.getByPlaceholder('Document number').fill('DL-E2E-0001')
      }
      if (label === 'Driving licence' || label === 'Insurance') {
        const future = new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10)
        await sectionLocator.getByLabel('Expiry date').fill(future)
      }
      await sectionLocator.getByRole('button', { name: 'Upload' }).click()
      await expect(sectionLocator.getByText('Pending review')).toBeVisible()
    }

    // Selfie, via the file-input fallback - the camera path is covered by component tests
    // (getUserMedia is unreliable headless without extra browser flags this suite doesn't need).
    const selfieSection = section(page, 'Selfie')
    await selfieSection.locator('input[type="file"]').setInputFiles(VALID_IMAGE)
    await expect(selfieSection.getByAltText('Selfie preview')).toBeVisible()
    await selfieSection.getByRole('button', { name: 'Upload' }).click()
    await expect(selfieSection.getByText('Pending review')).toBeVisible()

    const declarationSection = section(page, 'Self-declaration')
    await declarationSection.getByRole('button', { name: 'I agree' }).click()
    await expect(declarationSection.getByText(/Accepted/)).toBeVisible()

    await expect(section(page, 'Application status').getByText('Pending review')).toBeVisible()
  })

  test('a non-image file is rejected client-side with no status change', async ({ page }) => {
    await loginAs(page, env!.phones.WRONG_FILE_TYPE, env!.backendLog)
    await page.getByRole('combobox').selectOption('Srinagar')
    await page.getByRole('button', { name: 'Start application' }).click()

    const sectionLocator = section(page, 'Vehicle registration (RC) photo')
    await sectionLocator.locator('input[type="file"]').setInputFiles(NOT_AN_IMAGE)

    await expect(sectionLocator.getByRole('alert')).toHaveText('Only JPEG or PNG images are accepted.')
    await expect(sectionLocator.getByRole('button', { name: 'Upload' })).toBeDisabled()
  })

  test('an oversized file is rejected client-side with no status change', async ({ page }) => {
    await loginAs(page, env!.phones.OVERSIZED_FILE, env!.backendLog)
    await page.getByRole('combobox').selectOption('Srinagar')
    await page.getByRole('button', { name: 'Start application' }).click()

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
    await page.getByRole('combobox').selectOption('Srinagar')
    await page.getByRole('button', { name: 'Start application' }).click()

    const sectionLocator = section(page, 'Insurance')
    await sectionLocator.locator('input[type="file"]').setInputFiles(VALID_IMAGE)
    const yesterday = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString().slice(0, 10)
    await sectionLocator.getByLabel('Expiry date').fill(yesterday)
    await sectionLocator.getByRole('button', { name: 'Upload' }).click()

    await expect(sectionLocator.getByRole('alert')).toContainText('already expired')
    await expect(sectionLocator.getByText('Pending review')).not.toBeVisible()
  })

  test('a rejected document shows the reviewer note and can be resubmitted', async ({ page }) => {
    await loginAs(page, env!.phones.REJECT_RESUBMIT, env!.backendLog)

    const sectionLocator = section(page, 'Driving licence')
    await expect(sectionLocator.getByText('Rejected')).toBeVisible()
    await expect(sectionLocator.getByText('Photo is blurry, please retake.')).toBeVisible()
    // The note renders as literal text, never markup - no element the string could have created.
    expect(await sectionLocator.locator('.review-note').innerHTML()).not.toContain('<script')

    await sectionLocator.locator('input[type="file"]').setInputFiles(VALID_IMAGE)
    await sectionLocator.getByPlaceholder('Document number').fill('DL-E2E-RESUBMIT')
    const future = new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10)
    await sectionLocator.getByLabel('Expiry date').fill(future)
    await sectionLocator.getByRole('button', { name: 'Resubmit' }).click()

    await expect(sectionLocator.getByText('Pending review')).toBeVisible()
    await expect(sectionLocator.getByText('Rejected')).not.toBeVisible()
  })
})
