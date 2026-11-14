import {
  EmrDepartmentSummary,
  EmrFillerRole,
  EmrLayoutSection,
  EmrLayoutSettingsSection,
  EmrPart,
  FILLER_ORDER,
} from '../../emr/emr.model';

/**
 * Pure rules of the "EMR configuration by department" page (SAH-45, ADR-020 D6, mockup #Main).
 * The server stores the order as the array order of the sections; moving a section to the other
 * part changes only its part, and Up or Down swap with the next section of the same part.
 */

export type ToggleFlag = 'print' | 'copy' | 'collapsed';

/** Mockup tag on a row: "<initials> specialty", "Compliance" or "Common". */
export function sectionTag(section: Pick<EmrLayoutSection, 'kind'>, initials: string): string {
  switch (section.kind) {
    case 'SPECIALTY':
      return `${initials} specialty`;
    case 'COMPLIANCE':
      return 'Compliance';
    default:
      return 'Common';
  }
}

/** Mockup "Section settings" kind line: "Dental specialty", "Compliance", "Common section". */
export function sectionKindLabel(section: Pick<EmrLayoutSection, 'kind'>, departmentName: string): string {
  switch (section.kind) {
    case 'SPECIALTY':
      return `${departmentName} specialty`;
    case 'COMPLIANCE':
      return 'Compliance';
    default:
      return 'Common section';
  }
}

export function settingsOf(section: EmrLayoutSection | EmrLayoutSettingsSection): EmrLayoutSettingsSection {
  return {
    key: section.key,
    part: section.part,
    shown: section.shown,
    required: section.required,
    print: section.print,
    copy: section.copy,
    collapsed: section.collapsed,
    whoCanFill: [...section.whoCanFill],
  };
}

export function toSettings(sections: readonly EmrLayoutSection[]): EmrLayoutSettingsSection[] {
  return sections.map(settingsOf);
}

function update(
  sections: readonly EmrLayoutSection[],
  key: string,
  change: (section: EmrLayoutSection) => EmrLayoutSection,
): EmrLayoutSection[] {
  return sections.map((section) => (section.key === key ? change(section) : section));
}

/** Show off clears Required in the same change (SAH-45-2); a locked section stays shown. */
export function toggleShown(sections: readonly EmrLayoutSection[], key: string): EmrLayoutSection[] {
  return update(sections, key, (section) => {
    if (section.hideLocked && section.shown) {
      return section;
    }
    const shown = !section.shown;
    return { ...section, shown, required: shown ? section.required : false };
  });
}

/** Required can only be turned on for a shown section. Turning it off is always possible. */
export function toggleRequired(sections: readonly EmrLayoutSection[], key: string): EmrLayoutSection[] {
  return update(sections, key, (section) => {
    if (!section.shown && !section.required) {
      return section;
    }
    return { ...section, required: !section.required };
  });
}

export function toggleFlag(
  sections: readonly EmrLayoutSection[],
  key: string,
  flag: ToggleFlag,
): EmrLayoutSection[] {
  return update(sections, key, (section) => ({ ...section, [flag]: !section[flag] }));
}

export function toggleFiller(
  sections: readonly EmrLayoutSection[],
  key: string,
  role: EmrFillerRole,
): EmrLayoutSection[] {
  return update(sections, key, (section) => {
    const has = section.whoCanFill.includes(role);
    const next = has
      ? section.whoCanFill.filter((r) => r !== role)
      : FILLER_ORDER.filter((r) => r === role || section.whoCanFill.includes(r));
    return { ...section, whoCanFill: [...next] };
  });
}

export function otherPart(part: EmrPart): EmrPart {
  return part === 'ASSESSMENT' ? 'TREATMENT' : 'ASSESSMENT';
}

/** Moves a section to the other part; it keeps its place in the overall order (SAH-45-3). */
export function movePart(sections: readonly EmrLayoutSection[], key: string): EmrLayoutSection[] {
  return update(sections, key, (section) => ({ ...section, part: otherPart(section.part) }));
}

/** Swaps with the next section of the same part; at the edge of the part nothing changes. */
export function moveWithin(
  sections: readonly EmrLayoutSection[],
  key: string,
  direction: -1 | 1,
): EmrLayoutSection[] {
  const index = sections.findIndex((section) => section.key === key);
  if (index < 0) {
    return [...sections];
  }
  const part = sections[index].part;
  let target = index + direction;
  while (target >= 0 && target < sections.length && sections[target].part !== part) {
    target += direction;
  }
  if (target < 0 || target >= sections.length) {
    return [...sections];
  }
  const next = [...sections];
  [next[index], next[target]] = [next[target], next[index]];
  return renumber(next);
}

export function renumber(sections: readonly EmrLayoutSection[]): EmrLayoutSection[] {
  return sections.map((section, i) => (section.position === i + 1 ? section : { ...section, position: i + 1 }));
}

