import { IconName } from '../../shared/icon/icon.component';

/**
 * Sections of Clinic setup, from the mockup's `CFG` list (order, group, label, "what" line,
 * "Affects" chips and tips). `built` is true once a story supplies the page; the others open a
 * plain placeholder. Shared by SAH-6 (Team and access) and SAH-7 (Overview, Clinic profile, Specialty template, Case paper and Rx, Fees and billing).
 */
export interface ConfigSection {
  readonly key: string;
  readonly group: string;
  readonly label: string;
  readonly icon: IconName;
  readonly what: string;
  readonly affects: readonly string[];
  readonly tips: readonly string[];
  readonly built: boolean;
  /** Shown only when the clinic's IPD state is trial, on or read-only (SAH-22). */
  readonly requires?: 'ipd';
}

export const CONFIG_SECTIONS: readonly ConfigSection[] = [
  {
    key: 'home',
    group: 'Start here',
    label: 'Overview',
    icon: 'home',
    what: 'How far your clinic is set up, what is left to finish, and the latest changes.',
    affects: [],
    tips: [],
    built: true,
  },
  {
    key: 'modules',
    group: 'Start here',
    label: 'Plan and modules',
    icon: 'sliders',
    what: 'Which modules your clinic uses, and how much of the menu each person sees.',
    affects: ['Everyone'],
    tips: [
      'Start with OPD only. Switch on IPD or HRA when you actually need them, so staff see fewer menus.',
      'Simple mode suits receptionists in their first weeks. Owners usually prefer Advanced.',
      'Switching a module off never deletes data. Past records stay readable.',
    ],
    built: true,
  },
  {
    key: 'profile',
    group: 'Your clinic',
    label: 'Clinic profile',
    icon: 'home',
    what: 'Name, address, logo, timings and holidays. They print on every case paper and bill, and appear in every WhatsApp message.',
    affects: ['Patients', 'Front desk'],
    tips: [
      'Add the clinic name in Marathi or Hindi too. Many older patients read the local script more easily than English.',
      'Enter Diwali and other festival closures a month ahead, so patients with bookings are told in time.',
      'A Google Maps link in the token message saves the front desk many "where is the clinic?" calls.',
    ],
    built: true,
  },
  {
    key: 'team',
    group: 'Your clinic',
    label: 'Team and access',
    icon: 'users',
    what: 'Doctors, staff logins, and who is allowed to do what.',
    affects: ['Everyone'],
    tips: [
      'Give each person their own login, even on a shared reception computer. The change history can then show who did what.',
      'Keep refunds and data export with the owner. That is where money and patient data most often go missing.',
      'Switch staff off when they leave, but do not delete them. Their past entries stay under their name.',
    ],
    built: true,
  },
  {
    key: 'specialty',
    group: 'Your clinic',
    label: 'Specialty template',
    icon: 'sliders',
    what: 'A ready starting point for your specialty: workflow, case paper, price list and Rx favourites.',
    affects: ['Doctor', 'Front desk'],
    tips: [
      'Start from a template and change only what is different in your clinic.',
      'Applying a template later keeps all patients, bills and history as they are.',
    ],
    built: true,
  },
  {
    key: 'workflow',
    group: 'Patient flow',
    label: 'OPD workflow',
    icon: 'arrow',
    what: 'The steps a patient goes through from token to follow-up, and who owns each step.',
    affects: ['Front desk', 'Nurse', 'Doctor'],
    tips: [],
    built: true,
  },
  {
    key: 'slots',
    group: 'Patient flow',
    label: 'Appointments and slots',
    icon: 'calendar',
    what: 'Slot length, OPD sessions, walk-in reserve and booking rules for each doctor.',
    affects: ['Patients', 'Front desk'],
    tips: [
      'Keep a few slots free for walk-ins on busy days, so patients who turn up without booking are not turned away.',
      'A slot length close to how long a consultation really takes keeps the day running on time.',
    ],
    built: true,
  },
  {
    key: 'rules',
    group: 'Patient flow',
    label: 'Alerts and escalation',
    icon: 'bell',
    what: 'When something is late or missed: who gets told, and what gets blocked.',
    affects: ['Nurse', 'Front desk', 'Owner'],
    tips: [],
    built: true,
  },
  {
    key: 'paper',
    group: 'Paper and money',
    label: 'Case paper and Rx',
    icon: 'print',
    what: "What prints on the patient's case paper, in which language, and on which paper.",
    affects: ['Doctor', 'Patients'],
    tips: [],
    built: true,
  },
  {
    key: 'fees',
    group: 'Paper and money',
    label: 'Fees and billing',
    icon: 'receipt',
    what: 'Consultation fees, the follow-up rule, the service price list, discount limits and the bill format.',
    affects: ['Front desk', 'Owner'],
    tips: [],
    built: true,
  },
  {
    key: 'ipbeds',
    group: 'In-patients (IPD)',
    label: 'Wards, rooms and beds',
    icon: 'home',
    what: 'Your wards, optional rooms and every bed with its category. The bed board is built from this.',
    affects: ['Front desk', 'Nurse'],
    tips: [
      'Name beds the way staff say them, for example 101-A, so nobody is confused at night.',
      'Small nursing homes can switch rooms off and keep beds straight under each ward.',
      'Block a bed for repair instead of deleting it. Its history stays.',
    ],
    built: true,
    requires: 'ipd',
  },
  {
    key: 'iprates',
    group: 'In-patients (IPD)',
    label: 'Bed categories and charges',
    icon: 'wallet',
    what: 'Daily bed charge per category, nursing and doctor visit charges, and when a bed day is counted.',
    affects: ['Front desk', 'Owner'],
    tips: [
      'Counting the bed day at midnight is the most common practice in Indian nursing homes.',
      'A higher rate on services for private rooms is usual; keep it simple, like ×1.25 and ×1.5.',
      'Charges post automatically every day, so nobody has to remember to add them.',
    ],
    built: true,
    requires: 'ipd',
  },
  {
    key: 'ipadm',
    group: 'In-patients (IPD)',
    label: 'Admission and deposits',
    icon: 'shield',
    what: 'The minimum deposit for each kind of admission and bed category, and when to warn about the running bill.',
    affects: ['Front desk', 'Owner'],
    tips: [
      'Set the base deposit for each admission type, then add an amount for private rooms and ICU.',
      'Emergency patients can be admitted first and the deposit taken the same day.',
      'Insurance and corporate patients do not need a deposit.',
    ],
    built: true,
    requires: 'ipd',
  },
  {
    key: 'iper',
    group: 'In-patients (IPD)',
    label: 'Emergency and triage',
    icon: 'siren',
    what: 'Triage levels, target time to a doctor, emergency bays and unknown-patient registration.',
    affects: ['Nurse', 'Doctor'],
    tips: [
      '3 colours are enough for a small nursing home; hospitals use 5 levels.',
      'Register an unconscious patient as "Unknown male, about 40" and merge details later.',
      'A card is highlighted when its target time is passed. No message is sent to the duty doctor yet.',
    ],
    built: true,
    requires: 'ipd',
  },
  {
    key: 'ipdis',
    group: 'In-patients (IPD)',
    label: 'Discharge',
    icon: 'door',
    what: 'Discharge types, the summary template, the approval chain and the follow-up after discharge.',
    affects: ['Doctor', 'Front desk', 'Nurse'],
    tips: [
      'The final bill waits until the doctor signs the summary, which avoids re-printing.',
      'A free OPD review after discharge brings patients back and catches problems early.',
      'Keep refunds of unused deposit with the owner or the accounts person.',
    ],
    built: true,
    requires: 'ipd',
  },
  {
    key: 'whatsapp',
    group: 'Patients and data',
    label: 'WhatsApp messages',
    icon: 'msg',
    what: 'Which messages patients get, when they get them, and in which language.',
    affects: ['Patients'],
    tips: [],
    built: false,
  },
  {
    key: 'data',
    group: 'Patients and data',
    label: 'Privacy, ABDM and backup',
    icon: 'shield',
    what: 'Patient consent, ABDM links, record keeping, backup and the access log.',
    affects: ['Owner', 'Patients'],
    tips: [],
    built: true,
  },
];

export function configSectionByKey(key: string): ConfigSection | undefined {
  return CONFIG_SECTIONS.find((section) => section.key === key);
}
