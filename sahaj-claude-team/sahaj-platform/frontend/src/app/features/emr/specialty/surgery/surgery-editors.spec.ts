import { specialtyRecord } from '../specialty.fixtures';
import { mountEditor, byText, click, typeInto, EMR_RECORDS } from '../specialty.testing';
import { SurgeryFitnessComponent, SurgeryPlanComponent, SurgicalExamComponent, PreauthComponent, PreopOrdersComponent } from './surgery-editors';
import { ConsentChecklistComponent } from '../shared/consent-checklist.component';
import { SpConsentChecklist, SpPreauth, SpSurgeryFitness, SpSurgeryPlan } from '../specialty.model';

const anchors = [
  { key: 'sx_findings', component: 'SURGICAL_EXAM' as const },
  { key: 'sx_fitness', component: 'SURGERY_FITNESS' as const },
  { key: 'sx_plan', component: 'SURGERY_PLAN' as const, part: 'TREATMENT' as const },
  { key: 'sx_consent', component: 'CONSENT_CHECKLIST' as const, part: 'TREATMENT' as const },
  { key: 'sx_preauth', component: 'PREAUTH' as const, part: 'TREATMENT' as const },
  { key: 'sx_preop', component: 'PREOP_ORDERS' as const, part: 'TREATMENT' as const },
];
const view = (surgery?: object) => specialtyRecord('surgery', anchors, surgery ? { surgery: surgery as never } : {});
const plan = (over: Partial<SpSurgeryPlan> = {}): SpSurgeryPlan => ({ component: 'SURGERY_PLAN', schemaVersion: 1, procedureKey: 'LAP_CHOLECYSTECTOMY', procedureText: 'Lap cholecystectomy', anaesthesia: 'GA', admission: 'IPD_1_2_DAYS', payer: 'SELF', ...over });
const consent = (ticked: number): SpConsentChecklist => ({
  component: 'CONSENT_CHECKLIST',
  schemaVersion: 1,
  items: ['PROCEDURE_RISKS', 'CONVERSION_TO_OPEN', 'ANAESTHESIA', 'BLOOD_TRANSFUSION', 'PHOTOS_VIDEO'].map((key, i) => ({ key, confirmed: i < ticked })),
});
const problem = (slug: string, status: number, detail: string) => ({ type: `https://sahaj.in/problems/${slug}`, title: slug, status, detail });

describe('Surgical examination (SAH-64-1)', () => {
  it('stores chips, and Soft, Normal and None clear the others', () => {
    const h = mountEditor(SurgicalExamComponent, view(), 'sx_findings');
    byText(h.el, 'button.chip', 'Tender right upper').click();
    byText(h.el, 'button.chip', 'Guarding').click();
    expect(h.store.specialtyValue('sx_findings')).toMatchObject({ abdomen: ['TENDER_RIGHT_UPPER', 'GUARDING'] });
    byText(h.el, 'button.chip', 'Soft').click();
    expect(h.store.specialtyValue('sx_findings')).toMatchObject({ abdomen: ['SOFT'] });
    byText(h.el, 'button.chip', 'Right inguinal').click();
    byText(h.el, 'button.chip', 'Positive').click();
    expect(h.store.specialtyValue('sx_findings')).toMatchObject({ herniaSites: ['RIGHT_INGUINAL'], murphySign: 'POSITIVE' });
    byText(h.el, 'button.chip', 'Positive').click();
    expect((h.store.specialtyValue('sx_findings') as { murphySign: unknown }).murphySign).toBeNull();
  });
});

describe('Fitness for surgery (SAH-64-2)', () => {
  const blank: SpSurgeryFitness = { component: 'SURGERY_FITNESS', schemaVersion: 1, otherIllness: [], pac: 'NOT_DONE', tests: [] };

  it('moves a test pending → normal → abnormal and words the summary', () => {
    const h = mountEditor(SurgeryFitnessComponent, view(), 'sx_fitness', { sx_fitness: blank });
    expect(h.el.querySelector('[data-testid="fit-text"]')?.textContent?.trim()).toBe('9 test(s) pending: CBC, LFT, KFT, PT / INR, Blood sugar, ECG, Chest X-ray, HIV, HBsAg, HCV, Blood group');
    byText(h.el, 'button.tile', 'CBC').click();
    byText(h.el, 'button.tile', 'CBC').click();
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="fit-text"]')?.textContent?.trim()).toBe('Not fit yet: CBC abnormal');
    expect(h.el.textContent).toContain('Tap a test to move it: pending → normal → abnormal.');
  });

  it('draws the pre-op work-up as its own card with the red star, the fitness pill and nine tiles', () => {
    const h = mountEditor(SurgeryFitnessComponent, view(), 'sx_fitness', { sx_fitness: blank });
    const card = h.el.querySelector('[data-testid="preop-card"]')!;
    expect(card.querySelector('h4')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('Pre-op work-up *');
    expect(card.querySelector('.pill')?.getAttribute('data-testid')).toBe('fit-text');
    expect(card.querySelectorAll('.tests3 button.tile')).toHaveLength(9);
  });

  it('moves HIV, HBsAg and HCV together as one row', () => {
    const h = mountEditor(SurgeryFitnessComponent, view(), 'sx_fitness', { sx_fitness: blank });
    byText(h.el, 'button.tile', 'HIV, HBsAg, HCV').click();
    expect((h.store.specialtyValue('sx_fitness') as SpSurgeryFitness).tests.map((t) => t.key)).toEqual(['HIV', 'HBSAG', 'HCV']);
  });

  it('warns for a blood thinner and PAC unfit but does not block', () => {
    const h = mountEditor(SurgeryFitnessComponent, view(), 'sx_fitness', { sx_fitness: blank });
    byText(h.el, 'button.chip', 'Aspirin').click();
    byText(h.el, 'button.chip', 'Unfit').click();
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="fit-warning"]')?.textContent).toContain('Blood thinner');
    expect(h.el.querySelector('[data-testid="fit-warning"]')?.textContent).toContain('PAC unfit.');
    expect(h.el.querySelector('[data-testid="fit-text"]')?.textContent?.trim()).toBe('Not fit yet: PAC unfit');
  });

  it('shows the server’s sentence while nothing is unsaved', () => {
    const h = mountEditor(SurgeryFitnessComponent, view({ fitness: { state: 'FIT', text: 'Fit for surgery' }, bookMissing: [], consentCount: { ticked: 5, total: 5 } }), 'sx_fitness');
    expect(h.el.querySelector('[data-testid="fit-text"]')?.textContent?.trim()).toBe('Fit for surgery');
  });
});

