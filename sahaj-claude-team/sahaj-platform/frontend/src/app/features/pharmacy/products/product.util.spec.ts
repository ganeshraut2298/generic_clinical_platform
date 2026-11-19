import { GLOBAL_ADOPTED, GLOBAL_NOT_ADOPTED, OWN_PRODUCT, productRow } from '../pharmacy.fixtures';
import { DuplicateCandidate } from '../pharmacy.model';
import {
  duplicateMessage,
  duplicateUseLabel,
  filterOptions,
  gstText,
  hsnGstText,
  inUnitChip,
  productSubline,
  rowActions,
  saltLine,
  scheduleChip,
  uomText,
} from './product.util';

describe('product display helpers', () => {
  it('writes UOM as "buy → issue", with the count when the purchase unit does not name it', () => {
    expect(uomText({ purchaseUnit: 'Box of 10', issueUnit: 'vial', conversion: 10 })).toBe('Box of 10 → vial');
    expect(uomText({ purchaseUnit: 'Box', issueUnit: 'vial', conversion: 10 })).toBe('Box of 10 → vial');
    expect(uomText({ purchaseUnit: 'Kit', issueUnit: 'kit', conversion: 1 })).toBe('Kit → kit');
  });

  it('writes HSN and GST as in the mockup and trims a trailing zero', () => {
    expect(hsnGstText({ hsn: '3004', gstRate: '12' })).toBe('3004 · 12%');
    expect(gstText('2.50')).toBe('2.5%');
    expect(gstText('0')).toBe('0%');
  });

  it('writes the salt line and the product subline', () => {
    expect(saltLine(GLOBAL_NOT_ADOPTED)).toBe('Ceftriaxone · 1 g · injection');
    expect(productSubline(GLOBAL_NOT_ADOPTED)).toBe('G-000101 · Sample Labs (fake)');
    expect(productSubline(OWN_PRODUCT)).toBe('U-TCB-0001 · made in-house');
    expect(productSubline({ code: 'U-X-1', manufacturer: null, madeInHouse: false })).toBe('U-X-1');
  });

  it('draws a chip for H, H1 and X and nothing for None', () => {
    expect(scheduleChip('H')?.text).toBe('H');
    expect(scheduleChip('H1')?.text).toBe('H1');
    expect(scheduleChip('X')?.text).toBe('X');
    expect(scheduleChip('NONE')).toBeNull();
  });

  it('uses words for "In this unit"', () => {
    expect(inUnitChip('ADOPTED').text).toBe('Adopted');
    expect(inUnitChip('NOT_ADOPTED').text).toBe('Not adopted');
    expect(inUnitChip('WITHDRAWN').text).toBe('Withdrawn');
    expect(inUnitChip('ACTIVE').text).toBe('Active');
    expect(inUnitChip('INACTIVE').text).toBe('Inactive');
  });

  it('offers Adopt, Local settings or Edit to the owner only, and Adopt never for a withdrawn product', () => {
    expect(rowActions(GLOBAL_NOT_ADOPTED, true)).toEqual(['adopt']);
    expect(rowActions(GLOBAL_ADOPTED, true)).toEqual(['local-settings']);
    expect(rowActions(OWN_PRODUCT, true)).toEqual(['edit', 'local-settings']);
    expect(rowActions(productRow({ inUnit: 'WITHDRAWN', productId: null }), true)).toEqual([]);
    expect(rowActions(productRow({ inUnit: 'WITHDRAWN', productId: 'p' }), true)).toEqual(['local-settings']);
    for (const row of [GLOBAL_NOT_ADOPTED, GLOBAL_ADOPTED, OWN_PRODUCT]) {
      expect(rowActions(row, false)).toEqual([]);
    }
  });

  it('words the duplicate warning of mockup 16 for a global and for an own product', () => {
    const global: DuplicateCandidate = {
      source: 'GLOBAL',
      catalogueProductId: 'c',
      productId: null,
      code: 'G-000101',
      name: 'Sample Ceftriaxone 1 g injection',
      manufacturer: 'Sample Labs (fake)',
      madeInHouse: false,
      inUnit: 'NOT_ADOPTED',
    };
    expect(duplicateMessage(global)).toBe(
      'Possible duplicate: global product "Sample Ceftriaxone 1 g injection" (G-000101, Sample Labs (fake)) has the same salt, strength and form. Use it instead of creating a new one?',
    );
    expect(duplicateUseLabel(global)).toBe('Use global product');
    const own: DuplicateCandidate = {
      ...global,
      source: 'OWN',
      code: 'U-TCB-0001',
      name: 'Test kit',
      manufacturer: null,
      madeInHouse: true,
      inUnit: 'ACTIVE',
    };
    expect(duplicateMessage(own)).toContain('your own product "Test kit" (U-TCB-0001, made in-house)');
    expect(duplicateUseLabel(own)).toBe('Use Test kit');
  });

  it('filters suggestions ignoring capital letters', () => {
    expect(filterOptions(['Box', 'Bottle', 'Vial'], 'bo')).toEqual(['Box', 'Bottle']);
    expect(filterOptions(['Box', 'Vial'], '')).toEqual(['Box', 'Vial']);
    expect(filterOptions(['Box'], null)).toEqual(['Box']);
  });
});
