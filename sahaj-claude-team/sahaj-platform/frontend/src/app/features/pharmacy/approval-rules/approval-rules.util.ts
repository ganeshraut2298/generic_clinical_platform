import { formatInrPaise } from '../../../shared/format/inr';
import {
  ApprovalCondition,
  ApprovalLevel,
  ApprovalRule,
  ApproverKind,
  ConditionKind,
  PresetLimit,
  RulePreset,
  STORE_TYPE_LABELS,
  StoreType,
  TransactionType,
} from '../pharmacy.model';

/** The nine transaction types in the order the server returns them (OQ-78-25, contract 0.2.0). */
export const TRANSACTION_TYPES: readonly TransactionType[] = [
  'PURCHASE_ORDER',
  'GRN_RATE_RISE',
  'VENDOR_RETURN',
  'INDENT',
  'STOCK_TRANSFER',
  'WRITE_OFF',
  'STOCK_ADJUSTMENT',
  'SALE_DISCOUNT',
  'SALES_RETURN',
];

export const TRANSACTION_LABELS: Record<TransactionType, string> = {
  PURCHASE_ORDER: 'Purchase order',
  GRN_RATE_RISE: 'Receive stock (rate rise)',
  VENDOR_RETURN: 'Vendor return',
  INDENT: 'Indent',
  STOCK_TRANSFER: 'Stock transfer',
  WRITE_OFF: 'Write-off',
  STOCK_ADJUSTMENT: 'Stock adjustment',
  SALE_DISCOUNT: 'Discount at sale',
  SALES_RETURN: 'Sales return',
};

export const APPROVER_LABELS: Record<ApproverKind, string> = {
  STORE_APPROVER: 'Store approver',
  OWNER: 'Owner',
};

export const CONDITION_LABELS: Record<ConditionKind, string> = {
  ALWAYS: 'Always',
  ABOVE_AMOUNT: 'Above an amount',
  ABOVE_PERCENT: 'Above a percentage',
  SCHEDULE_X_ITEM: 'Schedule X item',
  HIGH_VALUE_ITEM: 'High-value item',
  URGENT_PRIORITY: 'Urgent priority',
  STORE_TYPE: 'Store type',
};

export const PRESET_LABELS: Record<RulePreset, string> = {
  LEAN: 'Lean',
  STANDARD: 'Standard',
  STRICT: 'Strict',
  CUSTOM: 'Custom',
};

/** One line each, as the story words them (OQ-78-28). */
export const PRESET_DESCRIPTIONS: Record<Exclude<RulePreset, 'CUSTOM'>, string> = {
  LEAN: 'No store approver step',
  STANDARD: 'One level, the store approver',
  STRICT: 'Store approver, then owner above a limit',
};

export const REPLACE_ROWS_TEXT = 'This replaces all rows below. You can change them afterwards.';
export const ESCALATION_NOTE = 'Escalation starts working when the approvals inbox is released.';
export const REVIEW_BANNER = 'Review these rules before use.';

export const STORE_TYPE_OPTIONS: readonly StoreType[] = ['CENTRAL', 'PHARMACY', 'SUB_STORE', 'WARD'];

/** ₹ 9,99,99,999 in paise: the limit of an amount condition (contract 0.2.1, DEC-078 item 1). */
const MAX_PAISE = 9_999_999_900;
const PERCENT_PATTERN = /^(100(\.0{1,2})?|[0-9]{1,2}(\.[0-9]{1,2})?)$/;
const RUPEES_PATTERN = /^[0-9]{1,9}(\.[0-9]{1,2})?$/;

/** The conditions a transaction type accepts (the server refuses the others, contract 0.2.1). */
export function allowedConditions(type: TransactionType): readonly ConditionKind[] {
  switch (type) {
    case 'SALE_DISCOUNT':
    case 'GRN_RATE_RISE':
      return ['ALWAYS', 'ABOVE_PERCENT'];
    case 'INDENT':
    case 'STOCK_TRANSFER':
      return ['ALWAYS', 'ABOVE_AMOUNT', 'SCHEDULE_X_ITEM', 'HIGH_VALUE_ITEM', 'URGENT_PRIORITY', 'STORE_TYPE'];
    default:
      return ['ALWAYS', 'ABOVE_AMOUNT', 'SCHEDULE_X_ITEM', 'HIGH_VALUE_ITEM', 'STORE_TYPE'];
  }
}