describe('Surgery plan and OT booking (SAH-64-3, SAH-71-1)', () => {
  const mount = (p: SpSurgeryPlan, extra = {}) => mountEditor(SurgeryPlanComponent, view(), 'sx_plan', { sx_plan: p, ...extra });

  it('takes the default anaesthesia and stay from the procedure and lets the doctor change them', () => {
    const h = mount(plan({ procedureKey: null, procedureText: null, anaesthesia: null, admission: null }));
    byText(h.el, 'button.chip', 'Excision of lipoma').click();
    expect(h.store.specialtyValue('sx_plan')).toMatchObject({ procedureKey: 'EXCISION_LIPOMA', anaesthesia: 'LOCAL', admission: 'DAY_CARE' });
    byText(h.el, 'button.chip', 'Spinal').click();
    expect(h.store.specialtyValue('sx_plan')).toMatchObject({ anaesthesia: 'SPINAL' });
  });

  it('shows "Not in tariff" with a typed package and works the deposit: Self half to ₹ 1,000, others ₹ 10,000', () => {
    const h = mount(plan());
    expect(h.el.querySelector('[data-testid="package"]')?.textContent).toContain('Not in tariff');
    typeInto(h.el.querySelector<HTMLInputElement>('#pkg-amt')!, '65000');
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="package"]')?.textContent).toContain('65,000');
    expect(h.el.querySelector('[data-testid="deposit"]')?.textContent).toContain('33,000');
    byText(h.el, 'button.chip', 'Insurance (TPA)').click();
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="deposit"]')?.textContent).toContain('10,000');
    expect(h.el.querySelector('[data-testid="payer-chip"]')?.textContent?.trim()).toBe('Insurance (TPA) · pre-auth pending');
  });

  it('keeps the booking closed and says what is missing before booking', () => {
    const h = mount(plan({ payer: 'INSURANCE_TPA' }), { sx_consent: consent(1) });
    expect((h.el.querySelector('button[data-act="book"]') as HTMLButtonElement).disabled).toBe(true);
    expect(h.el.querySelector('[data-testid="book-text"]')?.textContent).toBe('Before booking: diagnosis, 4 consent item(s), pre-authorisation.');
  });

  it('marks the pre-authorisation sent from the plan when the payer is not Self', () => {
    const h = mount(plan({ payer: 'INSURANCE_TPA' }), { sx_preauth: { component: 'PREAUTH', schemaVersion: 1, sent: false } as SpPreauth });
    click(h.el, 'button[data-act="toggle-preauth"]');
    expect(h.store.specialtyValue('sx_preauth')).toMatchObject({ sent: true });
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="preauth-line"]')?.textContent).toContain('✓ Pre-authorisation sent to Insurance (TPA)');
  });

  it('books: saves the visit, then posts the record revision with an Idempotency-Key and shows the server’s message', () => {
    const h = mount(plan(), { sx_consent: consent(5) });
    h.store.addListEntries('DIAGNOSIS', [{ itemKey: null, text: 'Gallstones', newToList: true }]);
    h.fixture.detectChanges();
    const book = h.el.querySelector('button[data-act="book"]') as HTMLButtonElement;
    expect(book.disabled).toBe(false);
    book.click();
    h.http.expectOne(`${EMR_RECORDS}/r1/draft`).flush({ ...view(), revision: 4 });
    const req = h.http.expectOne(`${EMR_RECORDS}/r1/surgery-booking`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ revision: 4 });
    expect(req.request.headers.get('Idempotency-Key')).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
    req.flush({
      record: view({ bookState: 'BOOKED', bookMissing: [], payerChip: 'Self-pay', depositPaise: 3300000, consentCount: { ticked: 5, total: 5 }, fitness: { state: 'FIT', text: 'Fit for surgery' } }),
      courseId: 'c1',
      sittingId: null,
      admissionRequested: false,
      message: 'Booked. This clinic has no IPD, so no ward bed was requested.',
    });
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="book-message"]')?.textContent).toBe('Booked. This clinic has no IPD, so no ward bed was requested.');
  });

  it('shows the server’s refusal when the booking is not ready', () => {
    const h = mount(plan(), { sx_consent: consent(5) });
    h.store.addListEntries('DIAGNOSIS', [{ itemKey: null, text: 'Gallstones', newToList: true }]);
    h.fixture.detectChanges();
    click(h.el, 'button[data-act="book"]');
    h.http.expectOne(`${EMR_RECORDS}/r1/draft`).flush({ ...view(), revision: 4 });
    h.http.expectOne(`${EMR_RECORDS}/r1/surgery-booking`).flush(problem('booking-not-ready', 409, 'Before booking: pre-authorisation.'), { status: 409, statusText: 'Conflict' });
    h.fixture.detectChanges();
    expect(h.el.textContent).toContain('Before booking: pre-authorisation.');
  });

  it('says "Changed after booking. Book again" from the server state', () => {
    const h = mountEditor(SurgeryPlanComponent, view({ bookState: 'CHANGED', bookMissing: [], payerChip: 'Self-pay', depositPaise: null, consentCount: { ticked: 5, total: 5 }, fitness: { state: 'FIT', text: 'Fit for surgery' } }), 'sx_plan');
    expect((h.el.querySelector('button[data-act="book"]') as HTMLButtonElement).textContent?.trim()).toBe('Changed after booking. Book again');
  });
});

