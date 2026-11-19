import { expect, test } from '@playwright/test'
import { mockApi } from './mock-api.ts'

const GRID = '/ae2/minecraft:overworld@120,64,-340'

/** Operating AE2 from the dashboard (ADR-0017), against the mock backend. */
test.describe('ae2 actions', () => {
  test('suspend, resume and cancel a crafting job', async ({ page }) => {
    await mockApi(page, { realistic: true })
    await page.goto(GRID)
    // Busy CPUs come first, so the card moves when its job ends: find it by name.
    const card = page.getByTestId('cpu-list').getByRole('listitem').filter({ hasText: 'Circuits' })
    await card.getByRole('button', { expanded: false }).click()
    const controls = card.getByTestId('cpu-controls')

    const suspend = page.waitForRequest((r) => r.url().endsWith('/api/v1/actions') && r.method() === 'POST')
    await controls.getByRole('button', { name: 'Suspend' }).click()
    expect((await suspend).postDataJSON()).toEqual({
      type: 'ae2.cpu.suspend',
      resource: 'ae2/grid/minecraft:overworld@120,64,-340',
      params: { cpu: 'minecraft:overworld@118,60,-338', suspended: true },
    })
    await expect(card.getByTestId('cpu-suspended')).toHaveText('Suspended')
    await controls.getByRole('button', { name: 'Resume' }).click()
    await expect(card.getByTestId('cpu-suspended')).toHaveCount(0)

    await controls.getByRole('button', { name: 'Cancel job' }).click()
    const confirm = card.getByTestId('cpu-confirm-cancel')
    await expect(confirm).toContainText('Good Electronic Circuit')
    await confirm.getByRole('button', { name: 'Cancel job' }).click()
    await expect(card).toContainText('Idle')
    await expect(controls.getByRole('button', { name: 'Cancel job' })).toHaveCount(0)

    const log = page.getByTestId('action-log')
    await expect(log).toContainText('Circuits: Suspended off → on')
    await expect(log).toContainText('Circuits: Job item:gtceu:good_electronic_circuit → –')
  })

  test('rename a CPU with colour codes and reserve it for players', async ({ page }) => {
    await mockApi(page, { realistic: true })
    await page.goto(GRID)
    const card = page.getByTestId('cpu-list').getByRole('listitem').first()
    await card.getByRole('button', { expanded: false }).click()
    await card.getByRole('button', { name: 'Edit CPU' }).click()
    const form = card.getByTestId('cpu-edit')

    await form.getByLabel('Name').fill('x'.repeat(51))
    await expect(form.getByText('51/50')).toBeVisible()
    await expect(form.getByRole('button', { name: 'Save' })).toBeDisabled()
    await form.getByLabel('Name').fill('§5Assembly')
    await expect(form.getByText('Preview')).toBeVisible()
    await form.getByLabel('Used for').selectOption('player_only')

    const request = page.waitForRequest((r) => r.url().endsWith('/api/v1/actions') && r.method() === 'POST')
    await form.getByRole('button', { name: 'Save' }).click()
    expect((await request).postDataJSON().params).toEqual({
      cpu: 'minecraft:overworld@118,60,-338',
      name: '§5Assembly',
      mode: 'player_only',
    })
    await expect(form).toHaveCount(0)
    await expect(card).toContainText('Assembly')
    await expect(card.getByTestId('cpu-details')).toContainText('Players only')
  })

  test('drive priority', async ({ page }) => {
    await mockApi(page, { realistic: true })
    await page.goto(GRID)
    const drive = page.getByTestId('drive-list').getByRole('listitem').first()
    await expect(drive).toContainText('Priority: 0')
    await drive.getByRole('button', { name: 'Change priority' }).click()
    const form = drive.getByTestId('drive-priority')
    await form.getByLabel('Priority').fill('1.5')
    await expect(form.getByRole('button', { name: 'Save' })).toBeDisabled()
    await form.getByLabel('Priority').fill('-5')
    await form.getByRole('button', { name: 'Save' }).click()
    await expect(drive).toContainText('Priority: -5')
  })

  test('viewers get no controls', async ({ page }) => {
    await mockApi(page, { realistic: true, role: 'player' })
    await page.goto(GRID)
    const card = page.getByTestId('cpu-list').getByRole('listitem').first()
    await card.getByRole('button', { expanded: false }).click()
    await expect(card.getByTestId('cpu-details')).toBeVisible()
    await expect(card.getByTestId('cpu-controls')).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Change priority' })).toHaveCount(0)
  })

  test('pattern providers: tiers, lock state, patterns and the editor', async ({ page }) => {
    await mockApi(page, { realistic: true })
    await page.goto(GRID)
    const list = page.getByTestId('provider-list')
    await expect(list.getByRole('listitem')).toHaveCount(3)
    await expect(page.getByTestId('providers-locked')).toContainText('1 waiting')

    const filter = page.getByTestId('provider-tier-filter')
    await filter.getByRole('tab', { name: 'MV' }).click()
    await expect(list.getByRole('listitem')).toHaveCount(1)
    await expect(list).toContainText('Chemical Reactor MV')
    await expect(list.getByTestId('provider-locked')).toContainText('Until the result is back')
    await filter.getByRole('tab', { name: 'All tiers' }).click()
    await page.getByLabel('Search providers and outputs').fill('resistor')
    await expect(list.getByRole('listitem')).toHaveCount(1)
    await page.getByLabel('Search providers and outputs').fill('')

    const card = list.getByRole('listitem').filter({ hasText: 'Assembler' })
    await card.getByRole('button', { expanded: false }).click()
    await expect(card.getByTestId('pattern-list')).toContainText('Good Electronic Circuit')
    await card.getByRole('button', { name: 'Edit provider' }).click()
    const editor = card.getByTestId('provider-editor')
    await editor.getByLabel('Voltage tier').selectOption('EV')
    await expect(editor.getByLabel('Name')).toHaveValue('§6Assembler EV')
    await editor.getByLabel('Icon').fill('Not An Id')
    await expect(editor.getByRole('button', { name: 'Save' })).toBeDisabled()
    await editor.getByLabel('Icon').fill('gtceu:ev_assembler')
    await editor.getByRole('radiogroup').first().getByRole('radio', { name: 'off' }).click()
    await editor.getByLabel('Lock crafting').selectOption('lock_until_pulse')

    const request = page.waitForRequest((r) => r.url().endsWith('/api/v1/actions') && r.method() === 'POST')
    await editor.getByRole('button', { name: 'Save' }).click()
    expect((await request).postDataJSON()).toEqual({
      type: 'ae2.provider.edit',
      resource: 'ae2/grid/minecraft:overworld@120,64,-340',
      params: { provider: 'minecraft:overworld@110,64,-338', name: '§6Assembler EV', icon: 'gtceu:ev_assembler', blocking: false, lock: 'lock_until_pulse' },
    })
    await expect(editor).toHaveCount(0)
    await expect(card).toContainText('Assembler EV')
    await expect(card.getByTestId('provider-details')).toContainText('Until redstone pulse')
    await expect(page.getByTestId('action-log')).toContainText('Assembler EV: Icon gtceu:hv_assembler → gtceu:ev_assembler')
  })

  test('remote crafting: the missing amount of a watched item, planned and started', async ({ page }) => {
    await mockApi(page, { realistic: true })
    await page.goto(GRID)
    await page.getByTestId('watchlist').getByRole('button', { name: 'Craft the missing amount' }).click()
    const panel = page.getByTestId('craft-panel')
    await expect(panel).toContainText('Good Electronic Circuit')
    await expect(panel.getByLabel('Amount')).toHaveValue('91')

    const plan = page.waitForRequest((r) => r.url().endsWith('/api/v1/actions') && r.method() === 'POST')
    await panel.getByRole('button', { name: 'Plan' }).click()
    expect((await plan).postDataJSON()).toEqual({
      type: 'ae2.craft.plan',
      resource: 'ae2/grid/minecraft:overworld@120,64,-340',
      params: { key: 'item:gtceu:good_electronic_circuit', amount: 91 },
    })
    const view = panel.getByTestId('craft-plan')
    await expect(view).toContainText('Ready')
    await expect(view.getByTestId('craft-used')).toContainText('Resistor')
    await expect(view.getByLabel('Crafting CPU').locator('option:not([disabled])')).toHaveCount(2) // automatic + the idle CPU

    const start = page.waitForRequest((r) => r.url().endsWith('/api/v1/actions') && r.method() === 'POST')
    await view.getByRole('button', { name: 'Start' }).click()
    expect((await start).postDataJSON().params).toEqual({ plan: '0000000000000001' })
    await expect(panel.getByTestId('craft-started')).toHaveText('Job started on CPU #3.')
  })

  test('remote crafting: search, missing items block the start', async ({ page }) => {
    await mockApi(page, { realistic: true })
    await page.goto(GRID)
    await page.getByTestId('craft-search-open').click()
    await page.getByTestId('craft-search').getByLabel('Search craftable items').fill('resis')
    await page.getByTestId('craft-search').getByRole('button', { name: 'Resistor' }).click()
    const panel = page.getByTestId('craft-panel')
    await panel.getByLabel('Amount').fill('0')
    await expect(panel.getByRole('button', { name: 'Plan' })).toBeDisabled()
    await panel.getByLabel('Amount').fill('300')
    await panel.getByRole('button', { name: 'Plan' }).click()
    await expect(panel.getByTestId('craft-plan')).toContainText('Items missing')
    await expect(panel.getByTestId('craft-missing')).toContainText('Fine Copper Wire')
    await expect(panel.getByRole('button', { name: 'Start' })).toHaveCount(0)
    // The contents list offers crafting for craftable items too.
    await expect(page.getByTestId('inventory-list').getByRole('button', { name: 'Craft Iron Ingot' })).toBeVisible()
  })

  test('viewers cannot craft', async ({ page }) => {
    await mockApi(page, { realistic: true, role: 'player' })
    await page.goto(GRID)
    await expect(page.getByTestId('inventory-list')).toBeVisible()
    await expect(page.getByTestId('craft-search-open')).toHaveCount(0)
    await expect(page.getByRole('button', { name: /^Craft / })).toHaveCount(0)
  })

  test('diagnosis: stuck jobs and devices without channel', async ({ page }) => {
    await mockApi(page, { realistic: true })
    await page.goto(GRID)
    await expect(page.getByText('1 stuck')).toBeVisible()
    const stuck = page.getByTestId('cpu-list').getByRole('listitem').filter({ hasText: 'Polyethylene' })
    await expect(stuck.getByTestId('cpu-stalled')).toContainText('No progress for 12')
    const offline = page.getByTestId('offline-devices')
    await expect(offline.getByRole('listitem')).toHaveCount(2)
    await expect(offline).toContainText('ME Import Bus')
    await expect(offline).toContainText('105, 64, -330 · east side')
  })

  test('import and export buses: what they face, filter and cards', async ({ page }) => {
    await mockApi(page, { realistic: true })
    await page.goto(GRID)
    const list = page.getByTestId('bus-list')
    await expect(list.getByRole('listitem')).toHaveCount(4)
    await page.getByTestId('bus-toolbar').getByRole('radio', { name: 'Import', exact: true }).click()
    await expect(list.getByRole('listitem')).toHaveCount(2)
    await expect(list).toContainText('Offline')
    await page.getByTestId('bus-toolbar').getByRole('radio', { name: 'All', exact: true }).click()
    await page.getByLabel('Search buses, machines and items').fill('raw copper')
    await expect(list.getByRole('listitem')).toHaveCount(1)
    await expect(list).toContainText('LV Macerator')
    await expect(list).toContainText('Speed ×2')
    await expect(list).toContainText('Round robin')
    await page.getByLabel('Search buses, machines and items').fill('circuit restock')
    await expect(list).toContainText('With redstone')
    await expect(list).toContainText('Crafted only')
    await expect(list).toContainText('Crafting jobs: 1')
  })
})
