import { field, record, recordSection, section } from '../emr.fixtures';
import { EmrLayoutSection, EmrRecordView } from '../emr.model';
import { EmrSpecialtyComponent, EmrSpecialtyProfile, EmrSpecialtyView } from './specialty.model';

/** Contract-shaped test data for the phase 4 specs (ADR-023). No real patient data. */

export interface Anchor {
  key: string;
  component: EmrSpecialtyComponent;
  name?: string;
  required?: boolean;
  part?: 'ASSESSMENT' | 'TREATMENT';
}

export function anchorSection(a: Anchor, profile: EmrSpecialtyProfile): EmrLayoutSection {
  return section({
    key: a.key,
    name: a.name ?? a.key,
    required: a.required ?? false,
    part: a.part ?? 'ASSESSMENT',
    kind: 'SPECIALTY',
    specialtyEditor: { component: a.component, profile },
  });
}

/** The sections every specialty visit also has: Investigations, Diagnosis and the prescription with their shared components. */
export function commonSections(): EmrLayoutSection[] {
  return [
    section({
      key: 'investigations',
      name: 'Investigations',
      part: 'TREATMENT',
      fields: [field({ key: 'tests', valueKind: 'COMPONENT', type: 'SEARCH_LIST', component: { type: 'EMR_LIST', listKind: 'INVESTIGATIONS' } })],
    }),
    section({
      key: 'diagnosis',
      name: 'Diagnosis',
      part: 'TREATMENT',
      required: true,
      fields: [field({ key: 'dx', valueKind: 'COMPONENT', type: 'SEARCH_LIST', component: { type: 'EMR_LIST', listKind: 'DIAGNOSIS' } })],
    }),
    section({
      key: 'rx',
      name: 'Prescription (Rx)',
      part: 'TREATMENT',
      fields: [field({ key: 'drug', valueKind: 'COMPONENT', type: 'SEARCH_LIST', component: { type: 'PRESCRIPTION', listKind: null } })],
    }),
  ];
}

export function specialtyRecord(
  profile: EmrSpecialtyProfile,
  anchors: readonly Anchor[],
  specialty: Partial<EmrSpecialtyView> = {},
  over: Partial<EmrRecordView> = {},
): EmrRecordView {
  const sections = [...anchors.map((a) => anchorSection(a, profile)), ...commonSections()];
  const base = record({ id: 'r1' });
  return {
    ...base,
    layout: { ...base.layout, starterKey: profile, sections },
    sections: sections.map((s) => recordSection(s.key, { status: s.required ? 'REQUIRED' : 'OPTIONAL' })),
    stillNeeded: [],
    specialty: { profile, mode: null, tagNote: 'value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed', ...specialty },
    ...over,
  };
}