describe('Consent, pre-authorisation and pre-op orders (SAH-71)', () => {
  it('shows the five fixed consent items, none ticked, and the count', () => {
    const h = mountEditor(ConsentChecklistComponent, view(), 'sx_consent');
    expect(h.el.querySelectorAll('input[type="checkbox"]')).toHaveLength(5);
    expect(h.el.querySelector('[data-testid="consent-count"]')?.textContent?.trim()).toBe('0 of 5 ticked');
    expect(h.el.textContent).toContain('Procedure, benefits and risks explained');
    expect(h.el.textContent).toContain('Photos / video for records');
    click(h.el, 'input[type="checkbox"]', 0);
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="consent-count"]')?.textContent?.trim()).toBe('1 of 5 ticked');
    expect(h.store.stateOf('sx_consent')?.done).toBe(false);
    for (let i = 1; i < 5; i++) {
      click(h.el, 'input[type="checkbox"]', i);
    }
    expect(h.store.stateOf('sx_consent')?.done).toBe(true);
  });

  it('records the pre-authorisation as sent and keeps it as a tick only', () => {
    const h = mountEditor(PreauthComponent, view(), 'sx_preauth');
    click(h.el, 'input[type="checkbox"]');
    expect(h.store.specialtyValue('sx_preauth')).toMatchObject({ sent: true });
    expect(h.el.textContent).toContain('Nothing is sent to an insurer or scheme from here.');
  });

  it('ticks pre-op instructions, adds a line and a blood chip, and says medicine orders are the ward’s', () => {
    const h = mountEditor(PreopOrdersComponent, view(), 'sx_preop');
    byText(h.el, 'label', 'Nothing by mouth from midnight').querySelector('input')!.click();
    typeInto(h.el.querySelector<HTMLInputElement>('input[aria-label="Another instruction"]')!, 'Shave the part', 'input');
    h.fixture.detectChanges();
    byText(h.el, 'button', '+ Add').click();
    byText(h.el, 'button.chip', 'Group and save').click();
    expect(h.store.specialtyValue('sx_preop')).toMatchObject({ ticked: ['NIL_BY_MOUTH'], extraLines: ['Shave the part'], blood: 'GROUP_AND_SAVE' });
    expect(h.el.textContent).toContain('Medicine orders are placed by the ward.');
  });

  it('shows the three Blood chips, stores only the contract key, picks one at a time and clears on a second tap', () => {
    const h = mountEditor(PreopOrdersComponent, view(), 'sx_preop');
    const chips = Array.from(h.el.querySelectorAll<HTMLButtonElement>('[aria-label="Blood"] button.chip')).map((b) => b.textContent?.trim());
    expect(chips).toEqual(['Not needed', 'Group and save', '1 unit reserved']);
    byText(h.el, 'button.chip', '1 unit reserved').click();
    expect((h.store.specialtyValue('sx_preop') as { blood: string | null }).blood).toBe('ONE_UNIT_RESERVED');
    byText(h.el, 'button.chip', 'Not needed').click();
    expect((h.store.specialtyValue('sx_preop') as { blood: string | null }).blood).toBe('NOT_NEEDED');
    byText(h.el, 'button.chip', 'Not needed').click();
    expect((h.store.specialtyValue('sx_preop') as { blood: string | null }).blood).toBeNull();
  });
});
