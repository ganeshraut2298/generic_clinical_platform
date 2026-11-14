import {
  EmrDepartmentCreateInput,
  EmrDepartmentView,
  EmrLayoutSection,
  EmrLayoutSettingsSection,
  EmrStarterDetail,
  EmrStarterSummary,
  StarterKey,
} from '../../../emr/emr.model';

/** What the owner chose in step 1. */
export type SourceChoice =
  | { readonly kind: 'STARTER'; readonly starterKey: StarterKey }
  | { readonly kind: 'BLANK' }
  | { readonly kind: 'COPY'; readonly copyFromDepartmentKey: string };

/** The id the step-2 preview department uses; it does not exist on the server yet. */
export const NEW_DEPARTMENT_KEY = 'new_department';

/** "Turned off by default: A, B and C. You can switch them back on." (SAH-54-1); null when nothing is off. */
export function turnedOffLine(names: readonly string[]): string | null {
  if (names.length === 0) {
    return null;
  }
  const list =
    names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
  return `Turned off by default: ${list}. You can switch them back on.`;
}

/** Names the design adds in brackets on the Template preview (AddDepartment.dc.html, Physiotherapy). */
const PREVIEW_NAMES: Readonly<Record<string, string>> = {
  pain: 'Pain assessment (VAS / NPRS)',
  mmt: 'Manual muscle testing (Oxford 0–5)',
  func_scales: 'Outcome scales (ODI, NDI, WOMAC)',
  modalities: 'Treatment modalities (IFT, TENS, US)',
};

/** Names on the Template preview: the starter's Specialty sections, as the mockup lists them. */
export function previewSections(detail: Pick<EmrStarterDetail, 'sections'>): string[] {
  return detail.sections.filter((s) => s.kind === 'SPECIALTY').map((s) => PREVIEW_NAMES[s.key] ?? s.name);
}

export function sameChoice(a: SourceChoice | null, b: SourceChoice | null): boolean {
  if (!a || !b || a.kind !== b.kind) {
    return a === b;
  }
  if (a.kind === 'STARTER' && b.kind === 'STARTER') {
    return a.starterKey === b.starterKey;
  }
  if (a.kind === 'COPY' && b.kind === 'COPY') {
    return a.copyFromDepartmentKey === b.copyFromDepartmentKey;
  }
  return true;
}

/** "Also create a matching print template" unticked: every Print flag starts off (OQ-45-10). */
export function withPrintFlags(sections: readonly EmrLayoutSection[], keep: boolean): EmrLayoutSection[] {
  return sections.map((s) => ({ ...s, print: keep ? s.print : false }));
}

/** A department that does not exist yet, drawn with the SAH-45 columns in step 2. */
export function previewView(
  name: string,
  base: Pick<EmrStarterSummary, 'initials' | 'colour'>,
  starterKey: StarterKey | null,
  sections: readonly EmrLayoutSection[],
  source: SourceChoice,
): EmrDepartmentView {
  const on = sections.filter((s) => s.shown);
  return {
    departmentKey: NEW_DEPARTMENT_KEY,
    name,
    initials: base.initials,
    colour: base.colour,
    starterKey,
    sourceKind: source.kind,
    copiedFromKey: source.kind === 'COPY' ? source.copyFromDepartmentKey : null,
    status: 'DRAFT',
    publishedVersion: 0,
    hasDraft: false,
    sectionsTotal: sections.length,
    sectionsOn: on.length,
    requiredOn: on.filter((s) => s.required).length,
    doctorCount: 0,
    rooms: [],
    canEdit: true,
    revision: 0,
    layoutSource: 'STARTER',
    sections: [...sections],
  };
}

export interface CreateParts {
  readonly name: string;
  readonly source: SourceChoice;
  readonly copyPrintFlags: boolean;
  /** Sent only when the owner changed something in step 2. */
  readonly changedSections: EmrLayoutSettingsSection[] | null;
  readonly doctorMembershipIds: readonly string[];
  readonly rooms: readonly string[];
}

export function createBody(parts: CreateParts): EmrDepartmentCreateInput {
  const source: EmrDepartmentCreateInput['source'] =
    parts.source.kind === 'STARTER'
      ? { kind: 'STARTER', starterKey: parts.source.starterKey }
      : parts.source.kind === 'COPY'
        ? { kind: 'COPY', copyFromDepartmentKey: parts.source.copyFromDepartmentKey }
        : { kind: 'BLANK' };
  const body: EmrDepartmentCreateInput = {
    name: parts.name.trim(),
    source,
    copyPrintFlags: parts.copyPrintFlags,
    doctorMembershipIds: [...parts.doctorMembershipIds],
    rooms: parts.rooms.map((r) => r.trim()),
  };
  if (parts.changedSections) {
    body.sections = parts.changedSections;
  }
  return body;
}

export const ROOM_MAX = 40;
export const ROOM_LIMIT = 10;
export const NAME_MAX = 60;

/** Room labels are unique ignoring letter case (OQ-45-19). */
export function roomProblem(rooms: readonly string[], text: string): string | null {
  const t = text.trim();
  if (!t) {
    return 'Type a room name first.';
  }
  if (t.length > ROOM_MAX) {
    return `A room name can have at most ${ROOM_MAX} characters.`;
  }
  if (rooms.some((r) => r.toLowerCase() === t.toLowerCase())) {
    return 'That room is already in the list.';
  }
  if (rooms.length >= ROOM_LIMIT) {
    return `A department can have at most ${ROOM_LIMIT} rooms.`;
  }
  return null;
}
