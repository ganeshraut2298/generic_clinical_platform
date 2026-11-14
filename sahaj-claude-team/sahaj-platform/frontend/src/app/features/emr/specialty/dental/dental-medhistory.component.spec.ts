import { specialtyRecord } from '../specialty.fixtures';
import { isSpecialtyDone, sanitizeSpecialty } from '../specialty.logic';
import { byText, click, mountEditor } from '../specialty.testing';
import { SpDentalMedHistory } from '../specialty.model';
import { DentalMedHistoryComponent } from './dental-medhistory.component';
import { blankMedHistory } from './dental-medhistory.logic';

const anchors = [{ key: 'dental_medhx', component: 'DENTAL_MED_HISTORY' as const, required: true, part: 'ASSESSMENT' as const }];
const view = (alerts: { key: string; title: string; text: string }[] = []) =>
  specialtyRecord('dental', anchors, { dental: { estimateTotalPaise: 0, estimateState: 'NOT_SIGNED', doneTodayRowIds: [], childTabDefault: false, medicalAlerts: alerts as never } });
const stored = (h: ReturnType<typeof mountEditor>) => h.store.specialtyValue('dental_medhx') as SpDentalMedHistory;

describe('Dental "Medical history and alerts" editor (SAH-75-6)', () => {
  it('shows the three groups, "Mark as reviewed" and a red border until it is reviewed', () => {
    const h = mountEditor(DentalMedHistoryComponent, view(), 'dental_medhx', { dental_medhx: blankMedHistory() });
    expect(Array.from(h.el.querySelectorAll('.sp-lbl')).map((e) => e.textContent!.trim())).toEqual(['Conditions', 'Medicines', 'Allergies']);
    expect(h.el.querySelector('[data-act="review"]')!.textContent!.trim()).toBe('Mark as reviewed');
    expect(h.el.querySelector('[data-testid="med-card"]')!.classList.contains('unreviewed')).toBe(true);
  });

  it('puts "✓ " before a chosen chip, shows it red, and clears "None" when another chip is chosen', () => {
    const h = mountEditor(DentalMedHistoryComponent, view(), 'dental_medhx', { dental_medhx: blankMedHistory() });
    byText(h.el, 'button.chip', 'None').click();
    h.fixture.detectChanges();
    expect(stored(h).conditions).toEqual(['NONE']);
    byText(h.el, 'button.chip', 'Diabetes').click();
    h.fixture.detectChanges();
    expect(stored(h).conditions).toEqual(['DIABETES']);
    const chip = byText(h.el, 'button.chip', 'Diabetes');
    expect(chip.textContent!.trim()).toBe('✓ Diabetes');
    expect(chip.classList.contains('bad')).toBe(true);
  });

  it('"Mark as reviewed" reads "✓ Reviewed with patient", turns the border normal, and pressing again takes it back', () => {
    const h = mountEditor(DentalMedHistoryComponent, view(), 'dental_medhx', { dental_medhx: blankMedHistory() });
    click(h.el, '[data-act="review"]');
    h.fixture.detectChanges();
    expect(stored(h).reviewedWithPatient).toBe(true);
    expect(h.el.querySelector('[data-act="review"]')!.textContent!.trim()).toBe('✓ Reviewed with patient');
    expect(h.el.querySelector('[data-testid="med-card"]')!.classList.contains('unreviewed')).toBe(false);
    click(h.el, '[data-act="review"]');
    expect(stored(h).reviewedWithPatient).toBe(false);
  });

  it('shows the banners of the chips on screen, with their bold title and text', () => {
    const h = mountEditor(DentalMedHistoryComponent, view(), 'dental_medhx', { dental_medhx: { ...blankMedHistory(), allergies: ['PENICILLIN'], medicines: ['ASPIRIN'] } });
    const alerts = Array.from(h.el.querySelectorAll('[data-testid="med-alert"]'));
    expect(alerts.map((a) => a.querySelector('b')!.textContent)).toEqual(['Penicillin allergy.', 'Bleeding risk.']);
    expect(alerts[0].textContent).toContain('Avoid amoxicillin; use a non-penicillin antibiotic.');
  });

  it('sends the server\'s banners while nothing is unsaved', () => {
    const h = mountEditor(DentalMedHistoryComponent, view([{ key: 'DIABETES', title: 'Diabetes.', text: 'Check sugar before surgery; healing is slower.' }]), 'dental_medhx');
    expect(h.el.querySelectorAll('[data-testid="med-alert"]')).toHaveLength(1);
  });

  it('adds the Penicillin chip to the allergy classes of the visit, so amoxicillin is blocked (SAH-48 check reused)', () => {
    const h = mountEditor(DentalMedHistoryComponent, view(), 'dental_medhx', { dental_medhx: { ...blankMedHistory(), allergies: ['PENICILLIN'] } });
    h.store.setValue('rx', 'prescription', {
      component: 'PRESCRIPTION',
      schemaVersion: 1,
      rows: [{ id: 'row1', medicineId: 'm-amox', name: 'Amoxicillin', form: null, frequency: 'TDS', days: 5, when: 'AFTER_FOOD', source: 'TYPED', doseText: null, frequencyText: null, note: null, calculation: null }],
    });
    h.store.noteMedicineClass('m-amox', 'PENICILLIN');
    expect(h.store.allergyAlerts().map((a) => a.medicineName)).toEqual(['Amoxicillin']);
    expect(h.store.alertBlocked()).toBe(true);
    expect(h.store.footer()).toBe('Remove Amoxicillin before completing (penicillin allergy).');
  });

  it('does not send the review stamp back (read only), and is Done only when reviewed', () => {
    const value: SpDentalMedHistory = { ...blankMedHistory(), review: { at: 't', byMembershipId: 'm', byName: 'Dr X' } };
    expect(sanitizeSpecialty(value)).not.toHaveProperty('review');
    expect(isSpecialtyDone('DENTAL_MED_HISTORY', value, {} as never, 'dental_medhx')).toBe(false);
    expect(isSpecialtyDone('DENTAL_MED_HISTORY', { ...value, reviewedWithPatient: true }, {} as never, 'dental_medhx')).toBe(true);
  });
});
