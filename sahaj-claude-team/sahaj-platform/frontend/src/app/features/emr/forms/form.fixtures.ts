import { EmrFormDefinition, EmrFormField, EmrFormSettings, EmrFormView } from '../emr-phase3.model';

/** Contract-shaped test data for the form specs: a small diabetic foot style form. No patient data. */

export function ff(over: Partial<EmrFormField> & Pick<EmrFormField, 'key' | 'type'>): EmrFormField {
  return { label: over.key, required: false, width: 6, print: true, copyFromLast: false, whoCanFill: ['DOCTOR'], ...over };
}

export function settings(over: Partial<EmrFormSettings> = {}): EmrFormSettings {
  return {
    useIn: { departmentKeys: ['gm'], places: [] },
    emrPart: 'ASSESSMENT',
    visitTypes: ['NEW_CONSULT'],
    whoCanFill: ['DOCTOR'],
    printLanguage: 'EN',
    approvalRequired: true,
    ...over,
  };
}

export function footDefinition(over: Partial<EmrFormDefinition> = {}): EmrFormDefinition {
  return {
    schemaVersion: 1,
    name: 'Diabetic foot examination',
    settings: settings(),
    fields: [
      ff({ key: 'history', type: 'SECTION_HEADING', label: 'History' }),
      ff({ key: 'ulcer', type: 'YES_NO', label: 'Previous foot ulcer', required: true, options: [{ key: 'yes', label: 'Yes', score: 2 }, { key: 'no', label: 'No', score: 0 }] }),
      ff({ key: 'mono', type: 'NUMBER', label: 'Monofilament: sites felt', unit: 'of 10', min: 0, max: 10, normalFrom: 8, normalTo: 10, required: true, standardCode: 'LOINC 32443-7' }),
      ff({
        key: 'vib',
        type: 'SINGLE_CHOICE',
        label: 'Vibration sense',
        options: [
          { key: 'normal', label: 'Normal', score: 0 },
          { key: 'reduced', label: 'Reduced', score: 1 },
          { key: 'absent', label: 'Absent', score: 1 },
        ],
      }),
      ff({
        key: 'pulses',
        type: 'MULTI_CHOICE',
        label: 'Foot pulses felt',
        options: [
          { key: 'dpr', label: 'DP right', score: 0 },
          { key: 'dpl', label: 'DP left', score: 0 },
          { key: 'ptr', label: 'PT right', score: 0 },
          { key: 'ptl', label: 'PT left', score: 0 },
        ],
      }),
      ff({ key: 'now', type: 'YES_NO', label: 'Ulcer present now', options: [{ key: 'yes', label: 'Yes', score: 2 }, { key: 'no', label: 'No', score: 0 }] }),
      ff({ key: 'site', type: 'LONG_TEXT', label: 'Ulcer site, size and depth', required: true, showIf: { fieldKey: 'now', optionKey: 'yes' } }),
      ff({
        key: 'risk',
        type: 'SCORE',
        label: 'Foot risk score',
        score: {
          sourceFieldKeys: ['ulcer', 'vib', 'now'],
          rules: [
            { kind: 'NUMBER_BELOW', fieldKey: 'mono', threshold: 8, points: 1 },
            { kind: 'CHOSEN_COUNT_BELOW', fieldKey: 'pulses', threshold: 4, points: 1 },
          ],
        },
      }),
      ff({
        key: 'cat',
        type: 'SCORE_BAND',
        label: 'Risk category',
        band: {
          sourceFieldKey: 'risk',
          bands: [
            { from: 0, label: 'Low risk: check every year' },
            { from: 1, label: 'Moderate: check every 6 months' },
            { from: 3, label: 'High: check every 1–3 months, refer' },
          ],
        },
      }),
      ff({ key: 'sig', type: 'SIGNATURE', label: 'Examined by' }),
    ],
    ...over,
  };
}

export function formView(over: Partial<EmrFormView> = {}): EmrFormView {
  const definition = over.definition ?? footDefinition();
  return {
    formKey: 'f_abcdefghij',
    name: definition.name,
    state: 'DRAFT',
    chipVersionNo: 1,
    liveVersionNo: 0,
    starterKey: 'diabetic_foot',
    useIn: definition.settings.useIn,
    emrPart: definition.settings.emrPart,
    fieldCount: definition.fields.length,
    logicCount: 1,
    scoredCount: 2,
    updatedAt: '2026-11-12T05:00:00Z',
    canEdit: true,
    canApprove: true,
    revision: 4,
    definition,
    sentBackReason: null,
    versions: [{ versionNo: 1, state: 'DRAFT', at: '2026-11-12T05:00:00Z', byName: 'Dr Owner' }],
    ...over,
  };
}
