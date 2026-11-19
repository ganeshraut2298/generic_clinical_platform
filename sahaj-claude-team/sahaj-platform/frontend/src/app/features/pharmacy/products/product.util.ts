import { Chip } from '../pharmacy-display.util';
import {
  DuplicateCandidate,
  IN_UNIT_LABELS,
  ProductInUnit,
  ProductRow,
  Schedule,
} from '../pharmacy.model';

/** Subtitle of the Products section tab (story 78-1). */
export const PRODUCTS_SUBTITLE = 'Global catalogue + own products';

/** Shown under every seeded catalogue product (DEC-062 Q2, story 78-5). */
export const SAMPLE_NOTE = 'Sample · not clinically validated';

/**
 * "UOM (buy → issue)" of mockup 16: "Box of 10 → vial", and "Kit → kit" when the conversion is 1. The
 * purchase unit may already name its count ("Box of 10"); when it does not, the count is added.
 */
export function uomText(
  product: Pick<ProductRow, 'purchaseUnit' | 'issueUnit' | 'conversion'>,
): string {
  const buy = product.purchaseUnit.trim();
  const count = String(product.conversion);
  const named = new RegExp(`(^|\\D)${count}(\\D|$)`).test(buy);
  const bought = product.conversion > 1 && !named ? `${buy} of ${count}` : buy;
  return `${bought} → ${product.issueUnit.trim()}`;
}

/** "12" is shown as "12%", "2.50" as "2.5%". */
export function gstText(gstRate: string): string {
  const value = Number(gstRate);
  return `${Number.isFinite(value) ? value : gstRate}%`;
}

/** "HSN · GST" column: "3004 · 12%". */
export function hsnGstText(product: Pick<ProductRow, 'hsn' | 'gstRate'>): string {
  return `${product.hsn} · ${gstText(product.gstRate)}`;
}

/** "Salt · strength · form" column. */
export function saltLine(product: Pick<ProductRow, 'salt' | 'strength' | 'form'>): string {
  return `${product.salt} · ${product.strength} · ${product.form}`;
}

/** Second line of the Product column: "G-104233 · Lupin", or "U-BAN-0042 · made in-house". */
export function productSubline(
  product: Pick<ProductRow, 'code' | 'manufacturer' | 'madeInHouse'>,
): string {
  const maker = product.madeInHouse ? 'made in-house' : product.manufacturer;
  return maker ? `${product.code} · ${maker}` : product.code;
}

/** Flags column: a chip for H, H1 or X; null for None (the list shows "—"). */
export function scheduleChip(schedule: Schedule): Chip | null {
  return schedule === 'NONE' ? null : { text: schedule, css: 'p-warn' };
}

/** "In this unit" column. The words carry the meaning; the colour never does. */
export function inUnitChip(inUnit: ProductInUnit): Chip {
  const text = IN_UNIT_LABELS[inUnit];
  switch (inUnit) {
    case 'ADOPTED':
    case 'ACTIVE':
      return { text, css: 'p-good' };
    case 'WITHDRAWN':
      return { text, css: 'p-warn' };
    case 'NOT_ADOPTED':
    case 'INACTIVE':
      return { text, css: 'p-wait' };
  }
}

/** The action of a row: Adopt, Local settings or Edit. Nothing for a reader, or a withdrawn product. */
export type RowAction = 'adopt' | 'local-settings' | 'edit';

export function rowActions(product: ProductRow, canEdit: boolean): RowAction[] {
  if (!canEdit) {
    return [];
  }
  if (product.source === 'GLOBAL') {
    if (product.inUnit === 'NOT_ADOPTED') {
      return ['adopt'];
    }
    return product.productId ? ['local-settings'] : [];
  }
  return product.productId ? ['edit', 'local-settings'] : [];
}

/**
 * The warning of the duplicate check (story 78-6, mockup 16): the global product named with its code,
 * or the owner's own product.
 */
export function duplicateMessage(candidate: DuplicateCandidate): string {
  const maker = candidate.madeInHouse ? 'made in-house' : candidate.manufacturer;
  const by = maker ? `, ${maker}` : '';
  if (candidate.source === 'GLOBAL') {
    return `Possible duplicate: global product "${candidate.name}" (${candidate.code}${by}) has the same salt, strength and form. Use it instead of creating a new one?`;
  }
  return `Possible duplicate: your own product "${candidate.name}" (${candidate.code}${by}) has the same salt, strength and form. Use it instead of creating a new one?`;
}

/** Button of the warning: "Use global product" or "Use <own product>". */
export function duplicateUseLabel(candidate: DuplicateCandidate): string {
  return candidate.source === 'GLOBAL' ? 'Use global product' : `Use ${candidate.name}`;
}

/** Keeps the suggestions that contain what is typed (ignoring capital letters); all when nothing is typed. */
export function filterOptions(options: readonly string[], typed: string | null): string[] {
  const needle = (typed ?? '').trim().toLowerCase();
  return needle ? options.filter((o) => o.toLowerCase().includes(needle)) : [...options];
}
