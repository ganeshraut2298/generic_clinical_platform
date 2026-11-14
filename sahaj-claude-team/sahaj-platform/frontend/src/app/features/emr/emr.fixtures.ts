import {
  EmrDepartmentSummary,
  EmrDepartmentView,
  EmrFieldDefinition,
  EmrLayoutSection,
  EmrRecordSection,
  EmrRecordView,
} from './emr.model';
import { EmrCasePaperContext, EmrPastVisitPage } from './emr-phase3.model';

/** Contract-shaped test data for the EMR specs. No real patient data. */

export function field(over: Partial<EmrFieldDefinition> & Pick<EmrFieldDefinition, 'key'>): EmrFieldDefinition {
  return {
    label: over.key,
    type: 'TEXT',
    valueKind: 'TEXT',
    meta: '',
    required: false,
    optionsSource: 'NONE',
    master: null,
    allowFreeText: false,
    options: [],
    columns: [],
    min: null,
    max: null,
    ...over,
  };
}

export function section(over: Partial<EmrLayoutSection> & Pick<EmrLayoutSection, 'key'>): EmrLayoutSection {
  return {
    source: 'LIBRARY',
    name: over.key,
    kind: 'COMMON',
    description: `About ${over.key}`,
    part: 'ASSESSMENT',
    position: 1,
    shown: true,
    required: false,
    print: false,
    copy: false,
    collapsed: false,
    whoCanFill: ['DOCTOR'],
    hideLocked: false,
    hideLockReason: null,
    fields: [],
    ...over,
  };
}

export const VITALS = section({
  key: 'vitals',
  name: 'Vitals',
  position: 1,
  fields: [
    field({ key: 'bp_systolic', label: 'BP systolic', type: 'NUMBER', valueKind: 'NUMBER', meta: 'mmHg · alert above 140', required: true }),
    field({ key: 'bp_diastolic', label: 'BP diastolic', type: 'NUMBER', valueKind: 'NUMBER', required: true }),
    field({ key: 'pulse', label: 'Pulse', type: 'NUMBER', valueKind: 'NUMBER' }),
  ],
});

export const COMPLAINTS = section({
  key: 'complaints',
  name: 'Chief complaints',
  position: 2,
  required: true,
  fields: [
    field({
      key: 'complaint',
      label: 'Complaint',
      type: 'SEARCH_LIST',
      valueKind: 'ITEM_LIST',
      required: true,
      optionsSource: 'MASTER',
      master: 'complaints',
      allowFreeText: true,
    }),
    field({ key: 'duration', label: 'Duration', type: 'NUMBER_UNIT', valueKind: 'NUMBER_UNIT' }),
    field({
      key: 'severity',
      label: 'Severity',
      type: 'DROPDOWN',
      valueKind: 'CHOICE',
      optionsSource: 'FIELD',
      options: [
        { key: 'mild', label: 'Mild', isNone: false },
        { key: 'moderate', label: 'Moderate', isNone: false },
      ],
    }),
  ],
});

export const ALLERGY = section({
  key: 'allergy',
  name: 'Allergies',
  position: 3,
  hideLocked: true,
  hideLockReason: 'Allergies are always shown for patient safety.',
  fields: [field({ key: 'drug_allergy', label: 'Drug allergy' })],
});

export const DIAGNOSIS = section({
  key: 'diagnosis',
  name: 'Diagnosis',
  part: 'TREATMENT',
  position: 4,
  required: true,
  fields: [
    field({
      key: 'provisional',
      label: 'Provisional diagnosis',
      type: 'SEARCH_LIST',
      valueKind: 'ITEM_LIST',
      required: true,
      optionsSource: 'MASTER',
      master: 'diagnoses',
    }),
  ],
});

export const RX = section({
  key: 'rx',
  name: 'Prescription (Rx)',
  part: 'TREATMENT',
  position: 5,
  fields: [field({ key: 'notes', label: 'Instructions' })],
});

export const SPECIALTY_ONE = section({
  key: 'dental_chart',
  name: 'Dental chart (odontogram)',
  kind: 'SPECIALTY',
  part: 'TREATMENT',
  position: 6,
  fields: [],
});

export function sections(): EmrLayoutSection[] {
  return [VITALS, COMPLAINTS, ALLERGY, DIAGNOSIS, RX, SPECIALTY_ONE].map((s) => ({ ...s }));
}

export function summary(over: Partial<EmrDepartmentSummary> = {}): EmrDepartmentSummary {
  return {
    departmentKey: 'gm',
    name: 'General Medicine',
    initials: 'GM',
    colour: '#1D5FA8',
    starterKey: 'gm',
    sourceKind: 'STARTER',
    copiedFromKey: null,
    status: 'TEMPLATE',
    publishedVersion: 0,
    hasDraft: false,
    sectionsTotal: 6,
    sectionsOn: 6,
    requiredOn: 2,
    doctorCount: 0,
    rooms: [],
    ...over,
  };
}

export function departmentView(over: Partial<EmrDepartmentView> = {}): EmrDepartmentView {
  return {
    ...summary(),
    canEdit: true,
    revision: 0,
    layoutSource: 'STARTER',
    sections: sections(),
    ...over,
  };
}

export function recordSection(key: string, over: Partial<EmrRecordSection> = {}): EmrRecordSection {
  return {
    key,
    status: 'OPTIONAL',
    values: {},
    copiedFrom: null,
    copyPendingConfirmation: false,
    updatedAt: null,
    ...over,
  };
}

