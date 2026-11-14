import { TestBed } from '@angular/core/testing';
import { casePaperContext } from '../../emr.fixtures';
import { specialtyRecord } from '../specialty.fixtures';
import { mountEditor, byText, click, flushReference } from '../specialty.testing';
import { GrowthComponent, ImmunizationComponent, MilestonesComponent, WeightDoseComponent } from './paeds-editors';
import { EmrSpecialtyView, SpGrowth, SpImmunization } from '../specialty.model';
import { SpecialtyStripComponent } from '../specialty-strip.component';

const paeds = (over: Partial<NonNullable<EmrSpecialtyView['paeds']>> = {}): Partial<EmrSpecialtyView> => ({
  paeds: {
    ageMonths: 18,
    ageDays: 3,
    growthNotes: [
      { field: 'length', label: '~40th centile', alert: false },
      { field: 'weight', label: '~25–50th centile', alert: false },
      { field: 'headCirc', label: '~50th centile', alert: false },
    ],
    vaccines: [
      { vaccineKey: 'bcg', state: 'GIVEN', givenOn: '2025-05-10' },
      { vaccineKey: 'dtwp_booster_1', state: 'DUE_TODAY' },
      { vaccineKey: 'ipv_booster_1', state: 'DUE_TODAY' },
      { vaccineKey: 'varicella_1', state: 'OVERDUE' },
      { vaccineKey: 'hep_a_1', state: 'LATER' },
    ],
    dueTodayCount: 2,
    overdueCount: 1,
    nextDue: null,
    milestonesSetUp: true,
    doseWeightKg: null,
    doseWeightChangedRowIds: [],
    ...over,
  },
});

const anchors = [
  { key: 'growth', component: 'GROWTH' as const, required: true },
  { key: 'immunization', component: 'IMMUNIZATION' as const, part: 'TREATMENT' as const },
  { key: 'milestones', component: 'MILESTONES' as const },
  { key: 'wtdose', component: 'WEIGHT_DOSE' as const, part: 'TREATMENT' as const },
];

const growth = (weightKg: number | null): SpGrowth => ({ component: 'GROWTH', schemaVersion: 1, weightKg, lengthCm: 81, headCircCm: 46.5 });

describe('Growth (SAH-62-1)', () => {
  it('shows the weight note only; length and head circumference notes stay hidden (DEC-044, H8)', () => {
    const h = mountEditor(GrowthComponent, specialtyRecord('paeds', anchors, paeds()), 'growth', { growth: growth(10.2) });
    h.http.expectOne('/api/v1/opd/emr/records/r1/specialty-history').flush({ profile: 'paeds', someHidden: false, growthPoints: [] });
    h.store.setSpecialty('growth', growth(10.2));
    h.fixture.detectChanges();
    const notes = Array.from(h.el.querySelectorAll('[data-testid="stepper-note"]')).map((n) => n.textContent?.trim());
    expect(notes).toEqual(['~25–50th centile']);
    expect(h.el.textContent).toContain('WHO 0–5 y');
    expect(h.el.querySelector('svg[role="img"]')).not.toBeNull();
  });

  it('says "Centile not available for this age" when the child is not 18 months and the weight is unsaved', () => {
    const h = mountEditor(GrowthComponent, specialtyRecord('paeds', anchors, paeds({ ageMonths: 9 })), 'growth', { growth: growth(8) });
    h.http.expectOne('/api/v1/opd/emr/records/r1/specialty-history').flush({ profile: 'paeds', someHidden: false });
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="stepper-note"]')?.textContent?.trim()).toBe('Centile not available for this age');
  });

  it('does not plot a growth point with no worked-out age (no date of birth) and plots the others', () => {
    const h = mountEditor(GrowthComponent, specialtyRecord('paeds', anchors, paeds({ ageMonths: null })), 'growth', { growth: growth(null) });
    h.http.expectOne('/api/v1/opd/emr/records/r1/specialty-history').flush({
      profile: 'paeds',
      someHidden: false,
      growthPoints: [
        { date: '2026-01-10', ageMonths: null, weightKg: 9, lengthCm: null, headCircCm: null },
        { date: '2026-05-10', ageMonths: 6.5, weightKg: 7.4, lengthCm: null, headCircCm: null },
      ],
    });
    h.fixture.detectChanges();
    expect(h.el.querySelectorAll('svg circle').length).toBe(1);
  });

  it('steps the weight by 0.1 kg and stores it', () => {
    const h = mountEditor(GrowthComponent, specialtyRecord('paeds', anchors, paeds()), 'growth', { growth: growth(10.2) });
    h.http.expectOne('/api/v1/opd/emr/records/r1/specialty-history').flush({ profile: 'paeds', someHidden: false });
    click(h.el, 'button[aria-label="Increase Weight"]');
    expect(h.store.specialtyValue('growth')).toMatchObject({ weightKg: 10.3 });
  });
});

