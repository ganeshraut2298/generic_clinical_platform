import { CATALOGUE, standardSetup } from './card-setup.fixtures';
import { abilitiesFor, resolveSetup } from './card-setup.logic';
import { SAFETY_FIELDS, STANDARD_RESOLVED } from './card-setup.model';

/**
 * The browser keeps two small copies of server content (SAH-37 code review #10): the locked safety
 * items (used for the label guard and the messages) and the standard card (drawn only when the list
 * answer has no usable `cardSetup`). These specs compare both copies with the catalogue fixture, which
 * is shaped like the server's answer, so a drift fails here.
 */
describe('card-setup.model: the browser copies agree with the catalogue', () => {
  describe('SAFETY_FIELDS', () => {
    it('lists exactly the fields the catalogue marks as safety fields', () => {
      const fromCatalogue = CATALOGUE.fields.filter((f) => f.safety).map((f) => f.key);
      expect(SAFETY_FIELDS.map((f) => f.key).sort()).toEqual([...fromCatalogue].sort());
    });

    it('gives each one the catalogue name and data group', () => {
      for (const item of SAFETY_FIELDS) {
        const cat = CATALOGUE.fields.find((f) => f.key === item.key);
        expect(cat).toBeDefined();
        expect(item.name).toBe(cat!.name);
        expect(item.group).toBe(cat!.group);
      }
    });

    it('names the section where the standard setup puts it, with the catalogue name of that section', () => {
      const setup = standardSetup();
      for (const item of SAFETY_FIELDS) {
        const holder = setup.sections.find((s) => s.fields.some((f) => f.key === item.key));
        expect(holder?.key).toBe(item.section);
        expect(item.sectionName).toBe(CATALOGUE.sections.find((s) => s.key === item.section)?.name);
      }
    });

    it('keeps each safety field in the standard setup switched on', () => {
      const setup = standardSetup();
      for (const item of SAFETY_FIELDS) {
        const placed = setup.sections.flatMap((s) => s.fields).find((f) => f.key === item.key);
        expect(placed?.on).toBe(true);
      }
    });
  });

  describe('STANDARD_RESOLVED', () => {
    it('is the standard setup resolved with the catalogue, marked as the fallback', () => {
      const resolved = resolveSetup(standardSetup(), CATALOGUE, { version: 1, isStandard: true });
      expect(STANDARD_RESOLVED).toEqual({ ...resolved, fallback: true });
    });

    it('marks as safety exactly the fields the catalogue marks', () => {
      const safetyKeys = CATALOGUE.fields.filter((f) => f.safety).map((f) => f.key);
      const drawn = STANDARD_RESOLVED.sections.flatMap((s) => s.fields);
      expect(drawn.filter((f) => f.safety).map((f) => f.key).sort()).toEqual([...safetyKeys].sort());
    });

    it('names each section as the catalogue does', () => {
      for (const section of STANDARD_RESOLVED.sections) {
        expect(section.name).toBe(CATALOGUE.sections.find((s) => s.key === section.key)?.name);
      }
    });
  });

  describe('the deposit-low flag the browser derives', () => {
    it('is on for the roles that see the Billing group, and for no one else', () => {
      const billing = CATALOGUE.groups.find((g) => g.key === 'billing')!;
      expect([...billing.visibleTo].sort()).toEqual(['FRONT_DESK', 'OWNER']);
      for (const role of CATALOGUE.viewAsRoles) {
        expect(abilitiesFor(role, CATALOGUE, 'on').depositLowFlag).toBe(
          billing.visibleTo.includes(role),
        );
      }
    });
  });
});