export function record(over: Partial<EmrRecordView> = {}): EmrRecordView {
  const layout = sections().filter((s) => s.shown);
  return {
    id: 'r1',
    encounterType: 'OPD_VISIT',
    encounterId: 'v1',
    roundDate: null,
    status: 'DRAFT',
    readOnly: false,
    readOnlyReason: null,
    revision: 3,
    header: {
      patientId: 'p1',
      patientName: 'Test Patient',
      initials: 'TP',
      age: 52,
      sex: 'M',
      uhid: 'U-000042',
      allergyText: 'Sulfa drugs',
      chronicText: 'Type 2 DM',
      lastVisitDaysAgo: 14,
      visitOrdinal: 3,
      visitType: 'FOLLOW_UP',
      doctor: { membershipId: 'm1', name: 'Dr Test', room: 'Room 2' },
      admission: null,
      emergency: null,
    },
    layout: {
      departmentKey: 'gm',
      departmentName: 'General Medicine',
      starterKey: 'gm',
      layoutSource: 'PUBLISHED',
      versionNo: 6,
      choiceSource: 'DOCTOR_SPECIALTY',
      takenAt: '2026-11-12T05:00:00Z',
      sections: layout,
    },
    sections: layout.map((s) => recordSection(s.key, { status: s.required ? 'REQUIRED' : 'OPTIONAL' })),
    parts: [
      { part: 'ASSESSMENT', requiredDone: 0, requiredTotal: 1, complete: false, sectionsDone: 0, sectionsTotal: 3 },
      { part: 'TREATMENT', requiredDone: 0, requiredTotal: 1, complete: false, sectionsDone: 0, sectionsTotal: 3 },
    ],
    stillNeeded: ['complaints', 'diagnosis'],
    readyToComplete: false,
    uiState: { part: 'ASSESSMENT', sectionKey: null },
    admissionAdvised: false,
    referralLine: null,
    departmentOptions: [
      { departmentKey: 'gm', name: 'General Medicine' },
      { departmentKey: 'dental', name: 'Dental' },
    ],
    ipd: { state: 'ON', canSwitchOn: false },
    ...over,
  };
}

// ---- phase 3 (ADR-022) ----

export function casePaperContext(over: Partial<EmrCasePaperContext> = {}): EmrCasePaperContext {
  return {
    letterhead: { clinicName: 'Test Clinic', address: '12 Test Road', phone: '020 5555 0100' },
    departmentName: 'General Medicine',
    doctor: { name: 'Dr Test', qualification: 'MBBS, MD', registrationNo: 'MMC 12345' },
    patient: { name: 'Test Patient', age: 52, sex: 'M', uhid: 'U-000042' },
    visitLine: 'OPD · follow-up visit',
    clinicDefaults: { paperSize: 'A4', language: 'EN', capitals: true },
    chosen: { language: null, paperSize: null },
    headings: [
      { heading: 'CHIEF_COMPLAINTS', en: 'Chief complaints', mr: 'मुख्य तक्रारी', hi: 'मुख्य शिकायतें' },
      { heading: 'ALLERGIES', en: 'Allergies', mr: 'ॲलर्जी', hi: 'एलर्जी' },
      { heading: 'MEDICAL_HISTORY', en: 'Medical history', mr: 'पूर्वीचे आजार', hi: 'पुरानी बीमारियाँ' },
      { heading: 'EXAMINATION', en: 'Examination', mr: 'तपासणी', hi: 'जाँच' },
      { heading: 'DIAGNOSIS', en: 'Diagnosis', mr: 'निदान', hi: 'निदान' },
      { heading: 'INVESTIGATIONS', en: 'Investigations advised', mr: 'सुचवलेल्या चाचण्या', hi: 'सुझाई गई जाँचें' },
      { heading: 'MEDICINES', en: 'Medicines ℞', mr: 'औषधे ℞', hi: 'दवाइयाँ ℞' },
      { heading: 'PROCEDURES', en: 'Procedures & services', mr: 'प्रक्रिया व सेवा', hi: 'प्रक्रिया व सेवाएँ' },
      { heading: 'ADVICE', en: 'Advice', mr: 'सल्ला', hi: 'सलाह' },
      { heading: 'FOLLOW_UP', en: 'Follow-up', mr: 'पुढील भेट', hi: 'अगली मुलाक़ात' },
    ],
    sectionHeadings: [
      { sectionKey: 'vitals', heading: 'VITALS_LINE' },
      { sectionKey: 'complaints', heading: 'CHIEF_COMPLAINTS' },
      { sectionKey: 'allergy', heading: 'ALLERGIES' },
      { sectionKey: 'diagnosis', heading: 'DIAGNOSIS' },
      { sectionKey: 'rx', heading: 'MEDICINES' },
    ],
    signature: { signed: false, signerName: null, signedAt: null },
    printBlocked: null,
    share: { available: false, reason: 'Needs the WhatsApp and ABHA services' },
    ...over,
  };
}

export function emptyPastPage(over: Partial<EmrPastVisitPage> = {}): EmrPastVisitPage {
  return { counts: { opd: 0, ipd: 0 }, someHidden: false, items: [], page: 0, pageSize: 8, hasMore: false, ...over };
}