describe('Immunization (SAH-62-2)', () => {
  const view = () => specialtyRecord('paeds', anchors, paeds());
  const empty: SpImmunization = { component: 'IMMUNIZATION', schemaVersion: 1, givenToday: [], scheduleReviewed: false };

  it('marks a due vaccine given today, and a vaccine given before cannot be changed', () => {
    const h = mountEditor(ImmunizationComponent, view(), 'immunization', { immunization: empty });
    const bcg = byText(h.el, 'button.vac', 'BCG') as HTMLButtonElement;
    expect(bcg.disabled).toBe(true);
    const due = byText(h.el, 'button.vac', 'DTwP booster 1');
    due.click();
    h.fixture.detectChanges();
    expect(h.store.specialtyValue('immunization')).toMatchObject({ givenToday: [{ vaccineKey: 'dtwp_booster_1' }] });
    expect(byText(h.el, 'button.vac', 'DTwP booster 1').textContent).toContain('· today');
    byText(h.el, 'button.vac', 'DTwP booster 1').click();
    expect(h.store.specialtyValue('immunization')).toMatchObject({ givenToday: [] });
  });

  it('"Mark all due as given" records the vaccines due today and the overdue one, once, with the toast (SAH-75-9)', () => {
    const h = mountEditor(ImmunizationComponent, view(), 'immunization', { immunization: empty });
    byText(h.el, 'button', 'Mark all due as given').click();
    h.fixture.detectChanges();
    const given = (h.store.specialtyValue('immunization') as SpImmunization).givenToday.map((g) => g.vaccineKey);
    expect(given.length).toBe(3);
    expect(given).toContain('dtwp_booster_1');
    expect(given).toContain('ipv_booster_1');
    expect(h.el.querySelector('[data-testid="vax-toast"]')?.textContent).toBe('3 vaccines recorded');
    byText(h.el, 'button', 'Mark all due as given').click();
    h.fixture.detectChanges();
    expect((h.store.specialtyValue('immunization') as SpImmunization).givenToday.length).toBe(3);
    expect(h.el.querySelector('[data-testid="vax-toast"]')?.textContent).toBe('0 vaccines recorded');
  });

  it('names the vaccine in the tap toasts: recorded as given today, then unmarked', () => {
    const h = mountEditor(ImmunizationComponent, view(), 'immunization', { immunization: empty });
    byText(h.el, 'button.vac', 'DTwP booster 1').click();
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="vax-toast"]')?.textContent).toBe('DTwP booster 1 recorded as given today');
    byText(h.el, 'button.vac', 'DTwP booster 1').click();
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="vax-toast"]')?.textContent).toBe('DTwP booster 1 unmarked');
  });

  it('says how many are still to give', () => {
    const h = mountEditor(ImmunizationComponent, view(), 'immunization', { immunization: empty });
    expect(h.el.querySelector('[data-testid="vax-note"]')?.textContent).toContain('3 still to give');
    expect(h.el.textContent).toContain('Marathi and Hindi card wording: later.');
  });

  it('prints the vaccination card with every vaccine, its date or state, and sends nothing anywhere', () => {
    let printed = '';
    const spy = vi.spyOn(window, 'print').mockImplementation(() => {
      printed = document.querySelector('.print-root')?.textContent ?? '';
    });
    const h = mountEditor(ImmunizationComponent, view(), 'immunization', { immunization: empty });
    h.store.paperContext.set(casePaperContext());
    byText(h.el, 'button.vac', 'DTwP booster 1').click();
    click(h.el, 'button[data-act="print-card"]');
    h.fixture.detectChanges();
    expect(spy).toHaveBeenCalledTimes(1);
    expect(printed).toContain('Test Clinic');
    expect(printed).toContain('Vaccination card');
    expect(printed).toContain('BCG — given 10 May 2025');
    expect(printed).toContain('DTwP booster 1 — given today');
    expect(printed).toContain('IPV booster 1 — due today');
    expect(printed).toContain('Varicella 1 — overdue');
    expect(document.querySelector('.print-root')).toBeNull();
    expect(h.el.querySelector('[data-testid="card-message"]')?.textContent).toBe('Vaccination card sent to printer');
    spy.mockRestore();
  });

  it("prints the child's date of birth on the vaccination card only when the server sends it", () => {
    let printed = '';
    const spy = vi.spyOn(window, 'print').mockImplementation(() => {
      printed = document.querySelector('.print-root')?.textContent ?? '';
    });
    const h = mountEditor(ImmunizationComponent, specialtyRecord('paeds', anchors, paeds({ dateOfBirthRecorded: true, dateOfBirth: '2025-06-13' })), 'immunization', { immunization: empty });
    h.store.paperContext.set(casePaperContext());
    click(h.el, 'button[data-act="print-card"]');
    expect(printed).toContain('Date of birth 13 Jun 2025');
    expect(h.el.textContent).not.toContain('13 Jun 2025');
    spy.mockRestore();
  });

  it('prints no date line when the child has no date of birth', () => {
    let printed = '';
    const spy = vi.spyOn(window, 'print').mockImplementation(() => {
      printed = document.querySelector('.print-root')?.textContent ?? '';
    });
    const h = mountEditor(ImmunizationComponent, specialtyRecord('paeds', anchors, paeds({ dateOfBirthRecorded: false, dateOfBirth: null })), 'immunization', { immunization: empty });
    h.store.paperContext.set(casePaperContext());
    click(h.el, 'button[data-act="print-card"]');
    expect(printed).toContain('Vaccination card');
    expect(printed).not.toContain('Date of birth');
    spy.mockRestore();
  });

  it('shows the schedule from the reference lists and stores the vaccine_key (iapGroups group_key, iapVaccines vaccine_key with its group)', () => {
    const h = mountEditor(ImmunizationComponent, specialtyRecord('paeds', anchors, paeds({ vaccines: [{ vaccineKey: 'hep_b_1', state: 'DUE_TODAY' }] })), 'immunization', { immunization: empty });
    flushReference(h.http, 'paeds', {
      profile: 'paeds',
      tagNote: '',
      lists: {
        iapGroups: [{ key: 'week_6', label: '6 weeks', number: 2 }],
        iapVaccines: [{ key: 'hep_b_1', label: 'Hep B 1', group: 'week_6' }, { key: 'rota_1', label: 'Rotavirus 1', group: 'week_6' }],
      },
    });
    h.fixture.detectChanges();
    expect(Array.from(h.el.querySelectorAll('button.vac')).map((b) => b.textContent?.replace(/\s+/g, ' ').trim())).toEqual(['Hep B 1', 'Rotavirus 1']);
    byText(h.el, 'button.vac', 'Hep B 1').click();
    expect(h.store.specialtyValue('immunization')).toMatchObject({ givenToday: [{ vaccineKey: 'hep_b_1' }] });
  });

  it('records "Schedule reviewed"', () => {
    const h = mountEditor(ImmunizationComponent, view(), 'immunization', { immunization: empty });
    click(h.el, 'input[type="checkbox"]');
    expect(h.store.specialtyValue('immunization')).toMatchObject({ scheduleReviewed: true });
  });
});

