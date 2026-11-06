/** Types for `/platform/modules` (SAH-19), from `docs/api/platform.yaml` (ModulesView). */
export type Plan = 'FREE' | 'PRO';
export type IpdState = 'off' | 'trial' | 'on' | 'readonly';

export interface ModulesView {
  plan: Plan;
  ipd: {
    state: IpdState;
    trialEndsAt?: string | null;
    requested: boolean;
    requestedAt?: string | null;
  };
  hra: { on: boolean };
  ipdTrialAvailable: boolean;
  menuCounts: { simple: number; advanced: number };
}
