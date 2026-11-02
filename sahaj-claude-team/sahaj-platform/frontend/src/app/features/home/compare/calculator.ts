import {
  COMPARE_FEATURES,
  CompareFeature,
  FeatureKey,
  FeatureLevel,
  FeatureLevels,
  PRESETS,
  PresetKey,
} from './compare.data';

/** What Sahaj costs over 3 years for up to 2 doctors: free year one, then ₹1,999 a year. */
export const SAHAJ_COST_3Y = 3998;
/** More than this many doctors means a custom plan and a quote. */
export const MAX_DOCTORS_ON_STANDARD_PLAN = 2;
export const WORKING_DAYS_PER_MONTH = 26;

export type SliderKey =
  | 'doctors'
  | 'ppd'
  | 'fuShare'
  | 'fuMiss'
  | 'recover'
  | 'avgBill'
  | 'minsNow'
  | 'minsApp';
export type NumberKey = SliderKey | 'spend' | 'msg';

export interface CalcState {
  preset: PresetKey;
  /** True once the visitor has changed a feature row. */
  edited: boolean;
  features: FeatureLevels;
  doctors: number;
  /** Patients a day. */
  ppd: number;
  /** Software cost today, per month, all doctors. */
  spend: number;
  /** True once the visitor typed their own software cost. */
  spendEdited: boolean;
  /** WhatsApp and SMS cost today, per month. */
  msg: number;
  /** Percent of visits that need a follow-up. */
  fuShare: number;
  /** Percent of follow-ups that never come back. */
  fuMiss: number;
  /** Percent of those brought back by reminders. */
  recover: number;
  avgBill: number;
  minsNow: number;
  minsApp: number;
}

export interface CalcNums {
  cur3: number;
  sah3: number | null;
  custom: boolean;
  back: number;
  val: number;
  hrs: number;
  yours: number;
  gaps: CompareFeature[];
}

export function initialCalcState(): CalcState {
  return {
    preset: 'cloud',
    edited: false,
    features: { ...PRESETS.cloud.features },
    doctors: 2,
    ppd: 40,
    spend: 3000,
    spendEdited: false,
    msg: 500,
    fuShare: 30,
    fuMiss: 30,
    recover: 25,
    avgBill: 300,
    minsNow: 3,
    minsApp: 1.5,
  };
}

export function levelOf(c: CalcState, key: FeatureKey): FeatureLevel {
  return c.features[key] ?? 0;
}

/** The estimates behind the result panel. Same formulas as `calcNums` in the mockup. */
export function calcNums(c: CalcState): CalcNums {
  const cur3 = (c.spend + c.msg) * 36;
  const custom = c.doctors > MAX_DOCTORS_ON_STANDARD_PLAN;
  const sah3 = custom ? null : SAHAJ_COST_3Y;
  const visits = c.ppd * WORKING_DAYS_PER_MONTH * 12;
  const back = Math.round(((visits * c.fuShare) / 100) * (c.fuMiss / 100) * (c.recover / 100));
  const hrs = (Math.max(0, c.minsNow - c.minsApp) * c.ppd * WORKING_DAYS_PER_MONTH) / 60;
  const yours = COMPARE_FEATURES.reduce((sum, f) => sum + levelOf(c, f.key) / 2, 0);
  const gaps = COMPARE_FEATURES.filter((f) => levelOf(c, f.key) < 2);
  return { cur3, sah3, custom, back, val: back * c.avgBill, hrs, yours, gaps };
}

export type Headline =
  | { kind: 'custom'; doctors: number }
  | { kind: 'save'; save: number; gaps: number }
  | { kind: 'free'; gaps: number; versus: 'paper' | 'today' };

/** The big line at the top of the result panel. */
export function headline(c: CalcState, n: CalcNums = calcNums(c)): Headline {
  if (n.custom) {
    return { kind: 'custom', doctors: c.doctors };
  }
  const save = n.cur3 - (n.sah3 ?? 0);
  if (save > 0) {
    return { kind: 'save', save, gaps: n.gaps.length };
  }
  return { kind: 'free', gaps: n.gaps.length, versus: c.preset === 'paper' ? 'paper' : 'today' };
}

/** Choosing "What do you use today?" resets the features and the costs to that option. */
export function applyPreset(c: CalcState, key: PresetKey): CalcState {
  const p = PRESETS[key];
  return {
    ...c,
    preset: key,
    edited: false,
    features: { ...p.features },
    spend: p.perDoc * c.doctors,
    spendEdited: false,
    msg: p.msg,
  };
}

/** Sets one slider or money field. Doctors also moves the software cost until the visitor types one. */
export function setNumber(c: CalcState, key: NumberKey, value: number): CalcState {
  const next: CalcState = { ...c, [key]: value };
  if (key === 'spend') {
    next.spendEdited = true;
  }
  if (key === 'doctors' && !c.spendEdited) {
    next.spend = PRESETS[c.preset].perDoc * value;
  }
  return next;
}

/** Clicking a feature row cycles No, Partly, Yes. */
export function cycleFeature(c: CalcState, key: FeatureKey): CalcState {
  const next = ((levelOf(c, key) + 1) % 3) as FeatureLevel;
  return { ...c, edited: true, features: { ...c.features, [key]: next } };
}

/** Reads a typed amount: keeps digits and one decimal point, empty means 0. */
export function parseAmount(text: string): number {
  return parseFloat(text.replace(/[^\d.]/g, '')) || 0;
}