describe('Paediatrics strip (SAH-73-4)', () => {
  const NOTE = "Date of birth not recorded. Add it in Patients to see due vaccines and the child's age.";

  it('shows the plain line when the date of birth is not recorded', () => {
    const h = mountEditor(SpecialtyStripComponent, specialtyRecord('paeds', anchors, paeds({ dateOfBirthRecorded: false, ageMonths: null, ageDays: null })), '');
    expect(h.el.querySelector('[data-testid="sp-note"]')?.textContent?.trim()).toBe(NOTE);
  });

  it('shows no line when the date of birth is recorded, or when an older answer does not say', () => {
    const recorded = mountEditor(SpecialtyStripComponent, specialtyRecord('paeds', anchors, paeds({ dateOfBirthRecorded: true })), '');
    expect(recorded.el.querySelector('[data-testid="sp-note"]')).toBeNull();
    TestBed.resetTestingModule();
    const silent = mountEditor(SpecialtyStripComponent, specialtyRecord('paeds', anchors, paeds()), '');
    expect(silent.el.querySelector('[data-testid="sp-note"]')).toBeNull();
  });
});

describe('Milestones (SAH-62-2)', () => {
  it('toggles a milestone achieved, then not yet, and notes what is not yet', () => {
    const h = mountEditor(MilestonesComponent, specialtyRecord('paeds', anchors, paeds()), 'milestones');
    click(h.el, 'button.mrow', 0);
    expect(h.store.specialtyValue('milestones')).toMatchObject({ achieved: ['walks_alone'], notYet: [] });
    click(h.el, 'button.mrow', 0);
    expect(h.store.specialtyValue('milestones')).toMatchObject({ achieved: [], notYet: ['walks_alone'] });
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="ms-note"]')?.textContent).toContain('Not yet: walks alone');
  });

  it('reads the milestones of the age band from the reference (milestone_key, domain from the group) and stores the key', () => {
    const h = mountEditor(MilestonesComponent, specialtyRecord('paeds', anchors, paeds()), 'milestones', { milestones: { component: 'MILESTONES', schemaVersion: 1, ageBandMonths: 18, achieved: [], notYet: [] } });
    flushReference(h.http, 'paeds', {
      profile: 'paeds',
      tagNote: '',
      lists: {
        milestones: [
          { key: 'walks_alone', label: 'Walks alone', group: 'GROSS_MOTOR', number: 18 },
          { key: 'says_6_or_more_words', label: 'Says 6 or more words', group: 'LANGUAGE', number: 18 },
          { key: 'sits_without_support', label: 'Sits without support', group: 'GROSS_MOTOR', number: 9 },
        ],
      },
    });
    h.fixture.detectChanges();
    expect(h.el.querySelectorAll('button.mrow')).toHaveLength(2);
    expect(h.el.querySelector('button.mrow')?.textContent).toContain('Gross motor');
    click(h.el, 'button.mrow', 1);
    expect(h.store.specialtyValue('milestones')).toMatchObject({ achieved: ['says_6_or_more_words'] });
  });

  it('says the age is not set up and stores nothing', () => {
    const h = mountEditor(MilestonesComponent, specialtyRecord('paeds', anchors, paeds({ milestonesSetUp: false })), 'milestones');
    expect(h.el.querySelector('[data-testid="ms-not-set-up"]')?.textContent).toBe('Milestones for this age are not set up');
    expect(h.el.querySelector('button.mrow')).toBeNull();
  });
});

