import { SpDentalMedHistory } from '../specialty.model';
import { ALLERGIES, CONDITIONS, MEDICINES, blankMedHistory, dentalRecap, headerTags, medicalAlerts, toggleGroup } from './dental-medhistory.logic';

const med = (over: Partial<SpDentalMedHistory>): SpDentalMedHistory => ({ ...blankMedHistory(), ...over });

describe('Dental "Medical history and alerts" (SAH-75-6; tagged)', () => {
  it('offers the groups and chips of the design in order', () => {
    expect(CONDITIONS.map((c) => c.label)).toEqual(['Diabetes', 'High BP', 'Heart disease', 'Thyroid', 'Asthma', 'Bleeding disorder', 'Epilepsy', 'Pregnant', 'None']);
    expect(MEDICINES.map((c) => c.label)).toEqual(['Blood thinners', 'Aspirin', 'Bisphosphonates', 'Steroids', 'Thyroid medicine', 'None']);
    expect(ALLERGIES.map((c) => c.label)).toEqual(['Penicillin', 'Local anaesthetic', 'Latex', 'Painkillers (NSAIDs)', 'None']);
  });

  it('"None" clears the others of its group and another chip clears "None"', () => {
    expect(toggleGroup(CONDITIONS, ['DIABETES', 'HIGH_BP'], 'NONE')).toEqual(['NONE']);
    expect(toggleGroup(CONDITIONS, ['NONE'], 'ASTHMA')).toEqual(['ASTHMA']);
  });

  it('raises each banner only when its condition holds, in the drawn order and words', () => {
    const alerts = medicalAlerts(med({ allergies: ['PENICILLIN'], medicines: ['ASPIRIN', 'BISPHOSPHONATES'], conditions: ['DIABETES', 'HEART_DISEASE', 'PREGNANT'] }), ['gutkha']);
    expect(alerts.map((a) => a.key)).toEqual(['PENICILLIN_ALLERGY', 'BLEEDING_RISK', 'DIABETES', 'BP_HEART', 'PREGNANT', 'BISPHOSPHONATES', 'TOBACCO_USE']);
    expect(alerts[0]).toEqual({ key: 'PENICILLIN_ALLERGY', title: 'Penicillin allergy.', text: 'Avoid amoxicillin; use a non-penicillin antibiotic.' });
    expect(alerts[1].text).toBe('Check INR or stop advice with the physician before extraction or surgery.');
    expect(alerts[6]).toMatchObject({ title: 'Tobacco use.', text: 'Screen the mouth for pre-cancer patches and counsel to quit.' });
  });

  it('raises no banner for none, and a bleeding disorder alone raises the bleeding risk', () => {
    expect(medicalAlerts(med({ conditions: ['NONE'], medicines: ['NONE'], allergies: ['NONE'] }), [])).toEqual([]);
    expect(medicalAlerts(med({ conditions: ['BLEEDING_DISORDER'] }), []).map((a) => a.key)).toEqual(['BLEEDING_RISK']);
    expect(medicalAlerts(null, ['alcohol'])).toEqual([]);
  });

  it('builds the header tags from the chips', () => {
    expect(headerTags(med({ allergies: ['PENICILLIN'], medicines: ['THYROID_MEDICINE'] }))).toEqual({ allergy: 'Allergy: Penicillin', medical: 'Medical: on thyroid medicine' });
    expect(headerTags(med({ allergies: ['NONE'] }))).toEqual({ allergy: null, medical: null });
  });

  it('writes the Assessment recap with pain and the alert names', () => {
    const alerts = medicalAlerts(med({ allergies: ['PENICILLIN'], conditions: ['DIABETES'] }), []);
    expect(dentalRecap(['Toothache (3 days)'], '7', alerts)).toBe('Toothache (3 days) · pain 7/10 · 2 medical alert(s): Penicillin allergy, Diabetes');
    expect(dentalRecap([], null, [])).toBe('no complaints · pain –/10');
  });
});
