import { EmrMedicineOption, EmrPrescriptionRow } from '../emr-phase3.model';
import {
  ALERT_BLOCK_TEXT,
  ALLERGY_LINE,
  DAYS_MAX,
  FREQUENCIES,
  WHEN_CHOICES,
  alertText,
  emptyRx,
  frequencyLabel,
  hasMedicine,
  isAllergyClassKey,
  rowFromOption,
  rowReviewed,
  rowsOf,
  stepDays,
  typedRow,
  unresolved,
  warningMessage,
  whenLabel,
  withRows,
} from './prescription.logic';

const option: EmrMedicineOption = {
  medicineId: 'm1',
  name: 'Telmisartan 40 mg',
  form: 'Tablet',
  favourite: true,
  allergyClass: null,
  defaultFrequency: '1-0-0',
  defaultDays: 30,
  defaultWhen: 'AFTER_FOOD',
};

describe('prescription rules (SAH-48)', () => {
  it('offers exactly the four frequencies with OD, BD, HS and TDS', () => {
    expect(FREQUENCIES.map((f) => f.label)).toEqual(['1-0-0 OD', '1-0-1 BD', '0-0-1 HS', '1-1-1 TDS']);
    expect(frequencyLabel('0-0-1')).toBe('0-0-1 HS');
  });

  it('offers the four when-to-take choices', () => {
    expect(WHEN_CHOICES.map((w) => w.label)).toEqual(['After food', 'Before food', 'Bedtime', 'Morning, empty stomach']);
    expect(whenLabel('MORNING_EMPTY_STOMACH')).toBe('Morning, empty stomach');
  });

  describe('days', () => {
    it('go up and down by 1 up to 7 and by 7 above that', () => {
      expect(stepDays(1, 1)).toBe(2);
      expect(stepDays(6, 1)).toBe(7);
      expect(stepDays(7, 1)).toBe(14);
      expect(stepDays(14, 1)).toBe(21);
      expect(stepDays(14, -1)).toBe(7);
      expect(stepDays(7, -1)).toBe(6);
      expect(stepDays(2, -1)).toBe(1);
    });

    it('never go below 1 or above 365', () => {
      expect(stepDays(1, -1)).toBe(1);
      expect(stepDays(DAYS_MAX, 1)).toBe(DAYS_MAX);
      expect(stepDays(363, 1)).toBe(DAYS_MAX);
    });
  });

  describe('rows', () => {
    it('adds a row with the saved frequency, days and when of the medicine', () => {
      const row = rowFromOption(option, 'FAVOURITE');
      expect(row).toMatchObject({ medicineId: 'm1', name: 'Telmisartan 40 mg', form: 'Tablet', frequency: '1-0-0', days: 30, when: 'AFTER_FOOD', source: 'FAVOURITE' });
      expect(row.id).toMatch(/^[0-9a-f-]{36}$/);
    });

    it('gives a typed medicine the defaults 1-0-1, 5 days and after food, with no medicine id', () => {
      expect(typedRow('  Zinc  ')).toMatchObject({ medicineId: null, name: 'Zinc', frequency: '1-0-1', days: 5, when: 'AFTER_FOOD', source: 'TYPED' });
    });

    it('recognises the same generic name and strength, ignoring case', () => {
      const rows = [rowFromOption(option, 'SEARCH')];
      expect(hasMedicine(rows, 'telmisartan 40 MG')).toBe(true);
      expect(hasMedicine(rows, 'Telmisartan 80 mg')).toBe(false);
    });

    it('reads rows from any value and replaces them', () => {
      expect(rowsOf(undefined)).toEqual([]);
      const v = withRows(null, [rowFromOption(option, 'SEARCH')]);
      expect(v).toMatchObject({ component: 'PRESCRIPTION', schemaVersion: 1 });
      expect(rowsOf(v)).toHaveLength(1);
      expect(emptyRx().rows).toEqual([]);
    });
  });

  describe('allergy check (C-2, tagged)', () => {
    it('words the sulfa alert and the penicillin alert as the mockup does', () => {
      expect(alertText('Co-trimoxazole 960 mg', 'SULFA')).toBe('Co-trimoxazole 960 mg contains a sulfa drug. This patient is allergic to sulfa drugs.');
      expect(alertText('Amoxicillin 500 mg', 'PENICILLIN')).toBe('Amoxicillin 500 mg is a penicillin. This patient is allergic to penicillin.');
    });

    it('words the warning shown when a flagged drug is added', () => {
      expect(warningMessage('Co-trimoxazole 960 mg', 'SULFA')).toBe('Warning: Co-trimoxazole 960 mg clashes with sulfa allergy');
      expect(warningMessage('Amoxicillin 500 mg', 'PENICILLIN')).toBe('Warning: Amoxicillin 500 mg clashes with penicillin allergy');
    });

    it('has the footer text and the safety line', () => {
      expect(ALERT_BLOCK_TEXT).toBe('Remove the drug or confirm the allergy alert before completing.');
      expect(ALLERGY_LINE).toBe('Allergy check covers listed allergy classes only. No alert is not a clearance.');
    });

    it('accepts only the two classes', () => {
      expect(isAllergyClassKey('SULFA')).toBe(true);
      expect(isAllergyClassKey('PENICILLIN')).toBe(true);
      expect(isAllergyClassKey('LATEX')).toBe(false);
      expect(isAllergyClassKey(null)).toBe(false);
    });

    it('counts a review only for the same class', () => {
      const row: EmrPrescriptionRow = { ...rowFromOption(option, 'SEARCH'), allergyReview: { allergyClass: 'SULFA', confirmed: true } };
      expect(rowReviewed(row, 'SULFA')).toBe(true);
      expect(rowReviewed(row, 'PENICILLIN')).toBe(false);
      expect(rowReviewed({ ...row, allergyReview: { allergyClass: 'SULFA', confirmed: false } }, 'SULFA')).toBe(false);
      expect(rowReviewed({ ...row, allergyReview: null }, 'SULFA')).toBe(false);
    });

    it('counts the alerts not yet reviewed', () => {
      expect(unresolved([{ reviewed: true }, { reviewed: false }, { reviewed: false }])).toBe(2);
    });
  });
});