/** A limit is a percentage for the discount at sale and the rate rise, and rupees for the others. */
export function limitIsPercent(type: TransactionType): boolean {
  return type === 'SALE_DISCOUNT' || type === 'GRN_RATE_RISE';
}

// ---------------------------------------------------------------- the draft the owner edits

export interface LevelDraft {
  approver: ApproverKind;
  kind: ConditionKind;
  /** Rupees as typed, for example "50000" or "1250.50". */
  amount: string;
  /** Percent as typed, for example "10". */
  percent: string;
  storeTypes: StoreType[];
}

export interface RuleDraft {
  transactionType: TransactionType;
  /** Zero, one or two levels; level 2 always follows a level 1. */
  levels: LevelDraft[];
  escalateAfterHours: string;
  urgentEscalateAfterMinutes: string;
}

export function paiseToRupeesText(paise: number | null): string {
  if (paise === null) {
    return '';
  }
  const rupees = Math.floor(paise / 100);
  const part = paise % 100;
  return part === 0 ? String(rupees) : `${rupees}.${String(part).padStart(2, '0')}`;
}

/** Rupees typed by the owner to whole paise; null when it is not a valid amount up to ₹9,99,99,999. */
export function rupeesTextToPaise(text: string): number | null {
  const value = text.trim();
  if (!RUPEES_PATTERN.test(value)) {
    return null;
  }
  const [rupees, fraction = ''] = value.split('.');
  const paise = Number(rupees) * 100 + Number(fraction.padEnd(2, '0'));
  return paise > MAX_PAISE ? null : paise;
}

export function toLevelDraft(level: ApprovalLevel): LevelDraft {
  return {
    approver: level.approver,
    kind: level.condition.kind,
    amount: paiseToRupeesText(level.condition.amountPaise),
    percent: level.condition.percent ?? '',
    storeTypes: level.condition.storeTypes ?? [],
  };
}

export function toRuleDraft(rule: ApprovalRule): RuleDraft {
  return {
    transactionType: rule.transactionType,
    levels: [...rule.levels].sort((a, b) => a.level - b.level).map(toLevelDraft),
    escalateAfterHours: rule.escalateAfterHours === null ? '' : String(rule.escalateAfterHours),
    urgentEscalateAfterMinutes:
      rule.urgentEscalateAfterMinutes === null ? '' : String(rule.urgentEscalateAfterMinutes),
  };
}

function toCondition(level: LevelDraft): ApprovalCondition {
  return {
    kind: level.kind,
    amountPaise: level.kind === 'ABOVE_AMOUNT' ? rupeesTextToPaise(level.amount) : null,
    percent: level.kind === 'ABOVE_PERCENT' ? level.percent.trim() : null,
    storeTypes: level.kind === 'STORE_TYPE' ? level.storeTypes : null,
  };
}

function toWhole(text: string): number | null {
  const value = text.trim();
  return value === '' ? null : Number(value);
}

/** The rule as `PUT /approval-rules` takes it; call it after `validateDrafts` found no error. */
export function toRule(draft: RuleDraft): ApprovalRule {
  return {
    transactionType: draft.transactionType,
    levels: draft.levels.map((level, index) => ({
      level: (index + 1) as 1 | 2,
      approver: level.approver,
      condition: toCondition(level),
    })),
    escalateAfterHours: toWhole(draft.escalateAfterHours),
    urgentEscalateAfterMinutes:
      draft.transactionType === 'INDENT' ? toWhole(draft.urgentEscalateAfterMinutes) : null,
  };
}

/** A new level, defaulted the way the preset Standard fills it: the store approver, always. */
export function newLevel(): LevelDraft {
  return { approver: 'STORE_APPROVER', kind: 'ALWAYS', amount: '', percent: '', storeTypes: [] };
}

/** Same rows, compared as the server would: true when nothing differs. */
export function sameRules(a: readonly RuleDraft[], b: readonly RuleDraft[]): boolean {
  return JSON.stringify(a.map(toRule)) === JSON.stringify(b.map(toRule));
}

// ---------------------------------------------------------------- checks before sending

export interface RuleErrors {
  /** Messages by level index (0 and 1), then escalation. */
  level: [string | null, string | null];
  escalate: string | null;
  urgent: string | null;
}

export interface DraftErrors {
  rows: Partial<Record<TransactionType, RuleErrors>>;
  highValue: string | null;
  any: boolean;
}

