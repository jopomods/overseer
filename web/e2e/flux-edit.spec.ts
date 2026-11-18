import { expect, test } from '@playwright/test'
import { mockApi } from './mock-api.ts'

/** Changing Flux networks from the dashboard (ADR-0016), against the mock backend. */
test.describe('flux editing', () => {
  test('network name, colour, security and password', async ({ page }) => {
    await mockApi(page)
    await page.goto('/energy/3')
    await page.getByTestId('network-edit').click()
    const form = page.getByTestId('network-settings')

    await form.getByLabel('Name').fill('x'.repeat(25))
    await expect(form.getByText('25/24')).toBeVisible()
    await expect(form.getByRole('button', { name: 'Save' })).toBeDisabled()
    await form.getByLabel('Name').fill('Reactor grid')

    await form.getByLabel('Hex code').fill('orange')
    await expect(form.getByRole('button', { name: 'Save' })).toBeDisabled()
    await form.getByRole('radio', { name: '#609732' }).click()
    await expect(form.getByLabel('Hex code')).toHaveValue('#609732')

    await form.getByRole('radio', { name: 'Password' }).click()
    await expect(form.getByRole('button', { name: 'Save' })).toBeDisabled()
    await form.getByLabel('Password').fill('secret')

    const request = page.waitForRequest((r) => r.url().endsWith('/api/v1/actions') && r.method() === 'POST')
    await form.getByRole('button', { name: 'Save' }).click()
    expect((await request).postDataJSON()).toEqual({
      type: 'flux.network.edit',
      resource: 'flux/network/3',
      params: { name: 'Reactor grid', color: '#609732', security: 'encrypted', password: 'secret' },
    })
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Reactor grid')
    await expect(form).toHaveCount(0)
    const log = page.getByTestId('action-log')
    await expect(log).toContainText('Name Network 3 → Reactor grid')
    await expect(log).toContainText('Password – → ***')
    await expect(log).not.toContainText('secret')
  })

  test('several devices at once: rename by pattern and set priority', async ({ page }) => {
    await mockApi(page)
    await page.goto('/energy/0')
    await page.getByRole('checkbox', { name: 'Select Device 4', exact: true }).check()
    await page.getByRole('checkbox', { name: 'Select Device 8', exact: true }).check()
    await expect(page.getByTestId('device-selection')).toContainText('2 selected')
    await page.getByTestId('edit-selected').click()

    const editor = page.getByTestId('device-editor')
    await editor.getByLabel('New names').fill('Furnace {n}')
    await expect(editor.getByTestId('name-preview')).toContainText('Furnace 1, Furnace 2')
    await editor.getByLabel('Priority').fill('10000')
    await expect(editor.getByRole('button', { name: 'Save' })).toBeDisabled()
    await editor.getByLabel('Priority').fill('7')
    await editor.getByRole('radio', { name: 'on' }).nth(1).click() // power surge

    const request = page.waitForRequest((r) => r.url().endsWith('/api/v1/actions') && r.method() === 'POST')
    await editor.getByRole('button', { name: 'Save' }).click()
    const sent = (await request).postDataJSON()
    expect(sent.type).toBe('flux.device.edit')
    expect(sent.params.devices).toHaveLength(2)
    expect(sent.params.devices[0]).toMatchObject({ name: 'Furnace 1', priority: 7, surge: true })

    await expect(editor).toHaveCount(0)
    const list = page.getByTestId('device-list')
    await expect(list).toContainText('Furnace 1')
    await expect(list).toContainText('Furnace 2')
    await expect(page.getByTestId('action-log')).toContainText('Priority 4 → 7')
  })

  test('unloaded devices are skipped and named', async ({ page }) => {
    await mockApi(page)
    await page.goto('/energy/0')
    await page.getByRole('checkbox', { name: 'Select Device 7', exact: true }).check() // unloaded
    await page.getByRole('checkbox', { name: 'Select Device 9', exact: true }).check()
    await page.getByTestId('edit-selected').click()
    const editor = page.getByTestId('device-editor')
    await expect(editor).toContainText('1 of them are in unloaded chunks')
    await editor.getByLabel('Priority').fill('3')
    await editor.getByRole('button', { name: 'Save' }).click()
    await expect(editor.getByRole('status')).toContainText('skipped')
  })

  test('members: add, promote and remove', async ({ page }) => {
    await mockApi(page)
    await page.goto('/energy/0')
    const members = page.getByTestId('member-list')
    await page.getByLabel('Player name').fill('Steve')
    await page.getByRole('button', { name: 'Add' }).click()
    const steve = members.getByRole('listitem').filter({ hasText: 'Steve' })
    await expect(steve).toBeVisible()

    await steve.getByLabel('Role').selectOption('admin')
    await expect(steve.getByLabel('Role')).toHaveValue('admin')

    await steve.getByRole('button', { name: 'Remove' }).click()
    await expect(steve.getByTestId('member-confirm')).toContainText('Remove Steve from the network?')
    await steve.getByTestId('member-confirm').getByRole('button', { name: 'Remove' }).click()
    await expect(members.getByRole('listitem').filter({ hasText: 'Steve' })).toHaveCount(0)

    await page.getByLabel('Player name').fill('Nobody')
    await page.getByRole('button', { name: 'Add' }).click()
    await expect(page.getByRole('alert')).toContainText('Unknown player')
  })

  test('players without edit rights get no edit controls', async ({ page }) => {
    await mockApi(page, { role: 'player' })
    await page.goto('/energy/0')
    await expect(page.getByTestId('device-list')).toBeVisible()
    await expect(page.getByTestId('network-edit')).toHaveCount(0)
    await expect(page.getByRole('checkbox')).toHaveCount(0)
    await expect(page.getByLabel('Role')).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Add' })).toHaveCount(0)
  })
})
