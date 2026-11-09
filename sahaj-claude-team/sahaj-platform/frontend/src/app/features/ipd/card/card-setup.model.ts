/**
 * SAH-37 IPD patient card setup. Types follow `docs/api/ipd.yaml` (tag SAH-37: `CardSetup`,
 * `CardSetupView`, `CardCatalogue`, `ResolvedCardSetup`, `CardLatestVitals`) one for one. The catalogue,
 * the standard setup, the templates and the role rule all come from the server; nothing here decides
 * who may see what. Labels are owner-typed text: shown with text interpolation only, never as HTML.
 */

export type CardLayout = 'compact' | 'standard' | 'expanded';
export type CardsPerRow = 2 | 3 | 4;
export type CardSectionKey =
  'header' | 'status' | 'stay' | 'care' | 'vitals' | 'clinical' | 'billing';
export type CardFieldKey =
  | 'name'
  | 'uhid'
  | 'ageSex'
  | 'bed'
  | 'ipNumber'
  | 'mobile'
  | 'alerts'
  | 'admittedOn'
  | 'lengthOfStay'
  | 'ward'
  | 'room'
  | 'admissionType'
  | 'doctor'
  | 'bp'
  | 'pulse'
  | 'spo2'
  | 'temp'
  | 'rr'
  | 'ews'
  | 'lastRecorded'
  | 'diagnosis'
  | 'allergies'
  | 'runningBill'
  | 'deposit'
  | 'payer';
export type CardActionKey = 'nursing' | 'emr' | 'transfer' | 'discharge' | 'deposit';
export type CardDataGroup = 'patient' | 'contact' | 'clinical' | 'vitals' | 'billing';
export type CardRole = 'OWNER' | 'DOCTOR' | 'NURSE' | 'FRONT_DESK';
export type CardChangedPart = 'layout' | 'sections' | 'fields' | 'quickActions';
export type VitalMark = 'CHECK' | 'CRITICAL';
export type CardIpdState = 'trial' | 'on' | 'readonly';
export type CardReadOnlyReason = 'ROLE' | 'IPD_READONLY';
export type CardTemplateKey = 'smallClinic' | 'nursingHome' | 'hospital';

// ---- The setup (stored, edited and sent back whole) ----

export interface CardFieldPlacement {
  key: CardFieldKey;
  on: boolean;
  /** "" keeps the standard name. At most 40 characters; plain text. */
  label: string;
}

export interface CardSection {
  key: CardSectionKey;
  on: boolean;
  fields: CardFieldPlacement[];
}

export interface CardActionPlacement {
  key: CardActionKey;
  on: boolean;
}

export interface CardSetup {
  layout: CardLayout;
  cardsPerRow: CardsPerRow;
  sections: CardSection[];
  actions: CardActionPlacement[];
}

/** Body of `PUT /card-setup/draft` and `POST /card-setup/publish`. */
export interface CardSetupWrite {
  baseVersion: number;
  draftRevision: number | null;
  setup: CardSetup;
}

// ---- Catalogue (fixed Sahaj content from the server) ----

export interface CardCatalogueSection {
  key: CardSectionKey;
  name: string;
  description: string;
  locked: boolean;
  safety: boolean;
}

export interface CardCatalogueField {
  key: CardFieldKey;
  name: string;
  group: CardDataGroup;
  required: boolean;
  safety: boolean;
  allowedSections: CardSectionKey[];
  unit?: string;
}

export interface CardCatalogueGroup {
  key: CardDataGroup;
  name: string;
  visibleTo: CardRole[];
}

export type CardActionIcon = 'nurse' | 'emr' | 'swap' | 'door' | 'deposit';

export interface CardCatalogueAction {
  key: CardActionKey;
  name: string;
  icon: CardActionIcon;
  description: string;
  usableBy: CardRole[];
  allowedInReadOnly: boolean;
}