function levelError(type: TransactionType, level: LevelDraft, highValueSet: boolean): string | null {
  if (!allowedConditions(type).includes(level.kind)) {
    return 'This condition does not fit this transaction';
  }
  if (level.kind === 'ABOVE_AMOUNT') {
    if (level.amount.trim() === '') {
      return 'Enter the limit';
    }
    return rupeesTextToPaise(level.amount) === null ? 'Enter an amount up to ₹ 9,99,99,999' : null;
  }
  if (level.kind === 'ABOVE_PERCENT') {
    if (level.percent.trim() === '') {
      return 'Enter the limit';
    }
    return PERCENT_PATTERN.test(level.percent.trim()) ? null : 'Enter a % from 0 to 100';
  }
  if (level.kind === 'STORE_TYPE' && level.storeTypes.length === 0) {
    return 'Choose the store types';
  }
  if (level.kind === 'HIGH_VALUE_ITEM' && !highValueSet) {
    return 'Enter the high-value amount';
  }
  return null;
}

function wholeInRange(text: string, min: number, max: number): boolean {
  const value = text.trim();
  if (value === '') {
    return true;
  }
  if (!/^[0-9]{1,4}$/.test(value)) {
    return false;
  }
  const n = Number(value);
  return n >= min && n <= max;
}

/** The contract's field rules for the owner's draft, with its own sentences. The server checks again. */
export function validateDrafts(drafts: readonly RuleDraft[], highValueText: string): DraftErrors {
  const highValueEmpty = highValueText.trim() === '';
  const highValuePaise = rupeesTextToPaise(highValueText);
  const result: DraftErrors = { rows: {}, highValue: null, any: false };
  if (!highValueEmpty && highValuePaise === null) {
    result.highValue = 'Enter an amount up to ₹ 9,99,99,999';
    result.any = true;
  }
  for (const draft of drafts) {
    const errors: RuleErrors = {
      level: [
        draft.levels[0] ? levelError(draft.transactionType, draft.levels[0], highValuePaise !== null) : null,
        draft.levels[1] ? levelError(draft.transactionType, draft.levels[1], highValuePaise !== null) : null,
      ],
      escalate: wholeInRange(draft.escalateAfterHours, 1, 168) ? null : 'Enter 1 to 168 hours',
      urgent:
        draft.transactionType === 'INDENT' && !wholeInRange(draft.urgentEscalateAfterMinutes, 5, 1440)
          ? 'Enter 5 to 1,440 minutes'
          : null,
    };
    if (errors.level[0] || errors.level[1] || errors.escalate || errors.urgent) {
      result.rows[draft.transactionType] = errors;
      result.any = true;
    }
  }
  return result;
}

// ---------------------------------------------------------------- read-out text

/** "Store approver · Always", "Owner · Above ₹ 50,000" or "Owner · Above 10%". */
export function levelText(level: ApprovalLevel): string {
  return `${APPROVER_LABELS[level.approver]} · ${conditionText(level.condition)}`;
}

export function conditionText(condition: ApprovalCondition): string {
  switch (condition.kind) {
    case 'ABOVE_AMOUNT':
      return condition.amountPaise === null
        ? CONDITION_LABELS.ABOVE_AMOUNT
        : `Above ${formatInrPaise(condition.amountPaise).replace('₹', '₹ ')}`;
    case 'ABOVE_PERCENT':
      return condition.percent === null ? CONDITION_LABELS.ABOVE_PERCENT : `Above ${condition.percent}%`;
    case 'STORE_TYPE':
      return `Store type: ${(condition.storeTypes ?? []).map((t) => STORE_TYPE_LABELS[t]).join(', ')}`;
    default:
      return CONDITION_LABELS[condition.kind];
  }
}

// ---------------------------------------------------------------- presets

export type PresetKey = Exclude<RulePreset, 'CUSTOM'>;

export interface LimitDraft {
  amount: string;
  percent: string;
  none: boolean;
}

/** The entries of the preset call; an entry with no value and no "no amount condition" is an error the caller shows. */
export function toPresetLimit(type: TransactionType, draft: LimitDraft): PresetLimit | null {
  if (draft.none) {
    return { transactionType: type, amountPaise: null, percent: null, noAmountCondition: true };
  }
  if (limitIsPercent(type)) {
    const percent = draft.percent.trim();
    return PERCENT_PATTERN.test(percent)
      ? { transactionType: type, amountPaise: null, percent, noAmountCondition: false }
      : null;
  }
  const paise = rupeesTextToPaise(draft.amount);
  return paise === null
    ? null
    : { transactionType: type, amountPaise: paise, percent: null, noAmountCondition: false };
}
