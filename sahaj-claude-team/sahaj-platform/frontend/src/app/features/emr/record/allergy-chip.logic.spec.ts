import { noAllergyText } from './allergy-chip.logic';

describe('noAllergyText', () => {
  it('Paediatrics reads "No known allergy" and Oncology "No known drug allergy" only for NONE_RECORDED', () => {
    expect(noAllergyText('paeds', 'NONE_RECORDED')).toBe('No known allergy');
    expect(noAllergyText('onco', 'NONE_RECORDED')).toBe('No known drug allergy');
  });

  it('draws nothing for a recorded allergy, no record of one, or an older server', () => {
    for (const status of ['RECORDED', 'NOT_RECORDED', undefined] as const) {
      expect(noAllergyText('paeds', status)).toBeNull();
      expect(noAllergyText('onco', status)).toBeNull();
    }
  });
});
