import {
  CardActionKey,
  CardActionPlacement,
  CardCatalogue,
  CardCatalogueField,
  CardDataGroup,
  CardFieldKey,
  CardFieldPlacement,
  CardLive,
  CardSection,
  CardSectionKey,
  CardSetup,
  CardSetupView,
  CardTemplate,
  CardVersion,
  ResolvedCardSetup,
} from './card-setup.model';
import { resolveSetup } from './card-setup.logic';

/**
 * Contract-shaped data for the SAH-37 specs (`CardSetupView`, `CardCatalogue`, `CardTemplate` in
 * docs/api/ipd.yaml; values from docs/architecture/SAH-37-patient-card-setup.md sections 1 to 3).
 * Invented people and times only.
 */

const field = (
  key: CardFieldKey,
  name: string,
  group: CardDataGroup,
  extra: Partial<CardCatalogueField> = {},
): CardCatalogueField => ({
  key,
  name,
  group,
  required: false,
  safety: false,
  allowedSections: ['header', 'status', 'stay', 'care', 'vitals', 'clinical', 'billing'],
  ...extra,
});

export const CATALOGUE: CardCatalogue = {
  sections: [
    {
      key: 'header',
      name: 'Card header',
      description: 'Name, identifiers and bed · always first',
      locked: true,
      safety: false,
    },
    { key: 'status', name: 'Workflow status', description: 'Alerts', locked: false, safety: true },
    {
      key: 'stay',
      name: 'Admission and bed',
      description: 'Dates, ward and room',
      locked: false,
      safety: false,
    },
    { key: 'care', name: 'Care team', description: 'Doctor', locked: false, safety: false },
    {
      key: 'vitals',
      name: 'Vitals',
      description: 'Latest readings with status',
      locked: false,
      safety: true,
    },
    {
      key: 'clinical',
      name: 'Clinical',
      description: 'Diagnosis and allergies',
      locked: false,
      safety: true,
    },
    {
      key: 'billing',
      name: 'Billing',
      description: 'Running bill and deposit',
      locked: false,
      safety: false,
    },
  ],
  fields: [
    field('name', 'Patient name', 'patient', { required: true, allowedSections: ['header'] }),
    field('uhid', 'UHID', 'patient', { safety: true }),
    field('ageSex', 'Age and gender', 'patient'),
    field('bed', 'Bed', 'patient'),
    field('ipNumber', 'IP number', 'patient'),
    field('mobile', 'Mobile', 'contact'),
    field('alerts', 'Alerts', 'patient', { safety: true }),
    field('admittedOn', 'Admitted on', 'patient'),
    field('lengthOfStay', 'Length of stay', 'patient'),
    field('ward', 'Ward', 'patient'),
    field('room', 'Room', 'patient'),
    field('admissionType', 'Admission type', 'patient'),
    field('doctor', 'Doctor', 'patient'),
    field('bp', 'BP', 'vitals', { unit: 'mmHg' }),
    field('pulse', 'Pulse', 'vitals', { unit: '/min' }),
    field('spo2', 'SpO₂', 'vitals', { unit: '%' }),
    field('temp', 'Temp', 'vitals', { unit: '°C' }),
    field('rr', 'RR', 'vitals', { unit: '/min' }),
    field('ews', 'Early warning', 'vitals', { safety: true }),
    field('lastRecorded', 'Last recorded', 'vitals'),
    field('diagnosis', 'Diagnosis', 'clinical'),
    field('allergies', 'Allergies', 'clinical', { safety: true }),
    field('runningBill', 'Running bill', 'billing'),
    field('deposit', 'Deposit', 'billing'),
    field('payer', 'Payer', 'billing'),
  ],
  groups: [
    {
      key: 'patient',
      name: 'Patient and admission',
      visibleTo: ['OWNER', 'DOCTOR', 'NURSE', 'FRONT_DESK'],
    },
    { key: 'contact', name: 'Contact details', visibleTo: ['OWNER', 'FRONT_DESK'] },
    { key: 'clinical', name: 'Clinical details', visibleTo: ['OWNER', 'DOCTOR', 'NURSE'] },
    { key: 'vitals', name: 'Vitals and early warning', visibleTo: ['OWNER', 'DOCTOR', 'NURSE'] },
    { key: 'billing', name: 'Billing', visibleTo: ['OWNER', 'FRONT_DESK'] },
  ],
  actions: [
    {
      key: 'nursing',
      name: 'Nursing',
      icon: 'nurse',
      description: 'Record vitals and a nursing note',
      usableBy: ['OWNER', 'NURSE', 'DOCTOR'],
      allowedInReadOnly: true,
    },
    {
      key: 'emr',
      name: 'EMR',
      icon: 'emr',
      description: 'Clinical record, medicines and results',
      usableBy: ['OWNER', 'NURSE', 'DOCTOR'],
      allowedInReadOnly: true,
    },
    {
      key: 'transfer',
      name: 'Transfer',
      icon: 'swap',
      description: 'Move to another bed',
      usableBy: ['OWNER', 'NURSE', 'FRONT_DESK'],
      allowedInReadOnly: false,
    },
    {
      key: 'discharge',
      name: 'Discharge',
      icon: 'door',
      description: 'Summary, final bill and bed release',
      usableBy: ['OWNER', 'DOCTOR'],
      allowedInReadOnly: false,
    },
    {
      key: 'deposit',
      name: 'Deposit',
      icon: 'deposit',
      description: 'Running bill and deposit',
      usableBy: ['OWNER', 'FRONT_DESK'],
      allowedInReadOnly: false,
    },
  ],
  viewAsRoles: ['OWNER', 'DOCTOR', 'NURSE', 'FRONT_DESK'],
  labelMaxLength: 40,
};

