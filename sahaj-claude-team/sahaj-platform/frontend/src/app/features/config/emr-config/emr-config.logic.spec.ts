import { section, sections, summary } from '../../emr/emr.fixtures';
import { EmrLayoutSection } from '../../emr/emr.model';
import {
  applyTarget,
  atEdge,
  countChanges,
  counts,
  groupByPart,
  initialSelection,
  movePart,
  moveWithin,
  sameLayout,
  searchDepartments,
  sectionKindLabel,
  sectionTag,
  settingsOf,
  toSettings,
  toggleFiller,
  toggleFlag,
  toggleRequired,
  toggleShown,
} from './emr-config.logic';

function keys(list: readonly EmrLayoutSection[]): string[] {
  return list.map((s) => s.key);
}

describe('emr-config.logic', () => {
  describe('Show and Required (SAH-45-2)', () => {
    it('clears Required in the same change when a section is hidden', () => {
      const after = toggleShown(sections(), 'complaints');
      const row = after.find((s) => s.key === 'complaints')!;
      expect(row.shown).toBe(false);
      expect(row.required).toBe(false);
    });

    it('cannot turn Required on for a hidden section, but can turn it off', () => {
      const hidden = toggleShown(sections(), 'rx');
      expect(toggleRequired(hidden, 'rx').find((s) => s.key === 'rx')!.required).toBe(false);
      const off = toggleRequired(sections(), 'complaints');
      expect(off.find((s) => s.key === 'complaints')!.required).toBe(false);
    });

    it('keeps a locked section shown', () => {
      const after = toggleShown(sections(), 'allergy');
      expect(after.find((s) => s.key === 'allergy')!.shown).toBe(true);
    });

    it('lets every other section, including Rx and Diagnosis, be switched off and back on', () => {
      let list = toggleShown(sections(), 'diagnosis');
      expect(list.find((s) => s.key === 'diagnosis')!.shown).toBe(false);
      list = toggleShown(list, 'diagnosis');
      expect(list.find((s) => s.key === 'diagnosis')!.shown).toBe(true);
    });

    it('toggles Print, Copy and Collapsed', () => {
      const list = toggleFlag(toggleFlag(toggleFlag(sections(), 'rx', 'print'), 'rx', 'copy'), 'rx', 'collapsed');
      const rx = list.find((s) => s.key === 'rx')!;
      expect([rx.print, rx.copy, rx.collapsed]).toEqual([true, true, true]);
    });

    it('toggles Who can fill and keeps the Doctor, Nurse, Reception order', () => {
      let list = toggleFiller(sections(), 'rx', 'RECEPTION');
      list = toggleFiller(list, 'rx', 'NURSE');
      expect(list.find((s) => s.key === 'rx')!.whoCanFill).toEqual(['DOCTOR', 'NURSE', 'RECEPTION']);
      list = toggleFiller(list, 'rx', 'DOCTOR');
      expect(list.find((s) => s.key === 'rx')!.whoCanFill).toEqual(['NURSE', 'RECEPTION']);
    });
  });

  describe('part and order (SAH-45-3)', () => {
    it('moves a section to the other part and keeps its place in the overall order', () => {
      const after = movePart(sections(), 'vitals');
      expect(after.find((s) => s.key === 'vitals')!.part).toBe('TREATMENT');
      expect(keys(after)).toEqual(keys(sections()));
    });

    it('swaps with the next section of the same part', () => {
      const after = moveWithin(sections(), 'complaints', 1);
      expect(keys(after).slice(0, 3)).toEqual(['vitals', 'allergy', 'complaints']);
      expect(after.map((s) => s.position)).toEqual([1, 2, 3, 4, 5, 6]);
    });

    it('skips sections of the other part when moving', () => {
      const moved = movePart(sections(), 'allergy');
      const after = moveWithin(moved, 'diagnosis', -1);
      expect(keys(after)).toEqual(['vitals', 'complaints', 'diagnosis', 'allergy', 'rx', 'dental_chart']);
    });

    it('changes nothing at the top or bottom of a part', () => {
      expect(keys(moveWithin(sections(), 'vitals', -1))).toEqual(keys(sections()));
      expect(keys(moveWithin(sections(), 'dental_chart', 1))).toEqual(keys(sections()));
      expect(atEdge(sections(), 'vitals', -1)).toBe(true);
      expect(atEdge(sections(), 'vitals', 1)).toBe(false);
      expect(atEdge(sections(), 'dental_chart', 1)).toBe(true);
    });
  });

  describe('unsaved changes count (OQ-45-15)', () => {
    it('is 0 for an untouched layout and when a switch is turned off and on again', () => {
      const base = toSettings(sections());
      expect(countChanges(base, base)).toBe(0);
      const back = toggleShown(toggleShown(sections(), 'rx'), 'rx');
      expect(countChanges(base, toSettings(back))).toBe(0);
    });

    it('counts each differing setting once', () => {
      const base = toSettings(sections());
      const changed = toggleShown(sections(), 'complaints'); // shown and required both change
      expect(countChanges(base, toSettings(changed))).toBe(2);
      expect(countChanges(base, toSettings(toggleFlag(sections(), 'rx', 'print')))).toBe(1);
      expect(countChanges(base, toSettings(movePart(sections(), 'vitals')))).toBe(1);
    });

    it('counts a swap as one step and a longer move as that many steps', () => {
      const base = toSettings(sections());
      expect(countChanges(base, toSettings(moveWithin(sections(), 'complaints', 1)))).toBe(1);
      const twice = moveWithin(moveWithin(sections(), 'vitals', 1), 'vitals', 1);
      expect(countChanges(base, toSettings(twice))).toBe(2);
    });

    it('compares sets of people ignoring order', () => {
      const base = toSettings(sections());
      const changed = toSettings(toggleFiller(sections(), 'rx', 'NURSE'));
      expect(countChanges(base, changed)).toBe(1);
      expect(sameLayout(base, base)).toBe(true);
      expect(sameLayout(base, changed)).toBe(false);
    });
  });

  describe('reset to template (SAH-45-5)', () => {
    it('puts the target settings and order on the sections', () => {
      const working = moveWithin(toggleShown(sections(), 'rx'), 'complaints', 1);
      const target = toSettings(sections());
      const after = applyTarget(working, target);
      expect(keys(after)).toEqual(keys(sections()));
      expect(after.find((s) => s.key === 'rx')!.shown).toBe(true);
      expect(countChanges(target, toSettings(after))).toBe(0);
    });

    it('keeps the section text and fields', () => {
      const after = applyTarget(sections(), toSettings(sections()));
      expect(after[0].name).toBe('Vitals');
      expect(after[0].fields.length).toBe(3);
    });

    it('keeps a section that the target does not list', () => {
      const after = applyTarget(sections(), toSettings(sections()).slice(0, 3));
      expect(after.length).toBe(6);
    });
  });

  describe('words and groups', () => {
    it('names the tag and the kind line as the mockup does', () => {
      expect(sectionTag(section({ key: 'a', kind: 'SPECIALTY' }), 'DN')).toBe('DN specialty');
      expect(sectionTag(section({ key: 'a', kind: 'COMPLIANCE' }), 'DN')).toBe('Compliance');
      expect(sectionTag(section({ key: 'a', kind: 'COMMON' }), 'DN')).toBe('Common');
      expect(sectionKindLabel(section({ key: 'a', kind: 'SPECIALTY' }), 'Dental')).toBe('Dental specialty');
      expect(sectionKindLabel(section({ key: 'a', kind: 'COMMON' }), 'Dental')).toBe('Common section');
    });

    it('groups by part with the shown count and counts shown and required', () => {
      const list = toggleShown(sections(), 'vitals');
      const groups = groupByPart(list);
      expect(groups[0].sections.length).toBe(3);
      expect(groups[0].on).toBe(2);
      expect(groups[1].on).toBe(3);
      expect(counts(list)).toEqual({ on: 5, required: 2 });
    });

    it('selects the first Specialty section, else Vitals', () => {
      expect(initialSelection(sections())).toBe('dental_chart');
      expect(initialSelection(sections().filter((s) => s.kind !== 'SPECIALTY'))).toBe('vitals');
      expect(initialSelection([])).toBeNull();
    });

    it('settingsOf copies only the eight settings', () => {
      expect(Object.keys(settingsOf(sections()[0])).sort()).toEqual(
        ['collapsed', 'copy', 'key', 'part', 'print', 'required', 'shown', 'whoCanFill'],
      );
    });
  });

  describe('search (SAH-45-1)', () => {
    const list = [
      summary({ departmentKey: 'gm', name: 'General Medicine', initials: 'GM' }),
      summary({ departmentKey: 'dental', name: 'Dental', initials: 'DN' }),
      summary({ departmentKey: 'derm', name: 'Dermatology & Cosmetology', initials: 'DR' }),
    ];

    it('matches the start or any part of a name in any letter case', () => {
      expect(searchDepartments(list, 'den').map((d) => d.departmentKey)).toEqual(['dental']);
      expect(searchDepartments(list, 'MEDIC').map((d) => d.departmentKey)).toEqual(['gm']);
      expect(searchDepartments(list, 'cosmet').map((d) => d.departmentKey)).toEqual(['derm']);
    });

    it('matches the initials exactly only', () => {
      expect(searchDepartments(list, 'dn').map((d) => d.departmentKey)).toEqual(['dental']);
      expect(searchDepartments(list, 'dr').map((d) => d.departmentKey)).toEqual(['derm']);
      // One letter is a part of a name, not initials: Medicine, Dental and Dermatology all contain it.
      expect(searchDepartments(list, 'd')).toHaveLength(3);
      expect(searchDepartments(list, 'xq')).toEqual([]);
    });

    it('returns everything for an empty query', () => {
      expect(searchDepartments(list, '  ')).toHaveLength(3);
    });
  });
});
