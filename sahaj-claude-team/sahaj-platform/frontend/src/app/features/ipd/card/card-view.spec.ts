import { ABILITIES_NURSE, AS_OF, ROW, rowOf } from '../admitted/admitted.fixtures';
import { agoText, buildCard, drawnSetup, ensureSafety } from './card-view';
import { resolvedStandard } from './card-setup.fixtures';
import { ResolvedCardSetup, STANDARD_RESOLVED } from './card-setup.model';

describe('card-view', () => {
  describe('agoText (mockup pcAgo)', () => {
    const at = (minutes: number) =>
      new Date(new Date(AS_OF).getTime() - minutes * 60000).toISOString();
    it('counts whole minutes from the server time', () => {
      expect(agoText(at(0), AS_OF)).toBe('0 min ago');
      expect(agoText(at(35), AS_OF)).toBe('35 min ago');
      expect(agoText(at(59), AS_OF)).toBe('59 min ago');
      expect(agoText(at(60), AS_OF)).toBe('1 h ago');
      expect(agoText(at(125), AS_OF)).toBe('2 h 5 min ago');
    });
    it('never goes below zero when the clocks differ by a moment', () => {
      expect(agoText(at(-3), AS_OF)).toBe('0 min ago');
    });
  });

  describe('drawnSetup (AC61)', () => {
    it('uses the published setup', () => {
      const raw = resolvedStandard();
      expect(drawnSetup(raw)).toEqual({ setup: raw, fallback: false });
    });
    it('uses the standard card with a notice when the server says fallback', () => {
      const raw: ResolvedCardSetup = { ...resolvedStandard(), fallback: true };
      expect(drawnSetup(raw)).toEqual({ setup: raw, fallback: true });
    });
    it('draws the standard card when the setup is missing or unreadable', () => {
      expect(drawnSetup(undefined)).toEqual({ setup: STANDARD_RESOLVED, fallback: true });
      expect(drawnSetup(null)).toEqual({ setup: STANDARD_RESOLVED, fallback: true });
      const odd = { ...resolvedStandard(), layout: 'huge' } as unknown as ResolvedCardSetup;
      expect(drawnSetup(odd).setup).toBe(STANDARD_RESOLVED);
      const noSections = { ...resolvedStandard(), sections: null } as unknown as ResolvedCardSetup;
      expect(drawnSetup(noSections).fallback).toBe(true);
    });
  });

  describe('ensureSafety', () => {
    it('adds back a missing name, UHID, Alerts, Early warning and Allergies with their standard names', () => {
      const bare: ResolvedCardSetup = { ...resolvedStandard(), sections: [] };
      const fixed = ensureSafety(bare);
      const all = fixed.sections.flatMap((s) =>
        s.fields.map((f) => `${s.key}:${f.key}:${f.label}`),
      );
      expect(all).toEqual(
        expect.arrayContaining([
          'header:name:Patient name',
          'header:uhid:UHID',
          'status:alerts:Alerts',
          'vitals:ews:Early warning',
          'clinical:allergies:Allergies',
        ]),
      );
      expect(fixed.sections[0].key).toBe('header');
      expect(fixed.sections[0].fields[0].key).toBe('name');
    });

    it('puts UHID right after the name when the header has other fields', () => {
      const s = resolvedStandard();
      s.sections[0].fields = s.sections[0].fields.filter((f) => f.key !== 'uhid');
      expect(ensureSafety(s).sections[0].fields.map((f) => f.key)).toEqual([
        'name',
        'uhid',
        'ageSex',
        'bed',
      ]);
    });

    it('resets a renamed safety label and leaves other labels alone', () => {
      const s = resolvedStandard();
      s.sections[5].fields[0].label = 'Notes';
      s.sections[3].fields[0].label = 'Consultant';
      const fixed = ensureSafety(s);
      expect(fixed.sections[5].fields[0].label).toBe('Allergies');
      expect(fixed.sections[3].fields[0].label).toBe('Consultant');
    });
  });

  describe('buildCard', () => {
    it('keeps the SAH-36 identity line, Admitted on / Length of stay / Ward / Room / Doctor and the flags', () => {
      const v = buildCard(ROW, ABILITIES_NURSE, resolvedStandard(), AS_OF);
      expect(v.ariaLabel).toBe('Asha Patil, bed 101-A');
      expect(v.identity.map((p) => p.text).join(' · ')).toBe('UHID PT-000042 · 56 · Female');
      expect(v.bed).toEqual({ label: 'Bed', value: '101-A' });
      const stay = v.sections.find((s) => s.key === 'stay');
      expect(stay && stay.kind === 'kv' ? stay.items.map((i) => i.label) : []).toEqual([
        'Admitted on',
        'Length of stay',
        'Ward',
        'Room',
      ]);
    });

    it('marks the first action primary only when it is Nursing', () => {
      const s = resolvedStandard();
      expect(buildCard(ROW, ABILITIES_NURSE, s, AS_OF).actions.map((a) => a.primary)).toEqual([
        true,
        false,
        false,
      ]);
      s.actions = ['emr', 'nursing'];
      expect(buildCard(ROW, ABILITIES_NURSE, s, AS_OF).actions.some((a) => a.primary)).toBe(false);
    });

    it('shows a dash for a mobile that was not sent and for a reading that is absent', () => {
      const s = resolvedStandard();
      s.sections[0].fields.push({
        key: 'mobile',
        label: 'Mobile',
        group: 'contact',
        safety: false,
      });
      s.sections[4].fields.unshift({ key: 'bp', label: 'BP', group: 'vitals', safety: false });
      const owner = {
        ...ABILITIES_NURSE,
        visibleGroups: ['patient', 'contact', 'clinical', 'vitals'] as const,
      };
      const v = buildCard(
        rowOf({ mobile: undefined }),
        { ...owner, visibleGroups: [...owner.visibleGroups] },
        s,
        AS_OF,
      );
      expect(v.identity.map((p) => p.text)).toContain('—');
      const vitals = v.sections.find((x) => x.kind === 'vitals');
      expect(vitals && vitals.kind === 'vitals' ? vitals.tiles[0].value : null).toBe('—');
    });
  });
});
