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

test.describe('voltage tiers, naming hints and priority order', () => {
  test('tiers from names: badges, filter and energy by tier', async ({ page }) => {
    await mockApi(page)
    await page.goto('/energy/0')
    const rows = page.getByTestId('device-list').getByRole('listitem')
    const assembler = rows.filter({ hasText: 'Assembler 2' })
    await expect(assembler.getByTestId('tier-badge')).toHaveText('MV')
    await page.getByTestId('tier-filter').getByRole('tab', { name: 'MV' }).click()
    await expect(rows).toHaveCount(2)
    await expect(page.getByTestId('tiers')).toContainText('ZPM')
  })

  test('without a tier scheme nothing about tiers is shown', async ({ page }) => {
    await mockApi(page, { energyTiers: 'none' })
    await page.goto('/energy/0')
    await expect(page.getByTestId('device-list')).toBeVisible()
    await expect(page.getByTestId('tier-badge')).toHaveCount(0)
    await expect(page.getByTestId('tier-filter')).toHaveCount(0)
    await expect(page.getByTestId('tiers')).toHaveCount(0)
    await page.getByRole('checkbox', { name: 'Select Device 4', exact: true }).check()
    await page.getByTestId('edit-selected').click()
    await expect(page.getByTestId('device-editor').getByLabel('Voltage tier')).toHaveCount(0)
  })

  test('the tier field rewrites the end of the name; hints follow the convention', async ({ page }) => {
    await mockApi(page)
    await page.goto('/energy/0')
    await page.getByTestId('device-list').getByRole('listitem').filter({ hasText: 'Assembler 2' })
      .getByRole('button', { name: 'Device settings' }).click()
    const editor = page.getByTestId('device-editor')
    await editor.getByLabel('Voltage tier').selectOption('HV')
    await expect(editor.getByRole('combobox', { name: /^Name/ })).toHaveValue('Factory: Assembler 2 HV')
    await editor.getByRole('combobox', { name: /^Name/ }).fill('Factory: Assembler MV')
    await expect(editor.getByTestId('naming-hint')).toContainText('another device has this name')
    await editor.getByRole('combobox', { name: /^Name/ }).fill('Assembler')
    await expect(editor.getByTestId('naming-hint')).toContainText('no zone yet')
  })

  test('several devices get a tier without changing the rest of their names', async ({ page }) => {
    await mockApi(page)
    await page.goto('/energy/0')
    await page.getByRole('checkbox', { name: 'Select Device 4', exact: true }).check()
    await page.getByRole('checkbox', { name: 'Select Device 8', exact: true }).check()
    await page.getByTestId('edit-selected').click()
    const editor = page.getByTestId('device-editor')
    await editor.getByLabel('Voltage tier').selectOption('EV')
    await expect(editor.getByTestId('name-preview')).toContainText('Power plant: Device 4 EV, Power plant: Device 8 EV')
  })

  test('priority order: surge first, then from the highest priority', async ({ page }) => {
    await mockApi(page)
    await page.goto('/energy/0')
    const receive = page.getByTestId('priority-receive').locator('ol > li')
    await expect(receive.first()).toContainText('Power surge')
    await expect(receive.nth(1)).toContainText('Priority 11')
    await expect(receive.nth(1)).toContainText('Assembler 2')
  })
})
