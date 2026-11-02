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
    built: false,
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
    built: false,
  },
];

export function configSectionByKey(key: string): ConfigSection | undefined {
  return CONFIG_SECTIONS.find((section) => section.key === key);
}