const f = (key: CardFieldKey, on = true, label = ''): CardFieldPlacement => ({ key, on, label });
const s = (key: CardSectionKey, on: boolean, fields: CardFieldPlacement[]): CardSection => ({
  key,
  on,
  fields,
});
const a = (key: CardActionKey, on = true): CardActionPlacement => ({ key, on });

/** The Sahaj standard setup, v1 (DEC-027). A new object each time, so a spec can change it. */
export function standardSetup(): CardSetup {
  return {
    layout: 'standard',
    cardsPerRow: 3,
    sections: [
      s('header', true, [
        f('name'),
        f('uhid'),
        f('ageSex'),
        f('bed'),
        f('ipNumber', false),
        f('mobile', false),
      ]),
      s('status', true, [f('alerts')]),
      s('stay', true, [
        f('admittedOn'),
        f('lengthOfStay'),
        f('ward'),
        f('room'),
        f('admissionType', false),
      ]),
      s('care', true, [f('doctor')]),
      s('vitals', true, [
        f('bp', false),
        f('pulse', false),
        f('spo2', false),
        f('temp', false),
        f('rr', false),
        f('ews'),
        f('lastRecorded', false),
      ]),
      s('clinical', true, [f('diagnosis', false), f('allergies')]),
      s('billing', false, [f('runningBill'), f('deposit'), f('payer')]),
    ],
    actions: [a('nursing'), a('emr'), a('transfer'), a('discharge'), a('deposit', false)],
  };
}

/** Sets a field on or off wherever it is. */
export function withField(setup: CardSetup, key: CardFieldKey, on: boolean): CardSetup {
  const copy = JSON.parse(JSON.stringify(setup)) as CardSetup;
  for (const section of copy.sections) {
    for (const p of section.fields) {
      if (p.key === key) {
        p.on = on;
      }
    }
  }
  return copy;
}

export function templates(): CardTemplate[] {
  const small = standardSetup();
  small.sections.find((x) => x.key === 'billing')!.on = false;
  const home = standardSetup();
  home.actions.find((x) => x.key === 'deposit')!.on = true;
  const hospital = standardSetup();
  hospital.sections.find((x) => x.key === 'billing')!.on = true;
  hospital.actions.forEach((x) => (x.on = true));
  return [
    {
      key: 'smallClinic',
      name: 'Small clinic',
      description: 'Compact card, no billing',
      layout: 'compact',
      sections: small.sections,
      actions: small.actions,
    },
    {
      key: 'nursingHome',
      name: 'Nursing home',
      description: 'Standard card with deposit action',
      layout: 'standard',
      sections: home.sections,
      actions: home.actions,
    },
    {
      key: 'hospital',
      name: 'Hospital',
      description: 'Expanded card, billing section, all actions',
      layout: 'expanded',
      sections: withField(withField(hospital, 'ipNumber', true), 'admissionType', true).sections,
      actions: hospital.actions,
    },
  ];
}

export const LIVE_V1: CardLive = {
  version: 1,
  isStandard: true,
  setup: standardSetup(),
  publishedAt: null,
  publishedByName: null,
};

export const VERSION_1: CardVersion = {
  version: 1,
  summary: 'Sahaj standard patient card (default)',
  isStandard: true,
  changedParts: [],
  publishedAt: null,
  publishedByName: null,
  live: true,
};

/** The view an OWNER gets for a clinic that never published (no draft). */
export function ownerView(partial: Partial<CardSetupView> = {}): CardSetupView {
  return {
    catalogue: CATALOGUE,
    standard: standardSetup(),
    templates: templates(),
    live: { ...LIVE_V1, setup: standardSetup() },
    draft: null,
    versions: [{ ...VERSION_1 }],
    canEdit: true,
    readOnlyReason: null,
    state: 'on',
    ...partial,
  };
}

/** The view a FRONT_DESK, NURSE or DOCTOR gets: `draft` and `versions` are absent, not null. */
export function staffView(partial: Partial<CardSetupView> = {}): CardSetupView {
  const view = ownerView({ canEdit: false, readOnlyReason: 'ROLE', ...partial });
  delete view.draft;
  delete view.versions;
  return view;
}

/** What `GET /admissions` sends as `cardSetup` for the standard setup. */
export function resolvedStandard(): ResolvedCardSetup {
  return resolveSetup(standardSetup(), CATALOGUE, { version: 1, isStandard: true });
}
