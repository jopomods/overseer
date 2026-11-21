import { expect, test } from '@playwright/test'
import { GREGTECH_TIERS, tierInName, tiersOf, withTier } from '../src/lib/tiers.ts'

/** Pure tier logic (docs/modules/flux.md); runs once, not per viewport. */
test.describe('voltage tiers in names', { tag: '@desktop' }, () => {
  const gt = tiersOf('gregtech')

  test('the tier is the last word, any case, colour codes ignored', () => {
    expect(tierInName('Factory: Assembler MV', gt)?.name).toBe('MV')
    expect(tierInName('Fusion: Reactor zpm', gt)?.name).toBe('ZPM')
    expect(tierInName('Plant: Diesel 1 §6HV', gt)?.name).toBe('HV')
    expect(tierInName('LuV', gt)?.name).toBe('LuV')
    expect(tierInName('Base: ME system', gt)).toBeUndefined()
    expect(tierInName('Mining: HVAC', gt)).toBeUndefined()
    expect(tierInName('Factory: MV Assembler', gt)).toBeUndefined()
    expect(tierInName('Factory: Assembler MV', tiersOf('none'))).toBeUndefined()
  })

  test('setting a tier replaces, adds or removes the last word', () => {
    const hv = GREGTECH_TIERS.find((t) => t.name === 'HV')!
    expect(withTier('Plant: Diesel 1', hv, gt)).toBe('Plant: Diesel 1 HV')
    expect(withTier('Plant: Diesel 1 MV', hv, gt)).toBe('Plant: Diesel 1 HV')
    expect(withTier('Plant: Diesel 1 mv ', null, gt)).toBe('Plant: Diesel 1')
    expect(withTier('', hv, gt)).toBe('HV')
    expect(withTier('Base: ME', null, gt)).toBe('Base: ME')
  })

  test('all GregTech tiers in order, with their game colours', () => {
    expect(GREGTECH_TIERS.map((t) => t.name).join(' ')).toBe('ULV LV MV HV EV IV LuV ZPM UV UHV UEV UIV UXV OpV MAX')
    expect(GREGTECH_TIERS.find((t) => t.name === 'IV')?.code).toBe('9')
  })
})