/** True when the section is first (direction -1) or last (+1) of its part. */
export function atEdge(sections: readonly EmrLayoutSection[], key: string, direction: -1 | 1): boolean {
  const index = sections.findIndex((section) => section.key === key);
  if (index < 0) {
    return true;
  }
  const part = sections[index].part;
  for (let i = index + direction; i >= 0 && i < sections.length; i += direction) {
    if (sections[i].part === part) {
      return false;
    }
  }
  return true;
}

function sameFillers(a: readonly EmrFillerRole[], b: readonly EmrFillerRole[]): boolean {
  return a.length === b.length && a.every((role) => b.includes(role));
}

const FLAGS = ['part', 'shown', 'required', 'print', 'copy', 'collapsed'] as const;

/**
 * "n unsaved changes" (OQ-45-15, ADR-020 D6): the number of distinct (section, property)
 * differences from the last saved layout, plus the moved positions (the number of Up or Down
 * steps that would restore the order). A switch turned off and on again counts 0.
 */
export function countChanges(
  baseline: readonly EmrLayoutSettingsSection[],
  working: readonly EmrLayoutSettingsSection[],
): number {
  const base = new Map(baseline.map((section) => [section.key, section]));
  let count = 0;
  for (const section of working) {
    const was = base.get(section.key);
    if (!was) {
      continue;
    }
    for (const flag of FLAGS) {
      if (section[flag] !== was[flag]) {
        count++;
      }
    }
    if (!sameFillers(section.whoCanFill, was.whoCanFill)) {
      count++;
    }
  }
  const baseIndex = new Map(baseline.map((section, i) => [section.key, i]));
  const order = working.map((section) => baseIndex.get(section.key) ?? -1);
  for (let i = 0; i < order.length; i++) {
    for (let j = i + 1; j < order.length; j++) {
      if (order[i] > order[j]) {
        count++;
      }
    }
  }
  return count;
}

export function sameLayout(
  a: readonly EmrLayoutSettingsSection[],
  b: readonly EmrLayoutSettingsSection[],
): boolean {
  return a.length === b.length && countChanges(a, b) === 0;
}

/**
 * Puts the reset target's settings and order on the working sections. The target holds exactly
 * this department's sections in starter order; a section missing from it keeps its settings.
 */
export function applyTarget(
  sections: readonly EmrLayoutSection[],
  target: readonly EmrLayoutSettingsSection[],
): EmrLayoutSection[] {
  const byKey = new Map(sections.map((section) => [section.key, section]));
  const out: EmrLayoutSection[] = [];
  for (const t of target) {
    const section = byKey.get(t.key);
    if (section) {
      out.push({ ...section, ...settingsOf(t) });
      byKey.delete(t.key);
    }
  }
  for (const rest of byKey.values()) {
    out.push(rest);
  }
  return renumber(out);
}

export interface PartGroup {
  readonly part: EmrPart;
  readonly sections: readonly EmrLayoutSection[];
  readonly on: number;
}

export function groupByPart(sections: readonly EmrLayoutSection[]): PartGroup[] {
  return (['ASSESSMENT', 'TREATMENT'] as const).map((part) => {
    const inPart = sections.filter((section) => section.part === part);
    return { part, sections: inPart, on: inPart.filter((section) => section.shown).length };
  });
}

/** Counts shown at the top of a department: "x sections on · y required". */
export function counts(sections: readonly EmrLayoutSection[]): { on: number; required: number } {
  const shown = sections.filter((section) => section.shown);
  return { on: shown.length, required: shown.filter((section) => section.required).length };
}

/** First Specialty section of the department, else Vitals, else the first section (SAH-45-1). */
export function initialSelection(sections: readonly EmrLayoutSection[]): string | null {
  const specialty = sections.find((section) => section.kind === 'SPECIALTY');
  if (specialty) {
    return specialty.key;
  }
  return sections.find((section) => section.key === 'vitals')?.key ?? sections[0]?.key ?? null;
}

/** Search: text anywhere in the name (any letter case) or the initials exactly (SAH-45-1). */
export function searchDepartments(
  departments: readonly EmrDepartmentSummary[],
  query: string,
): EmrDepartmentSummary[] {
  const q = query.trim().toLowerCase();
  if (!q) {
    return [...departments];
  }
  return departments.filter(
    (department) => department.name.toLowerCase().includes(q) || department.initials.toLowerCase() === q,
  );
}

export const PART_HEAD: Record<EmrPart, { num: string; title: string; sub: string }> = {
  ASSESSMENT: {
    num: '1',
    title: 'Assessment',
    sub: 'What the doctor finds: complaints, history, vitals, examination, findings',
  },
  TREATMENT: {
    num: '2',
    title: 'Treatment',
    sub: 'What the doctor decides and does: diagnosis, medicines, tests, procedures, follow-up',
  },
};

export const VISIT_TYPES = ['New consult', 'Follow-up', 'Procedure visit', 'Teleconsult'] as const;
export type ConfigVisitType = (typeof VISIT_TYPES)[number];

export function plural(n: number, one: string, many: string): string {
  return n === 1 ? one : many;
}
