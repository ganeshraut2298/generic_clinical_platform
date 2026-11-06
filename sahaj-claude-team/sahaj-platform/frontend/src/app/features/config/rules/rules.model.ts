/** Types from `docs/api/opd.yaml` (SAH-18): AlertRuleInput, AlertRule, AlertRuleSuggestion, AlertRules. */
export interface AlertRuleInput {
  when: string;
  cond: string;
  then: string;
}

export interface AlertRule extends AlertRuleInput {
  /** Null while the starter rules are not yet saved. */
  id: string | null;
  enabled: boolean;
}

export interface AlertRuleSuggestion extends AlertRuleInput {
  index: number;
  why: string;
}

export interface AlertRuleOptions {
  when: string[];
  cond: string[];
  then: string[];
}

export interface AlertRules {
  rules: AlertRule[];
  suggestions: AlertRuleSuggestion[];
  options: AlertRuleOptions;
}