describe('Dose by weight (SAH-62-3)', () => {
  it('asks for today’s weight first when none is recorded', () => {
    const h = mountEditor(WeightDoseComponent, specialtyRecord('paeds', anchors, paeds()), 'wtdose');
    expect(h.el.querySelector('[data-testid="dose-no-weight"]')?.textContent).toContain('Record today’s weight first');
    expect((byText(h.el, 'button', 'Add to prescription') as HTMLButtonElement).disabled).toBe(true);
  });

  it('works out the dose from the weight typed in Growth and adds it to the prescription with the calculation', () => {
    const h = mountEditor(WeightDoseComponent, specialtyRecord('paeds', anchors, paeds()), 'wtdose', { growth: growth(10.2) });
    expect(h.el.querySelector('[data-testid="dose-weight"]')?.textContent).toContain('10.2 kg');
    expect(h.el.querySelector('[data-testid="dose-mg"]')?.textContent).toBe('153 mg');
    expect(h.el.querySelector('[data-testid="dose-ml"]')?.textContent).toBe('6.4 ml');
    expect(h.el.querySelector('[data-testid="dose-give"]')?.textContent?.replace(/\s+/g, ' ').trim()).toContain('Give6.4 ml');
    byText(h.el, 'button', 'Add to prescription').click();
    const search = h.http.expectOne((r) => r.url === '/api/v1/opd/emr/medicines');
    expect(search.request.params.get('q')).toBe('Paracetamol syrup 120 mg / 5 ml');
    search.flush({ items: [], total: 0, page: 0, pageSize: 20 });
    const [row] = h.store.prescriptionRows();
    expect(row).toMatchObject({
      name: 'Paracetamol syrup 120 mg / 5 ml',
      source: 'DOSE_BY_WEIGHT',
      frequency: null,
      frequencyText: 'every 6 hours if needed, at most 4 doses a day',
      doseText: '6.4 ml (153 mg)',
      note: 'Calculated by weight',
      calculation: { doseRuleKey: 'PARACETAMOL_120_5', weightKg: 10.2, mg: 153, ml: 6.4 },
    });
  });

  it('uses the medicine from the EMR list when it is found, so the allergy check can apply', () => {
    const h = mountEditor(WeightDoseComponent, specialtyRecord('paeds', anchors, paeds()), 'wtdose', { growth: growth(10.2) });
    byText(h.el, 'button.tile', 'Ibuprofen').click();
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="dose-ml"]')?.textContent).toBe('5.1 ml');
    byText(h.el, 'button', 'Add to prescription').click();
    h.http
      .expectOne((r) => r.url === '/api/v1/opd/emr/medicines')
      .flush({ items: [{ medicineId: 'm-ibu', name: 'Ibuprofen syrup 100 mg / 5 ml', form: 'Syrup', favourite: false, allergyClass: null, defaultFrequency: '1-0-1', defaultDays: 3, defaultWhen: 'AFTER_FOOD' }], total: 1, page: 0, pageSize: 20 });
    expect(h.store.prescriptionRows()[0].medicineId).toBe('m-ibu');
  });

  it('lists a row whose weight changed and never changes it silently', () => {
    const h = mountEditor(WeightDoseComponent, specialtyRecord('paeds', anchors, paeds({ doseWeightKg: 10.2, doseWeightChangedRowIds: ['row1'] })), 'wtdose');
    expect(h.el.querySelector('[data-testid="dose-changed"]')?.textContent).toContain('Weight changed. Add the dose again.');
  });
});
