/** Types for `/ipd` (SAH-22), from `docs/api/ipd.yaml`. Money is integer paise. */
export type CategoryKey = 'gen' | 'semi' | 'pvt' | 'icu';
export type BedStatus = 'free' | 'occ' | 'clean' | 'blk' | 'res';
export type BedDayCut = 'midnight' | '24h';

export interface IpdBed {
  id: string;
  code: string;
  categoryKey: CategoryKey;
  status: BedStatus;
}

export interface IpdRoom {
  id: string;
  name: string;
  beds: IpdBed[];
}

export interface IpdWard {
  id: string;
  name: string;
  station: string;
  bedCount: number;
  rooms: IpdRoom[];
  beds: IpdBed[];
}

export interface IpdCategory {
  key: CategoryKey;
  name: string;
  ratePaise: number;
  multiplier: number;
  beds: number;
}

export interface IpdCharges {
  bedDayCut: BedDayCut;
  nursingPaise: number;
  visitPaise: number;
}

export interface IpdLayout {
  state: 'trial' | 'on' | 'readonly';
  roomLevel: boolean;
  bedLimit: number;
  bedCount: number;
  wards: IpdWard[];
  categories: IpdCategory[];
  charges: IpdCharges;
}

export const CATEGORY_OPTIONS: readonly { key: CategoryKey; name: string }[] = [
  { key: 'gen', name: 'General' },
  { key: 'semi', name: 'Semi-private' },
  { key: 'pvt', name: 'Private' },
  { key: 'icu', name: 'ICU' },
];

export const BED_DAY_CUTS: readonly { value: BedDayCut; label: string }[] = [
  { value: 'midnight', label: 'At midnight' },
  { value: '24h', label: 'Every 24 hours' },
];

export const MAX_RATE_RUPEES = 100000;
export const MIN_MULTIPLIER = 0.5;
export const MAX_MULTIPLIER = 5;

export function paiseToRupees(paise: number): number {
  return Math.round(paise / 100);
}

export function rupeesToPaise(rupees: number): number {
  return Math.round(rupees) * 100;
}

/** Whole rupees from text, or null when it is not a whole number from 0 to the limit. */
export function parseRupees(text: string, max = MAX_RATE_RUPEES): number | null {
  const t = text.trim();
  if (!/^\d+$/.test(t)) {
    return null;
  }
  const value = Number(t);
  return value <= max ? value : null;
}

/** A multiplier from 0.5 to 5 with at most two decimals, or null. */
export function parseMultiplier(text: string): number | null {
  const t = text.trim();
  if (!/^\d+(\.\d{1,2})?$/.test(t)) {
    return null;
  }
  const value = Number(t);
  return value >= MIN_MULTIPLIER && value <= MAX_MULTIPLIER ? value : null;
}
