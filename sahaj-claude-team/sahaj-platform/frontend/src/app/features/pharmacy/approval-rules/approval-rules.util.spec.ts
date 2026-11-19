import { approvalRule, approvalRules } from '../pharmacy-c.fixtures';
import {
  RuleDraft,
  TRANSACTION_TYPES,
  allowedConditions,
  conditionText,
  levelText,
  newLevel,
  paiseToRupeesText,
  rupeesTextToPaise,
  sameRules,
  toPresetLimit,
  toRule,
  toRuleDraft,
  validateDrafts,
} from './approval-rules.util';

const drafts = (): RuleDraft[] => approvalRules().rules.map(toRuleDraft);

describe('approval rules util', () => {
  it('knows the nine transaction types in the contract order', () => {
    expect(TRANSACTION_TYPES).toHaveLength(9);
    expect(approvalRules().rules.map((r) => r.transactionType)).toEqual([...TRANSACTION_TYPES]);
  });

  it('turns rupees into paise and back, to the paisa', () => {
    expect(rupeesTextToPaise('50000')).toBe(5000000);
    expect(rupeesTextToPaise('1250.5')).toBe(125050);
    expect(rupeesTextToPaise('999999.99')).toBe(99999999);
    expect(rupeesTextToPaise('99999999')).toBe(9999999900);
    expect(rupeesTextToPaise('99999999.01')).toBeNull();
    expect(rupeesTextToPaise('-5')).toBeNull();
    expect(rupeesTextToPaise('abc')).toBeNull();
    expect(rupeesTextToPaise('1.234')).toBeNull();
    expect(paiseToRupeesText(5000000)).toBe('50000');
    expect(paiseToRupeesText(125050)).toBe('1250.50');
    expect(paiseToRupeesText(null)).toBe('');
  });

  it('accepts a percentage only for the discount at sale and the rate rise', () => {
    expect(allowedConditions('SALE_DISCOUNT')).toContain('ABOVE_PERCENT');
    expect(allowedConditions('GRN_RATE_RISE')).toContain('ABOVE_PERCENT');
    expect(allowedConditions('SALE_DISCOUNT')).not.toContain('ABOVE_AMOUNT');
    expect(allowedConditions('PURCHASE_ORDER')).not.toContain('ABOVE_PERCENT');
    expect(allowedConditions('INDENT')).toContain('URGENT_PRIORITY');
    expect(allowedConditions('STOCK_TRANSFER')).toContain('URGENT_PRIORITY');
    expect(allowedConditions('WRITE_OFF')).not.toContain('URGENT_PRIORITY');
  });

  it('offers the rate rise only Always and Above a percentage, never an amount (DEC-078 item 2)', () => {
    expect(allowedConditions('GRN_RATE_RISE')).toEqual(['ALWAYS', 'ABOVE_PERCENT']);
    const rows = drafts();
    const rise = rows.find((r) => r.transactionType === 'GRN_RATE_RISE') as RuleDraft;
    rise.levels = [{ ...newLevel(), approver: 'OWNER', kind: 'ABOVE_AMOUNT', amount: '5000' }];
    expect(validateDrafts(rows, '').rows.GRN_RATE_RISE?.level[0]).toBe('This condition does not fit this transaction');
    rise.levels = [{ ...newLevel(), approver: 'OWNER', kind: 'ABOVE_PERCENT', percent: '5' }];
    expect(validateDrafts(rows, '').any).toBe(false);
    expect(toRule(rise).levels[0].condition).toEqual({ kind: 'ABOVE_PERCENT', amountPaise: null, percent: '5', storeTypes: null });
  });

  it('keeps an amount limit at ₹ 9,99,99,999 (9,999,999,900 paise) at most (DEC-078 item 1)', () => {
    const rows = drafts();
    rows[0].levels[0].amount = '99999999';
    expect(validateDrafts(rows, '').any).toBe(false);
    expect(toRule(rows[0]).levels[0].condition.amountPaise).toBe(9_999_999_900);
    rows[0].levels[0].amount = '100000000';
    expect(validateDrafts(rows, '').rows.PURCHASE_ORDER?.level[0]).toBe('Enter an amount up to ₹ 9,99,99,999');
  });

  it('round-trips a server rule through the draft without change', () => {
    const rules = approvalRules().rules;
    expect(rules.map((r) => toRule(toRuleDraft(r)))).toEqual(rules);
  });

  it('refuses an amount condition with no limit with "Enter the limit"', () => {
    const rows = drafts();
    rows[0].levels[0].amount = '';
    const result = validateDrafts(rows, '');
    expect(result.any).toBe(true);
    expect(result.rows.PURCHASE_ORDER?.level[0]).toBe('Enter the limit');
  });

  it('refuses a percentage over 100, an amount over ₹ 9,99,99,999 and bad escalation values', () => {
    const rows = drafts();
    rows[7].levels[0].percent = '120';
    rows[0].levels[0].amount = '100000000';
    rows[3].escalateAfterHours = '200';
    rows[3].urgentEscalateAfterMinutes = '2';
    const result = validateDrafts(rows, '');
    expect(result.rows.SALE_DISCOUNT?.level[0]).toBe('Enter a % from 0 to 100');
    expect(result.rows.PURCHASE_ORDER?.level[0]).toBe('Enter an amount up to ₹ 9,99,99,999');
    expect(result.rows.INDENT?.escalate).toBe('Enter 1 to 168 hours');
    expect(result.rows.INDENT?.urgent).toBe('Enter 5 to 1,440 minutes');
  });

  it('asks for the high-value amount when a level uses a high-value item', () => {
    const rows = drafts();
    rows[2].levels = [{ ...newLevel(), approver: 'OWNER', kind: 'HIGH_VALUE_ITEM' }];
    expect(validateDrafts(rows, '').rows.VENDOR_RETURN?.level[0]).toBe('Enter the high-value amount');
    expect(validateDrafts(rows, '5000').any).toBe(false);
    expect(validateDrafts(rows, 'x').highValue).toBe('Enter an amount up to ₹ 9,99,99,999');
  });

  it('accepts the rules the server returned', () => {
    expect(validateDrafts(drafts(), '').any).toBe(false);
  });

  it('drops the urgent time of every type but the indent', () => {
    const row = toRuleDraft(approvalRule({ transactionType: 'WRITE_OFF' }));
    row.urgentEscalateAfterMinutes = '30';
    expect(toRule(row).urgentEscalateAfterMinutes).toBeNull();
  });

  it('writes a level as the story words it', () => {
    const rules = approvalRules().rules;
    expect(levelText(rules[0].levels[0])).toBe('Owner · Above ₹ 50,000');
    expect(levelText(rules[3].levels[0])).toBe('Store approver · Always');
    expect(conditionText(rules[7].levels[0].condition)).toBe('Above 10%');
    expect(conditionText({ kind: 'STORE_TYPE', amountPaise: null, percent: null, storeTypes: ['WARD', 'PHARMACY'] })).toBe(
      'Store type: Ward store, Pharmacy',
    );
  });

  it('compares the draft with what was saved', () => {
    const a = drafts();
    const b = drafts();
    expect(sameRules(a, b)).toBe(true);
    b[0].levels[0].amount = '60000';
    expect(sameRules(a, b)).toBe(false);
  });

  it('builds a preset limit from an amount, a percent or "no amount condition"', () => {
    expect(toPresetLimit('PURCHASE_ORDER', { amount: '50000', percent: '', none: false })).toEqual({
      transactionType: 'PURCHASE_ORDER',
      amountPaise: 5000000,
      percent: null,
      noAmountCondition: false,
    });
    expect(toPresetLimit('SALE_DISCOUNT', { amount: '', percent: '10', none: false })).toEqual({
      transactionType: 'SALE_DISCOUNT',
      amountPaise: null,
      percent: '10',
      noAmountCondition: false,
    });
    expect(toPresetLimit('SALES_RETURN', { amount: '', percent: '', none: true })?.noAmountCondition).toBe(true);
    expect(toPresetLimit('PURCHASE_ORDER', { amount: '', percent: '', none: false })).toBeNull();
    expect(toPresetLimit('SALE_DISCOUNT', { amount: '', percent: '101', none: false })).toBeNull();
  });
});
