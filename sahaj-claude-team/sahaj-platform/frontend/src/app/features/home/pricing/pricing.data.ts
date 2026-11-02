/** Plan and growth data, copied from the mockup (PLANS and GROW). Server limits arrive in SAH-3. */
export type PlanKey = 'free' | 'pro' | 'multi' | 'custom';

export interface Plan {
  key: PlanKey;
  name: string;
  eyebrow: string;
  description: string;
  points: string[];
  cta: { label: string; action: 'signup' | 'sales' };
}

export const PLANS: readonly Plan[] = [
  {
    key: 'free',
    name: 'Free',
    eyebrow: 'First 12 months',
    description: 'Every new clinic starts here, with all features.',
    points: [
      'OPD tokens, queue, billing, follow-ups',
      'Workflow, case paper and bill designer',
      'Doctor App with stylus and voice',
      '1,000 WhatsApp messages a month',
      'Up to 2 doctors and 5 users',
    ],
    cta: { label: 'Start free', action: 'signup' },
  },
  {
    key: 'pro',
    name: 'Professional',
    eyebrow: 'From year two',
    description: 'Same features and limits as Free, after the first year.',
    points: [
      'Everything in Free',
      'Up to 2 doctors and 5 users',
      'WhatsApp included, top-ups available',
      'UPI AutoPay, cancel any time',
      'Export your data any time',
    ],
    cta: { label: 'Start free', action: 'signup' },
  },
  {
    key: 'multi',
    name: 'Multi-Doctor',
    eyebrow: '3 to 10 doctors',
    description: 'For polyclinics with one front desk.',
    points: [
      'One patient record across doctors',
      'A workflow and fee list per doctor',
      'Shared front desk and single bill',
      'Higher WhatsApp allowance',
    ],
    cta: { label: 'Contact sales', action: 'sales' },
  },
  {
    key: 'custom',
    name: 'Custom',
    eyebrow: 'Branches and nursing homes',
    description: 'For groups with more than one location.',
    points: [
      'Multiple branches, one record',
      'Custom templates and integrations',
      'Dedicated manager and SLA',
      'Assisted data migration',
    ],
    cta: { label: 'Contact sales', action: 'sales' },
  },
];

export interface PlanPrice {
  /** The big text, for example "₹1,999". */
  amount: string;
  /** The small text after it, for example "per year". Empty for Custom. */
  per: string;
  /** Plans without a fixed price are shown smaller. */
  quoted: boolean;
}

/** The price shown on a plan card for the chosen billing period. */
export function planPrice(key: PlanKey, yearly: boolean): PlanPrice {
  switch (key) {
    case 'free':
      return { amount: '₹0', per: 'for 12 months', quoted: false };
    case 'pro':
      return yearly
        ? { amount: '₹1,999', per: 'per year', quoted: false }
        : { amount: '₹299', per: 'per month', quoted: false };
    case 'multi':
      return { amount: 'Per doctor', per: 'quote on request', quoted: true };
    case 'custom':
      return { amount: "Let's talk", per: '', quoted: true };
  }
}

export interface Growth {
  label: string;
  doctors: number;
  staff: number;
  branches: number;
  plans: PlanKey[];
  text: string;
}

export const GROWTH: readonly Growth[] = [
  { label: 'Solo practice', doctors: 1, staff: 1, branches: 1, plans: ['free', 'pro'], text: '1 doctor and a receptionist' },
  { label: 'Small clinic', doctors: 2, staff: 3, branches: 1, plans: ['free', 'pro'], text: '2 doctors, reception, nurse and billing' },
  { label: 'Multi-doctor', doctors: 5, staff: 6, branches: 1, plans: ['multi'], text: '5 doctors sharing one front desk' },
  { label: 'Branches', doctors: 5, staff: 6, branches: 2, plans: ['custom'], text: 'A second branch, one patient record' },
];

export const PRICING_NOTE =
  'Prices exclude 18% GST. Limits are exactly as listed. SMS, if you want it, is billed by your own SMS provider.';
