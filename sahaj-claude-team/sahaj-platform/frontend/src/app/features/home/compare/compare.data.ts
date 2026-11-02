/** Comparison data, copied from the mockup (CMP_FEATS and PRESETS). */
export type FeatureKey =
  | 'token'
  | 'family'
  | 'sendin'
  | 'appt'
  | 'stylus'
  | 'voice'
  | 'paper'
  | 'qr'
  | 'furule'
  | 'upi'
  | 'disc'
  | 'workflow'
  | 'rules'
  | 'dash'
  | 'wa'
  | 'abdm';

/** 0 = No, 1 = Partly, 2 = Yes */
export type FeatureLevel = 0 | 1 | 2;
export type FeatureLevels = Partial<Record<FeatureKey, FeatureLevel>>;

export const LEVEL_LABELS = ['No', 'Partly', 'Yes'] as const;

export interface CompareFeature {
  group: 'Front desk' | 'Doctor' | 'Billing' | 'Owner';
  key: FeatureKey;
  label: string;
}

export const COMPARE_FEATURES: readonly CompareFeature[] = [
  { group: 'Front desk', key: 'token', label: 'Walk-in token by mobile number' },
  { group: 'Front desk', key: 'family', label: 'Whole family under one mobile number' },
  { group: 'Front desk', key: 'sendin', label: 'Live queue with "send in now"' },
  { group: 'Front desk', key: 'appt', label: 'Appointments with WhatsApp confirmation' },
  { group: 'Doctor', key: 'stylus', label: 'Doctor writes with a stylus on a tablet' },
  { group: 'Doctor', key: 'voice', label: 'Voice to prescription in Indian languages' },
  { group: 'Doctor', key: 'paper', label: 'Case paper layout you design yourself' },
  { group: 'Doctor', key: 'qr', label: 'Printed case paper with a QR to full history' },
  { group: 'Billing', key: 'furule', label: 'Follow-up fee rule applied automatically' },
  { group: 'Billing', key: 'upi', label: 'UPI QR on the bill, dues carried forward' },
  { group: 'Billing', key: 'disc', label: 'Discount limits with owner approval' },
  { group: 'Owner', key: 'workflow', label: 'Change your own workflow, no vendor needed' },
  { group: 'Owner', key: 'rules', label: 'Alerts when a step is missed' },
  { group: 'Owner', key: 'dash', label: 'Dashboard of missed steps by person' },
  { group: 'Owner', key: 'wa', label: 'WhatsApp reminders included in the price' },
  { group: 'Owner', key: 'abdm', label: 'ABDM: ABHA, Scan & Share, record linking' },
];

export type PresetKey = 'paper' | 'basic' | 'cloud' | 'govt';

export interface Preset {
  label: string;
  note: string;
  /** Software cost per doctor per month, in rupees. */
  perDoc: number;
  /** WhatsApp and SMS cost per month, in rupees. */
  msg: number;
  features: FeatureLevels;
}

export const PRESETS: Record<PresetKey, Preset> = {
  paper: { label: 'Paper register and files', note: 'No software today', perDoc: 0, msg: 0, features: {} },
  basic: {
    label: 'Basic billing software',
    note: 'Desktop billing, about ₹400 a month',
    perDoc: 400,
    msg: 0,
    features: { token: 1, upi: 1, disc: 1 },
  },
  cloud: {
    label: 'Typical cloud clinic software',
    note: 'About ₹1,000–2,000 per doctor a month',
    perDoc: 1500,
    msg: 500,
    features: {
      token: 2, family: 1, sendin: 2, appt: 2, stylus: 0, voice: 1, paper: 1, qr: 0,
      furule: 1, upi: 2, disc: 1, workflow: 0, rules: 0, dash: 1, wa: 1, abdm: 2,
    },
  },
  govt: {
    label: 'Low-cost HMIS',
    note: 'About ₹499 a month for 5 users',
    perDoc: 250,
    msg: 0,
    features: {
      token: 2, family: 1, sendin: 1, appt: 1, stylus: 0, voice: 2, paper: 0, qr: 0,
      furule: 0, upi: 1, disc: 0, workflow: 0, rules: 0, dash: 1, wa: 0, abdm: 2,
    },
  },
};

export const PRESET_KEYS = Object.keys(PRESETS) as PresetKey[];