export interface CardCatalogue {
  sections: CardCatalogueSection[];
  fields: CardCatalogueField[];
  groups: CardCatalogueGroup[];
  actions: CardCatalogueAction[];
  viewAsRoles: CardRole[];
  labelMaxLength: number;
}

export interface CardTemplate {
  key: CardTemplateKey;
  name: string;
  description: string;
  layout: CardLayout;
  sections: CardSection[];
  actions: CardActionPlacement[];
}

// ---- Setup view (answer of every /card-setup operation) ----

export interface CardLive {
  version: number;
  isStandard: boolean;
  setup: CardSetup;
  publishedAt: string | null;
  publishedByName: string | null;
}

export interface CardDraft {
  revision: number;
  baseVersion: number;
  setup: CardSetup;
  savedAt: string;
  savedByName: string;
  changedParts: CardChangedPart[];
}

export interface CardVersion {
  version: number;
  summary: string;
  isStandard: boolean;
  changedParts: CardChangedPart[];
  publishedAt: string | null;
  publishedByName: string | null;
  live: boolean;
}

/** `draft` and `versions` are absent (not null) for FRONT_DESK, NURSE and DOCTOR. */
export interface CardSetupView {
  catalogue: CardCatalogue;
  standard: CardSetup;
  templates: CardTemplate[];
  live: CardLive;
  draft?: CardDraft | null;
  versions?: CardVersion[];
  canEdit: boolean;
  readOnlyReason: CardReadOnlyReason | null;
  state: CardIpdState;
}

// ---- The published setup, ready to draw (GET /admissions: cardSetup) ----

export interface ResolvedCardField {
  key: CardFieldKey;
  /** The owner's label, or the standard name when it is empty. */
  label: string;
  group: CardDataGroup;
  safety: boolean;
}

export interface ResolvedCardSection {
  key: CardSectionKey;
  name: string;
  fields: ResolvedCardField[];
}

export interface ResolvedCardSetup {
  version: number;
  isStandard: boolean;
  fallback: boolean;
  layout: CardLayout;
  cardsPerRow: CardsPerRow;
  sections: ResolvedCardSection[];
  actions: CardActionKey[];
}

// ---- Card facts of GET /admissions (SAH-37) ----

export interface CardBpReading {
  systolic: number;
  diastolic: number;
  mark?: VitalMark;
}

export interface CardReading {
  value: number;
  mark?: VitalMark;
}

export interface CardLatestVitals {
  recordedAt: string;
  recordedByName?: string;
  bp?: CardBpReading;
  pulse?: CardReading;
  spo2?: CardReading;
  temp?: CardReading;
  rr?: CardReading;
}

// ---- Fixed texts ----

export const LAYOUT_NAME: Record<CardLayout, string> = {
  compact: 'Compact',
  standard: 'Standard',
  expanded: 'Expanded',
};

export const LAYOUT_TEXT: Record<CardLayout, string> = {
  compact: 'Key facts on one line, icon actions. Fits 4–5 cards a row.',
  standard: 'Sections with labels and vital tiles. The Sahaj default.',
  expanded: 'Every field and section titles.',
};

/** "Desktop: ... 4 to 5 to a row" per layout (mockup `pcPreview`). */
export const LAYOUT_PER_ROW: Record<CardLayout, string> = {
  compact: '4 to 5',
  standard: '3 to 4',
  expanded: '2 to 3',
};

export const LAYOUTS: readonly CardLayout[] = ['compact', 'standard', 'expanded'];
export const COLS_OPTIONS: readonly CardsPerRow[] = [2, 3, 4];

export const ROLE_NAME: Record<CardRole, string> = {
  OWNER: 'Owner / Admin',
  DOCTOR: 'Doctor',
  NURSE: 'Nurse',
  FRONT_DESK: 'Front desk',
};

