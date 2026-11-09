import { CATALOGUE, standardSetup, templates, withField } from './card-setup.fixtures';
import {
  abilitiesFor,
  applyTemplate,
  canMoveAction,
  canMoveField,
  canMoveSection,
  canonicalSetup,
  changedParts,
  draftPillText,
  fieldsOnText,
  groupsFor,
  hiddenCounts,
  hiddenText,
  labelLocked,
  moveAction,
  moveActionBy,
  moveField,
  moveFieldBy,
  moveSection,
  moveSectionBy,
  publishToast,
  resolveSetup,
  sameSetup,
  setCardsPerRow,
  setFieldLabel,
  setLayout,
  tileLine,
  toggleAction,
  toggleField,
  toggleSection,
  updatedText,
} from './card-setup.logic';
import { CardSetup } from './card-setup.model';

const keysOf = (s: CardSetup, section: string) =>
  s.sections.find((x) => x.key === section)!.fields.map((f) => f.key);
const must = <T extends { ok: boolean }>(r: T): Extract<T, { ok: true }> => {
  expect(r.ok).toBe(true);
  return r as Extract<T, { ok: true }>;
};

describe('card-setup.logic', () => {
  describe('equality and changed parts', () => {
    it('treats labels with edge spaces like the trimmed label', () => {
      const a = standardSetup();
      const b = standardSetup();
      b.sections[2].fields[0].label = '  ';
      expect(sameSetup(a, b)).toBe(true);
      b.sections[2].fields[0].label = ' Date in ';
      expect(sameSetup(a, b)).toBe(false);
      expect(canonicalSetup(b).sections[2].fields[0].label).toBe('Date in');
    });

    it('names the parts that differ in the order layout, sections, fields, quickActions (AC3)', () => {
      const live = standardSetup();
      expect(changedParts(live, live)).toEqual([]);
      expect(changedParts(setCardsPerRow(live, 4), live)).toEqual(['layout']);
      expect(changedParts(setLayout(live, 'compact'), live)).toEqual(['layout']);
      expect(changedParts(moveSectionBy(live, 'care', 1), live)).toEqual(['sections', 'fields']);
      expect(changedParts(must(toggleSection(live, CATALOGUE, 'billing')).setup, live)).toEqual([
        'sections',
      ]);
      expect(changedParts(must(toggleField(live, CATALOGUE, 'ipNumber')).setup, live)).toEqual([
        'fields',
      ]);
      expect(changedParts(toggleAction(live, 'deposit'), live)).toEqual(['quickActions']);
      const many = toggleAction(setLayout(live, 'expanded'), 'deposit');
      expect(changedParts(many, live)).toEqual(['layout', 'quickActions']);
    });

    it('counts a switch changed and changed back as no change (AC8)', () => {
      const live = standardSetup();
      const there = toggleAction(live, 'emr');
      expect(changedParts(toggleAction(there, 'emr'), live)).toEqual([]);
    });

    it('words: pill, toast and updated text (AC3, AC7)', () => {
      expect(draftPillText(1, null)).toBe('Draft · 1 change not published');
      expect(draftPillText(2, '11:05 AM')).toBe('Draft · 2 changes not published · saved 11:05 AM');
      expect(updatedText(['layout', 'fields'])).toBe('Layout, fields updated.');
      expect(updatedText(['quickActions'])).toBe('Quick actions updated.');
      expect(updatedText([])).toBe('');
      expect(publishToast(2, ['layout', 'fields'])).toBe(
        'Patient card v2 is live for every role. Layout, fields updated.',
      );
      expect(publishToast(3, [])).toBe('Patient card v3 is live for every role.');
    });

    it('writes the Overview tile line (AC15)', () => {
      expect(tileLine({ layout: 'standard', isStandard: true, version: 1, hasDraft: false })).toBe(
        'Standard · Sahaj default · v1',
      );
      expect(tileLine({ layout: 'compact', isStandard: false, version: 4, hasDraft: true })).toBe(
        'Compact · customized · v4 · draft',
      );
    });
  });

  describe('layout, cards per row and templates', () => {
    it('changes only the layout or the cards per row, nothing is hidden by the choice (AC19, AC20)', () => {
      const s = standardSetup();
      const compact = setLayout(s, 'compact');
      expect(compact.layout).toBe('compact');
      expect(compact.sections).toEqual(s.sections);
      expect(setCardsPerRow(s, 2).cardsPerRow).toBe(2);
    });

    it('applies a template to layout, sections and actions and keeps the cards per row (AC18)', () => {
      const draft = setCardsPerRow(standardSetup(), 4);
      const [small, home, hospital] = templates();
      const s = applyTemplate(draft, small);
      expect(s.layout).toBe('compact');
      expect(s.cardsPerRow).toBe(4);
      expect(s.sections.find((x) => x.key === 'billing')!.on).toBe(false);
      expect(applyTemplate(draft, home).actions.find((a) => a.key === 'deposit')!.on).toBe(true);
      const h = applyTemplate(draft, hospital);
      expect(h.layout).toBe('expanded');
      expect(h.sections.find((x) => x.key === 'billing')!.on).toBe(true);
      expect(h.actions.every((a) => a.on)).toBe(true);
      expect(h.sections[0].fields.find((f) => f.key === 'ipNumber')!.on).toBe(true);
      expect(h.sections[2].fields.find((f) => f.key === 'admissionType')!.on).toBe(true);
      // The template object is not shared with the draft.
      h.sections[0].fields[0].label = 'x';
      expect(hospital.sections[0].fields[0].label).toBe('');
    });
  });

  describe('sections', () => {
    it('lists "<N> of <M> fields on" (AC21)', () => {
      const s = standardSetup();
      expect(fieldsOnText(s.sections[0])).toBe('4 of 6 fields on');
      expect(fieldsOnText(s.sections[6])).toBe('3 of 3 fields on');
    });

    it('switches Admission and bed, Care team and Billing; the others are refused (AC22, AC23)', () => {
      const s = standardSetup();
      const off = must(toggleSection(s, CATALOGUE, 'stay')).setup;
      expect(off.sections.find((x) => x.key === 'stay')!.on).toBe(false);
      expect(fieldsOnText(off.sections[2])).toBe('4 of 5 fields on');
      for (const key of ['header', 'status', 'vitals', 'clinical'] as const) {
        expect(toggleSection(s, CATALOGUE, key).ok).toBe(false);
      }
      expect(toggleSection(s, CATALOGUE, 'vitals')).toEqual({
        ok: false,
        message: 'The Vitals section is a safety section and stays on.',
      });
    });

    it('refuses to switch off a section that holds a safety field and names it (AC23)', () => {
      const billingOn = must(toggleSection(standardSetup(), CATALOGUE, 'billing')).setup;
      const moved = must(moveField(billingOn, CATALOGUE, 'allergies', 'billing', 0)).setup;
      expect(toggleSection(moved, CATALOGUE, 'billing')).toEqual({
        ok: false,
        message: 'This section holds a safety field (Allergies). Move it out first.',
      });
      const stay = must(moveField(standardSetup(), CATALOGUE, 'uhid', 'stay', 0)).setup;
      expect(toggleSection(stay, CATALOGUE, 'stay')).toEqual({
        ok: false,
        message: 'This section holds a safety field (UHID). Move it out first.',
      });
    });

    it('switches Billing on and off again', () => {
      const on = must(toggleSection(standardSetup(), CATALOGUE, 'billing')).setup;
      expect(on.sections[6].on).toBe(true);
      expect(must(toggleSection(on, CATALOGUE, 'billing')).setup.sections[6].on).toBe(false);
    });

    it('moves a section and never puts anything above the Card header (AC24)', () => {
      const s = standardSetup();
      const order = (x: CardSetup) => x.sections.map((y) => y.key);
      expect(order(moveSection(s, 'billing', 1))).toEqual([
        'header',
        'billing',
        'status',
        'stay',
        'care',
        'vitals',
        'clinical',
      ]);
      expect(order(moveSection(s, 'billing', 0))[0]).toBe('header');
      expect(order(moveSection(s, 'header', 3))).toEqual(order(s));
      expect(order(moveSectionBy(s, 'care', -1))).toEqual([
        'header',
        'status',
        'care',
        'stay',
        'vitals',
        'clinical',
        'billing',
      ]);
      expect(order(moveSectionBy(s, 'care', 1))).toEqual([
        'header',
        'status',
        'stay',
        'vitals',
        'care',
        'clinical',
        'billing',
      ]);
    });

    it('knows the ends of the list for the arrows', () => {
      const s = standardSetup();
      expect(canMoveSection(s, 'header', 1)).toBe(false);
      expect(canMoveSection(s, 'status', -1)).toBe(false);
      expect(canMoveSection(s, 'status', 1)).toBe(true);
      expect(canMoveSection(s, 'billing', 1)).toBe(false);
      expect(canMoveSection(s, 'billing', -1)).toBe(true);
    });
  });

  describe('fields', () => {
    it('switches a field on and off wherever it is (AC27)', () => {
      const s = must(toggleField(standardSetup(), CATALOGUE, 'ipNumber')).setup;
      expect(s.sections[0].fields.find((f) => f.key === 'ipNumber')!.on).toBe(true);
    });

    it('refuses to hide a safety field with the message of AC29, and the patient name', () => {
      for (const [key, name] of [
        ['uhid', 'UHID'],
        ['alerts', 'Alerts'],
        ['ews', 'Early warning'],
        ['allergies', 'Allergies'],
      ] as const) {
        expect(toggleField(standardSetup(), CATALOGUE, key)).toEqual({
          ok: false,
          message: `${name} is a safety field and cannot be hidden.`,
        });
      }
      expect(toggleField(standardSetup(), CATALOGUE, 'name')).toEqual({
        ok: false,
        message: 'Patient name stays first in the Card header.',
      });
    });

    it('keeps the label as typed up to 40 characters, and the standard name for the safety fields (AC31, AC32)', () => {
      const s = setFieldLabel(standardSetup(), CATALOGUE, 'doctor', 'Consultant');
      expect(s.sections[3].fields[0].label).toBe('Consultant');
      const long = setFieldLabel(standardSetup(), CATALOGUE, 'doctor', 'x'.repeat(60));
      expect(long.sections[3].fields[0].label).toHaveLength(40);
      expect(
        setFieldLabel(standardSetup(), CATALOGUE, 'allergies', 'Notes').sections[5].fields[1].label,
      ).toBe('');
      expect(labelLocked(CATALOGUE, 'name')).toBe(true);
      expect(labelLocked(CATALOGUE, 'uhid')).toBe(true);
      expect(labelLocked(CATALOGUE, 'doctor')).toBe(false);
    });

    it('moves a field inside a section and to another section (AC27)', () => {
      const s = standardSetup();
      const inside = must(moveField(s, CATALOGUE, 'room', 'stay', 0)).setup;
      expect(keysOf(inside, 'stay')).toEqual([
        'room',
        'admittedOn',
        'lengthOfStay',
        'ward',
        'admissionType',
      ]);
      const down = must(moveField(s, CATALOGUE, 'admittedOn', 'stay', 3)).setup;
      expect(keysOf(down, 'stay')).toEqual([
        'lengthOfStay',
        'ward',
        'admittedOn',
        'room',
        'admissionType',
      ]);
      const across = must(moveField(s, CATALOGUE, 'doctor', 'stay', 1)).setup;
      expect(keysOf(across, 'stay')).toEqual([
        'admittedOn',
        'doctor',
        'lengthOfStay',
        'ward',
        'room',
        'admissionType',
      ]);
      expect(keysOf(across, 'care')).toEqual([]);
    });

    it('places a field moved into the Card header after the patient name, never above it (AC30)', () => {
      const s = must(moveField(standardSetup(), CATALOGUE, 'doctor', 'header', 0)).setup;
      expect(keysOf(s, 'header').slice(0, 2)).toEqual(['name', 'doctor']);
    });

    it('does not move the patient name', () => {
      const s = standardSetup();
      expect(must(moveField(s, CATALOGUE, 'name', 'stay', 0)).setup).toEqual(s);
      expect(canMoveField(s, CATALOGUE, 'name', 1)).toBe(false);
    });

    it('allows a safety field only into a section that is switched on (AC30)', () => {
      // The standard card has Billing switched off.
      const off = standardSetup();
      expect(moveField(off, CATALOGUE, 'allergies', 'billing', 0)).toEqual({
        ok: false,
        message: 'Allergies is a safety field. Move it to a section that is switched on.',
      });
      const ok = must(moveField(off, CATALOGUE, 'allergies', 'stay', 0)).setup;
      expect(keysOf(ok, 'stay')[0]).toBe('allergies');
    });

    it('moves a field up or down by one and across a section border (story note of AC27)', () => {
      const s = standardSetup();
      expect(keysOf(must(moveFieldBy(s, CATALOGUE, 'ward', -1)).setup, 'stay')).toEqual([
        'admittedOn',
        'ward',
        'lengthOfStay',
        'room',
        'admissionType',
      ]);
      expect(keysOf(must(moveFieldBy(s, CATALOGUE, 'ward', 1)).setup, 'stay')).toEqual([
        'admittedOn',
        'lengthOfStay',
        'room',
        'ward',
        'admissionType',
      ]);
      // Up from the first field of a section enters the end of the previous one.
      const up = must(moveFieldBy(s, CATALOGUE, 'admittedOn', -1)).setup;
      expect(keysOf(up, 'status')).toEqual(['alerts', 'admittedOn']);
      // Down from the last field enters the start of the next section.
      const down = must(moveFieldBy(s, CATALOGUE, 'admissionType', 1)).setup;
      expect(keysOf(down, 'care')).toEqual(['admissionType', 'doctor']);
    });

    it('knows where a field cannot move any more', () => {
      const s = standardSetup();
      expect(canMoveField(s, CATALOGUE, 'uhid', -1)).toBe(false);
      expect(canMoveField(s, CATALOGUE, 'ageSex', -1)).toBe(true);
      expect(canMoveField(s, CATALOGUE, 'payer', 1)).toBe(false);
      expect(canMoveField(s, CATALOGUE, 'payer', -1)).toBe(true);
      expect(canMoveField(s, CATALOGUE, 'admittedOn', -1)).toBe(true);
    });

    it('refuses a safety field moved by arrow into a switched-off section', () => {
      // Allergies is the last field of Clinical; the next section, Billing, is switched off.
      expect(moveFieldBy(standardSetup(), CATALOGUE, 'allergies', 1).ok).toBe(false);
    });
  });

  describe('quick actions', () => {
    it('switches and moves actions (AC41)', () => {
      const s = standardSetup();
      expect(toggleAction(s, 'deposit').actions[4].on).toBe(true);
      expect(moveAction(s, 'deposit', 0).actions.map((a) => a.key)[0]).toBe('deposit');
      expect(moveActionBy(s, 'emr', 1).actions.map((a) => a.key)).toEqual([
        'nursing',
        'transfer',
        'emr',
        'discharge',
        'deposit',
      ]);
      expect(canMoveAction(s, 'nursing', -1)).toBe(false);
      expect(canMoveAction(s, 'deposit', 1)).toBe(false);
      expect(canMoveAction(s, 'emr', 1)).toBe(true);
    });
  });

  describe('resolve and role rule', () => {
    it('resolves a setup with the catalogue: only what is on, labels resolved, in order', () => {
      const s = setFieldLabel(standardSetup(), CATALOGUE, 'doctor', 'Consultant');
      const r = resolveSetup(s, CATALOGUE, { version: 2, isStandard: false });
      expect(r.sections.map((x) => x.key)).toEqual([
        'header',
        'status',
        'stay',
        'care',
        'vitals',
        'clinical',
      ]);
      expect(r.sections[0].fields.map((f) => f.key)).toEqual(['name', 'uhid', 'ageSex', 'bed']);
      expect(r.sections[3].fields[0]).toEqual({
        key: 'doctor',
        label: 'Consultant',
        group: 'patient',
        safety: false,
      });
      expect(r.sections[1].fields[0]).toMatchObject({
        key: 'alerts',
        label: 'Alerts',
        safety: true,
      });
      expect(r.actions).toEqual(['nursing', 'emr', 'transfer', 'discharge']);
      expect(r.version).toBe(2);
      expect(r.fallback).toBe(false);
    });

    it('reads who sees what from the catalogue the server sent (no table in the browser)', () => {
      expect(groupsFor('NURSE', CATALOGUE)).toEqual(['patient', 'clinical', 'vitals']);
      expect(groupsFor('FRONT_DESK', CATALOGUE)).toEqual(['patient', 'contact', 'billing']);
      expect(groupsFor('OWNER', CATALOGUE)).toHaveLength(5);
      const custom = {
        ...CATALOGUE,
        groups: CATALOGUE.groups.map((g) =>
          g.key === 'billing' ? { ...g, visibleTo: ['NURSE' as const] } : g,
        ),
      };
      expect(groupsFor('NURSE', custom)).toContain('billing');
      expect(groupsFor('OWNER', custom)).not.toContain('billing');
    });

    it('works out the abilities of a role and the IPD state from the "who" line (AC39)', () => {
      expect(abilitiesFor('NURSE', CATALOGUE, 'on')).toMatchObject({
        nursing: true,
        emr: true,
        transfer: true,
        discharge: false,
        deposit: false,
        depositLowFlag: false,
      });
      expect(abilitiesFor('DOCTOR', CATALOGUE, 'on')).toMatchObject({
        nursing: true,
        discharge: true,
        transfer: false,
      });
      expect(abilitiesFor('FRONT_DESK', CATALOGUE, 'trial')).toMatchObject({
        nursing: false,
        transfer: true,
        deposit: true,
        depositLowFlag: true,
      });
      expect(abilitiesFor('OWNER', CATALOGUE, 'on')).toMatchObject({
        nursing: true,
        emr: true,
        transfer: true,
        discharge: true,
        deposit: true,
      });
      expect(abilitiesFor('OWNER', CATALOGUE, 'readonly')).toMatchObject({
        nursing: true,
        emr: true,
        transfer: false,
        discharge: false,
        deposit: false,
      });
    });

    it('counts what is hidden for a role, only what the owner switched on (AC48)', () => {
      let s = standardSetup();
      s = withField(s, 'bp', true);
      s = withField(s, 'runningBill', true);
      s = toggleAction(s, 'deposit');
      const nurse = hiddenCounts(s, CATALOGUE, 'NURSE', 'on');
      // Billing is a switched-off section, so its fields do not count; Mobile is off.
      expect(nurse).toEqual({ fields: 0, actions: 2 });
      const billingOn = must(toggleSection(s, CATALOGUE, 'billing')).setup;
      expect(hiddenCounts(billingOn, CATALOGUE, 'NURSE', 'on')).toEqual({ fields: 3, actions: 2 });
      expect(hiddenCounts(billingOn, CATALOGUE, 'OWNER', 'on')).toEqual({ fields: 0, actions: 0 });
      // Front desk cannot see BP, Early warning (vitals) and Allergies (clinical).
      expect(hiddenCounts(billingOn, CATALOGUE, 'FRONT_DESK', 'on')).toEqual({
        fields: 3,
        actions: 3,
      });
    });

    it('words the hidden line with singular and plural (AC48)', () => {
      expect(hiddenText('NURSE', { fields: 3, actions: 1 })).toBe(
        'Hidden for Nurse: 3 fields, 1 action',
      );
      expect(hiddenText('FRONT_DESK', { fields: 1, actions: 0 })).toBe(
        'Hidden for Front desk: 1 field',
      );
      expect(hiddenText('OWNER', { fields: 0, actions: 0 })).toBeNull();
    });
  });
});