/** Short role names for the "who" line of a quick action (mockup `who`: the part before " / "). */
export const ROLE_SHORT: Record<CardRole, string> = {
  OWNER: 'Owner',
  DOCTOR: 'Doctor',
  NURSE: 'Nurse',
  FRONT_DESK: 'Front desk',
};

export const PART_WORD: Record<CardChangedPart, string> = {
  layout: 'layout',
  sections: 'sections',
  fields: 'fields',
  quickActions: 'quick actions',
};

/** Units of the five vital readings (mockup `PC_F`). */
export const VITAL_UNIT = {
  bp: 'mmHg',
  pulse: '/min',
  spo2: '%',
  temp: '°C',
  rr: '/min',
} as const;

export type VitalKey = keyof typeof VITAL_UNIT;
export const VITAL_KEYS: readonly VitalKey[] = ['bp', 'pulse', 'spo2', 'temp', 'rr'];

export const MARK_WORD: Record<VitalMark, string> = { CHECK: 'Check', CRITICAL: 'Critical' };
export const MARK_TITLE: Record<VitalMark, string> = {
  CHECK: 'Outside normal range',
  CRITICAL: 'Critical',
};

export const FALLBACK_NOTICE = 'Could not load the card setup. Showing the standard card.';

/** Safety items whose labels are fixed and that are never hidden (DEC-027; the server is the protection). */
export const SAFETY_FIELDS: readonly {
  key: CardFieldKey;
  name: string;
  group: CardDataGroup;
  section: CardSectionKey;
  sectionName: string;
}[] = [
  { key: 'uhid', name: 'UHID', group: 'patient', section: 'header', sectionName: 'Card header' },
  {
    key: 'alerts',
    name: 'Alerts',
    group: 'patient',
    section: 'status',
    sectionName: 'Workflow status',
  },
  { key: 'ews', name: 'Early warning', group: 'vitals', section: 'vitals', sectionName: 'Vitals' },
  {
    key: 'allergies',
    name: 'Allergies',
    group: 'clinical',
    section: 'clinical',
    sectionName: 'Clinical',
  },
];

function field(
  key: CardFieldKey,
  name: string,
  group: CardDataGroup,
  safety = false,
): ResolvedCardField {
  return { key, label: name, group, safety };
}

/**
 * The Sahaj standard card (v1; DEC-027: the SAH-36 card plus the locked Allergies and Early warning
 * lines). Used only when the list answer carries no usable `cardSetup`, with the notice
 * {@link FALLBACK_NOTICE}. The server's own standard setup is what Reset to default uses.
 */
export const STANDARD_RESOLVED: ResolvedCardSetup = {
  version: 1,
  isStandard: true,
  fallback: true,
  layout: 'standard',
  cardsPerRow: 3,
  sections: [
    {
      key: 'header',
      name: 'Card header',
      fields: [
        field('name', 'Patient name', 'patient'),
        field('uhid', 'UHID', 'patient', true),
        field('ageSex', 'Age and gender', 'patient'),
        field('bed', 'Bed', 'patient'),
      ],
    },
    {
      key: 'status',
      name: 'Workflow status',
      fields: [field('alerts', 'Alerts', 'patient', true)],
    },
    {
      key: 'stay',
      name: 'Admission and bed',
      fields: [
        field('admittedOn', 'Admitted on', 'patient'),
        field('lengthOfStay', 'Length of stay', 'patient'),
        field('ward', 'Ward', 'patient'),
        field('room', 'Room', 'patient'),
      ],
    },
    { key: 'care', name: 'Care team', fields: [field('doctor', 'Doctor', 'patient')] },
    { key: 'vitals', name: 'Vitals', fields: [field('ews', 'Early warning', 'vitals', true)] },
    {
      key: 'clinical',
      name: 'Clinical',
      fields: [field('allergies', 'Allergies', 'clinical', true)],
    },
  ],
  actions: ['nursing', 'emr', 'transfer', 'discharge'],
};
