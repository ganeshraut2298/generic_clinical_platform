import { HttpTestingController } from '@angular/common/http/testing';
import { specialtyRecord } from '../specialty.fixtures';
import { mountEditor, byText, click, flushReference, typeInto, FP, EMR_RECORDS, Harness } from '../specialty.testing';
import { ChemoPlanComponent } from './chemo-plan.component';
import { ChemoCheckerOption, ChemoOrderView, ChemoProtocol } from '../specialty.model';

const anchors = [
  { key: 'onc_tox', component: 'ONC_TOX' as const },
  { key: 'onc_bsa', component: 'ONC_EXAM' as const },
  { key: 'onc_regimen', component: 'CHEMO_PLAN' as const, part: 'TREATMENT' as const },
];

const protocols: ChemoProtocol[] = [
  {
    key: 'AC',
    label: 'AC',
    subLine: 'Doxorubicin + cyclophosphamide · every 21 days',
    intervalDays: 21,
    emeticRisk: 'HIGH',
    suggestedOnChange: false,
    drugs: [
      { drugKey: 'DOXORUBICIN', name: 'Doxorubicin', dosePerM2: 60, infusionMinutes: 15, vialStepMg: 5, vesicant: true, anthracycline: true, note: null },
      { drugKey: 'CYCLOPHOSPHAMIDE', name: 'Cyclophosphamide', dosePerM2: 600, infusionMinutes: 30, vialStepMg: 20, vesicant: false, anthracycline: false, note: null },
    ],
    premeds: [{ key: 'ONDANSETRON', text: 'Ondansetron 8 mg IV', minutes: 15 }],
  },
  { key: 'TC', label: 'TC', subLine: 'Docetaxel + cyclophosphamide · every 21 days', intervalDays: 21, emeticRisk: 'MODERATE', suggestedOnChange: false, drugs: [], premeds: [] },
];

const checker: ChemoCheckerOption = { membershipId: 'm-nurse', name: 'Sr. Test', role: 'NURSE', hasPin: true };

function order(over: Partial<ChemoOrderView> = {}): ChemoOrderView {
  const checks = (['DECISION_RECORDED', 'FIT_TO_TREAT', 'LABS_72H', 'BSA_TODAY', 'CUMULATIVE_DOSE', 'REDUCTION_REASON', 'ALLERGIES_REVIEWED', 'CONSENT_REGIMEN', 'SCHEME_APPROVAL', 'LINE_VESICANT'] as const).map((key) => ({
    key,
    passed: true,
    detail: 'OK',
    action: null,
  }));
  return {
    id: 'o1',
    recordId: 'r1',
    status: 'DRAFT',
    revision: 2,
    decisionRecorded: true,
    decision: 'GIVE_AS_PLANNED',
    protocolKey: 'AC',
    levelPct: 100,
    reductionReason: null,
    bsa: { raw: 1.66, used: 1.66, capped: false, source: 'BSA from today’s weight' },
    weightChangePct: 1.2,
    recalculated: false,
    drugs: [
      { drugKey: 'DOXORUBICIN', name: 'Doxorubicin', dosePerM2: 60, calculatedMg: 99.6, roundedMg: 100, finalMg: 100, finalEdited: false, moreThan5Pct: false, vesicant: true, lifetime: { earlierMgM2: 180, thisCycleMgM2: 60, totalMgM2: 240, limitMgM2: 450, red: false }, infusionMinutes: 15 },
      { drugKey: 'CYCLOPHOSPHAMIDE', name: 'Cyclophosphamide', dosePerM2: 600, calculatedMg: 996, roundedMg: 1000, finalMg: 1000, finalEdited: false, moreThan5Pct: false, vesicant: false, lifetime: null, infusionMinutes: 30 },
    ],
    premeds: [{ key: 'ONDANSETRON', text: 'Ondansetron 8 mg IV', minutes: 15, given: true }],
    emeticRisk: 'HIGH',
    allergyConfirmed: true,
    consentNewSigned: false,
    scheme: 'APPROVED',
    priorOutsideDoxorubicinMgM2: 0,
    checks,
    passedCount: 10,
    totalCount: 10,
    checker: { membershipId: 'm-nurse', name: 'Sr. Test', role: 'NURSE', namedAt: '2026-11-12T05:00:00Z' },
    secondCheck: { state: 'WAITING' },
    stages: { decided: true, dosesChecked: true, secondCheck: false, pharmacy: 'NOT_SENT', chair: 'LATER' },
    send: { ready: false, note: 'Waiting for the second check', holdMode: false },
    fingerprint: FP,
    ...over,
  };
}

const TAKE_HOME_START = ['ONDANSETRON_8', 'DEXAMETHASONE_4', 'PANTOPRAZOLE_40', 'BENZYDAMINE_MOUTHWASH'];

/**
 * Opens the sheet: the screen reads the reference and the order, then the checker options. By default
 * the take-home lines are already saved on the record (so nothing on screen is unsaved); with
 * `freshCycle` the screen starts the cycle and ticks the usual lines itself.
 */
function open(
  o: ChemoOrderView,
  options: ChemoCheckerOption[] = [checker],
  decisionRecorded = true,
  freshCycle = false,
  extra: { onco?: object; reference?: object } = {},
): Harness<ChemoPlanComponent> {
  const view = specialtyRecord('onco', anchors, { onco: { orderStatus: 'NONE', ...extra.onco } as never });
  if (!freshCycle) {
    view.sections = view.sections.map((sec) =>
      sec.key === 'onc_regimen' ? { ...sec, values: { specialty: { component: 'CHEMO_PLAN', schemaVersion: 1, takeHome: TAKE_HOME_START, referrals: [] } } } : sec,
    );
  }
  const h = mountEditor(ChemoPlanComponent, view, 'onc_regimen');
  flushReference(h.http, 'onco', { profile: 'onco', tagNote: '', protocols, ...extra.reference });
  h.http.expectOne(`${EMR_RECORDS}/r1/chemo-order`).flush({ ...o, decisionRecorded });
  if (o.status === 'DRAFT' || o.status === 'NONE') {
    h.http.expectOne(`${EMR_RECORDS}/r1/chemo-order/checker-options`).flush(options);
  }
  h.fixture.detectChanges();
  return h;
}

const problem = (slug: string, status: number, detail: string) => ({ type: `https://sahaj.in/problems/${slug}`, title: slug, status, detail });

function flushTakeHomeLookups(http: HttpTestingController): void {
  for (const req of http.match((r) => r.url === '/api/v1/opd/emr/medicines')) {
    req.flush({ items: [], total: 0, page: 0, pageSize: 20 });
  }
}

describe('Chemotherapy order sheet (SAH-66, SAH-70)', () => {
  it('says "No decision yet" and goes to the decision when none is recorded', () => {
    const h = open(order({ status: 'NONE', decisionRecorded: false, decision: null, protocolKey: null }), [checker], false);
    expect(h.el.querySelector('[data-testid="no-decision"]')?.textContent).toContain('No decision yet.');
    expect(h.el.textContent).toContain('Finish the assessment and confirm today’s decision.');
    h.store.setPart('TREATMENT');
    click(h.el, 'button[data-act="go-decision"]');
    expect(h.store.part()).toBe('ASSESSMENT');
  });

  it('shows the protocols, the doses from the server, the lifetime dose and the stages', () => {
    const h = open(order());
    expect(h.el.querySelector('[data-testid="order-title"]')?.textContent).toContain('Cycle');
    expect(h.el.textContent).toContain('Doxorubicin + cyclophosphamide · every 21 days');
    expect(h.el.textContent).toContain('180 earlier + 60 this cycle = 240 of 450 mg/m²');
    expect(h.el.textContent).toContain('No lifetime limit');
    expect(h.el.querySelector('[data-testid="bsa-line"]')?.textContent).toContain('BSA 1.66 m²');
    expect(h.el.querySelector('[data-testid="check-badge"]')?.textContent?.trim()).toBe('10 of 10 passed');
    const stages = Array.from(h.el.querySelectorAll('.stages li')).map((li) => li.textContent?.replace(/\s+/g, ' ').trim());
    expect(stages).toHaveLength(6);
    expect(stages[0]).toContain('Decided');
    expect(stages[4]).toContain('Chair');
    expect(stages[4]).toContain('Later');
    expect(stages[5]).toContain('Given');
  });

  it('flags a final dose more than 5% from calculated and sends only the edited dose', () => {
    const h = open(order({ drugs: [{ ...order().drugs[0], finalMg: 120, finalEdited: true, moreThan5Pct: true }, order().drugs[1]] }));
    expect(h.el.textContent).toContain('More than 5% from calculated');
    typeInto(h.el.querySelector<HTMLInputElement>('input[aria-label="Final dose of Doxorubicin in mg"]')!, '110');
    const req = h.http.expectOne(`${EMR_RECORDS}/r1/chemo-order`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toMatchObject({ revision: 2, protocolKey: 'AC', levelPct: 100, finalDoses: [{ drugKey: 'DOXORUBICIN', finalMg: 110 }], allergyConfirmed: true, scheme: 'APPROVED' });
    req.flush(order({ revision: 3 }));
  });

  it('asks for a reason when the dose level is below 100', () => {
    const h = open(order());
    byText(h.el, 'button.chip', '90%').click();
    const req = h.http.expectOne(`${EMR_RECORDS}/r1/chemo-order`);
    expect(req.request.body).toMatchObject({ levelPct: 90 });
    req.flush(order({ levelPct: 90, reductionReason: null, revision: 3 }));
    h.fixture.detectChanges();
    expect(h.el.textContent).toContain('Reason for reduction (required):');
    byText(h.el, 'button.chip', 'Neuropathy').click();
    const next = h.http.expectOne(`${EMR_RECORDS}/r1/chemo-order`);
    expect(next.request.body).toMatchObject({ levelPct: 90, reductionReasonKey: 'NEUROPATHY' });
    next.flush(order({ levelPct: 90, reductionReason: 'Neuropathy', revision: 4 }));
  });

  describe('the independent second check (ADR-023 D6, H7)', () => {
    it('names a checker only while every safety check passes', () => {
      const failing = order({ passedCount: 9, checks: order().checks.map((c, i) => (i === 6 ? { ...c, passed: false, detail: 'Confirm with patient', action: 'CONFIRM' as const } : c)), checker: null, secondCheck: { state: 'NOT_NAMED' } });
      const h = open(failing);
      expect(h.el.textContent).toContain('Clear the red checks before the second check.');
      expect((byText(h.el, 'button.chip', 'Sr. Test') as HTMLButtonElement).disabled).toBe(true);
    });

    it('names the checker from the options and shows "Waiting for <name>"', () => {
      const h = open(order({ checker: null, secondCheck: { state: 'NOT_NAMED' } }));
      byText(h.el, 'button.chip', 'Sr. Test · Nurse').click();
      const req = h.http.expectOne(`${EMR_RECORDS}/r1/chemo-order/checker`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({ revision: 2, checkerMembershipId: 'm-nurse' });
      req.flush(order({ revision: 3 }));
      h.fixture.detectChanges();
      expect(h.el.querySelector('[data-testid="waiting"]')?.textContent).toContain('Waiting for Sr. Test to confirm the second check');
      expect(h.el.textContent).toContain('sign in on their own device');
    });

    it('confirms with the checker’s own PIN, sends the PIN once and clears the field', () => {
      const h = open(order());
      const pin = h.el.querySelector<HTMLInputElement>('input[type="password"]')!;
      expect(pin.autocomplete).toBe('off');
      typeInto(pin, '12a34', 'input');
      h.fixture.detectChanges();
      expect(pin.value).toBe('1234');
      click(h.el, 'button[data-act="confirm-pin"]');
      h.fixture.detectChanges();
      expect(pin.value).toBe('');
      const req = h.http.expectOne(`${EMR_RECORDS}/r1/chemo-order/second-check`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ orderRevision: 2, fingerprint: FP, checkerMembershipId: 'm-nurse', pin: '1234' });
      req.flush(order({ revision: 3, secondCheck: { state: 'CONFIRMED', confirmedByName: 'Sr. Test', confirmedRole: 'NURSE', via: 'PIN', confirmedAt: '2026-11-12T05:35:00Z' }, send: { ready: true, note: 'All checks passed. Ready to send.' } }));
      h.fixture.detectChanges();
      expect(h.el.querySelector('[data-testid="second-confirmed"]')?.textContent).toContain('Second check confirmed by Sr. Test, Nurse, 12 Nov 2026');
      expect(JSON.stringify(h.store.record())).not.toContain('1234');
    });

    it('shows the server’s words for a wrong PIN and keeps the field empty', () => {
      const h = open(order());
      typeInto(h.el.querySelector<HTMLInputElement>('input[type="password"]')!, '0000', 'input');
      h.fixture.detectChanges();
      click(h.el, 'button[data-act="confirm-pin"]');
      h.http.expectOne(`${EMR_RECORDS}/r1/chemo-order/second-check`).flush(problem('wrong-pin', 403, 'That PIN is not right.'), { status: 403, statusText: 'Forbidden' });
      h.fixture.detectChanges();
      expect(h.el.querySelector('[data-testid="order-error"]')?.textContent).toBe('That PIN is not right.');
      expect(h.el.querySelector<HTMLInputElement>('input[type="password"]')!.value).toBe('');
    });

    it('reads the sheet again when the order changed under the checker', () => {
      const h = open(order());
      typeInto(h.el.querySelector<HTMLInputElement>('input[type="password"]')!, '1234', 'input');
      h.fixture.detectChanges();
      click(h.el, 'button[data-act="confirm-pin"]');
      h.http.expectOne(`${EMR_RECORDS}/r1/chemo-order/second-check`).flush(problem('chemo-order-changed', 409, 'The order changed.'), { status: 409, statusText: 'Conflict' });
      h.http.expectOne(`${EMR_RECORDS}/r1/chemo-order`).flush(order({ revision: 3, fingerprint: 'b'.repeat(64) }));
      h.http.expectOne(`${EMR_RECORDS}/r1/chemo-order/checker-options`).flush([checker]);
    });

    it('says the confirmation was cleared when the order changed after the check', () => {
      const h = open(order({ secondCheck: { state: 'CLEARED' }, checker: null }));
      expect(h.el.querySelector('[data-testid="second-cleared"]')?.textContent).toBe('Order changed after the second check. A new confirmation is needed.');
    });

    it('offers no PIN when the checker has none set and points to Second checks', () => {
      const h = open(order(), [{ ...checker, hasPin: false }]);
      expect(h.el.querySelector('input[type="password"]')).toBeNull();
      expect(h.el.textContent).toContain('has no PIN set');
    });
  });

  describe('send and hold', () => {
    it('cannot send before the second check is confirmed', () => {
      const h = open(order());
      expect((h.el.querySelector('button[data-act="send-order"]') as HTMLButtonElement).disabled).toBe(true);
      expect(h.el.querySelector('[data-testid="send-note"]')?.textContent).toBe('Waiting for the second check');
    });

    it('sends with the revision and the fingerprint the checker confirmed, then shows "Sent to pharmacy"', () => {
      const confirmed = order({ secondCheck: { state: 'CONFIRMED', confirmedByName: 'Sr. Test', confirmedRole: 'NURSE', via: 'SIGN_IN', confirmedAt: '2026-11-12T05:35:00Z' }, send: { ready: true, note: 'All checks passed. Ready to send.' } });
      const h = open(confirmed);
      const send = h.el.querySelector('button[data-act="send-order"]') as HTMLButtonElement;
      expect(send.disabled).toBe(false);
      send.click();
      const req = h.http.expectOne(`${EMR_RECORDS}/r1/chemo-order/send`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ revision: 2, fingerprint: FP });
      req.flush(order({ status: 'SENT', revision: 3, secondCheck: confirmed.secondCheck, sentAt: '2026-11-12T05:40:00Z', stages: { decided: true, dosesChecked: true, secondCheck: true, pharmacy: 'SENT', chair: 'LATER' }, send: { ready: false, note: '' } }));
      h.http.expectOne(`${EMR_RECORDS}/r1`).flush(specialtyRecord('onco', anchors, {}, { revision: 6 }));
      h.fixture.detectChanges();
      expect(h.el.querySelector('[data-testid="sent"]')?.textContent).toContain('✓ Sent to pharmacy');
      expect(h.el.querySelector('[data-testid="send-note"]')?.textContent).toContain('Sent Doxorubicin 100 mg, Cyclophosphamide 1000 mg · pharmacy preparing');
      expect(h.store.orderStatusOverride()).toBeNull();
      expect(h.el.querySelector('button[data-act="send-order"]')).toBeNull();
      expect(h.el.querySelector('input[aria-label="Final dose of Doxorubicin in mg"]')?.hasAttribute('disabled')).toBe(true);
    });

    it('says what is wrong when the server refuses the send', () => {
      const h = open(order({ send: { ready: true, note: 'All checks passed. Ready to send.' } }));
      click(h.el, 'button[data-act="send-order"]');
      h.http.expectOne(`${EMR_RECORDS}/r1/chemo-order/send`).flush(problem('second-check-not-confirmed', 409, 'The second check is not confirmed yet.'), { status: 409, statusText: 'Conflict' });
      h.fixture.detectChanges();
      expect(h.el.querySelector('[data-testid="order-error"]')?.textContent).toBe('The second check is not confirmed yet.');
    });

    it('for a Delay decision shows the hold and records it with no second check', () => {
      const h = open(order({ decision: 'DELAY_1_WEEK', checker: null, secondCheck: { state: 'NOT_NAMED' } }));
      expect(h.el.querySelector('[data-testid="hold"]')?.textContent).toContain('Delay chemotherapy by 1 week');
      expect(h.el.querySelector('[data-testid="hold"]')?.textContent).toContain('Repeat CBC');
      expect(h.el.querySelector('input[type="password"]')).toBeNull();
      click(h.el, 'button[data-act="record-hold"]');
      const req = h.http.expectOne(`${EMR_RECORDS}/r1/chemo-order/hold`);
      expect(req.request.body).toEqual({ revision: 2 });
      req.flush(order({ decision: 'DELAY_1_WEEK', status: 'HELD', hold: { kind: 'DELAY', reason: 'Fit to treat today: Check labs', date: '2026-11-19' }, revision: 3 }));
      h.http.expectOne(`${EMR_RECORDS}/r1`).flush(specialtyRecord('onco', anchors));
      h.fixture.detectChanges();
      expect(h.el.querySelector('[data-testid="hold-done"]')?.textContent).toContain('✓ Recorded');
    });

    it('for a Stop decision says so and refers to palliative care', () => {
      const h = open(order({ decision: 'STOP' }));
      expect(h.el.textContent).toContain('Stop chemotherapy · move to supportive / palliative care');
      expect(h.el.textContent).toContain('Record and refer to palliative care');
    });
  });

  describe('take-home, next cycle and referrals (SAH-70)', () => {
    it('ticks the first, second, third and fifth take-home lines at the start of a cycle and adds them to the prescription', () => {
      const h = open(order(), [checker], true, true);
      expect(h.store.specialtyValue('onc_regimen')).toMatchObject({ takeHome: ['ONDANSETRON_8', 'DEXAMETHASONE_4', 'PANTOPRAZOLE_40', 'BENZYDAMINE_MOUTHWASH'] });
      flushTakeHomeLookups(h.http);
      expect(h.store.prescriptionRows().map((r) => r.name)).toEqual(['Ondansetron 8 mg', 'Dexamethasone 4 mg', 'Pantoprazole 40 mg', 'Benzydamine mouthwash']);
      expect(h.store.prescriptionRows().every((r) => r.source === 'SPECIALTY_LIST')).toBe(true);
      expect(h.store.prescriptionRows()[0]).toMatchObject({ frequency: '1-0-1', days: 3, note: 'Nausea' });
    });

    it('shows the six drawn take-home lines with Loperamide not ticked, and the eight referral chips with none ticked (contract 0.5.1)', () => {
      const h = open(order(), [checker], true, true);
      flushTakeHomeLookups(h.http);
      h.fixture.detectChanges();
      const lines = Array.from(h.el.querySelectorAll('ul.plain li')).map((li) => li.textContent?.replace(/\s+/g, ' ').trim());
      expect(lines).toHaveLength(6);
      expect(lines[5]).toBe('Loperamide 2 mgLoose motionsAfter each loose motion (max 8)');
      expect(h.el.querySelector('[data-testid="take-sub"]')?.textContent).toBe('Generic names first · printed in English');
      expect((byText(h.el, 'li', 'Loperamide').querySelector('input') as HTMLInputElement).checked).toBe(false);
      expect((byText(h.el, 'li', 'Pegfilgrastim').querySelector('input') as HTMLInputElement).checked).toBe(false);
      expect(h.el.textContent).not.toContain('suggested: fever / regimen');
      const chips = Array.from(h.el.querySelectorAll('div[aria-label="Referrals"] button.chip')).map((b) => b.textContent?.replace(/\s+/g, ' ').trim());
      expect(chips).toEqual([
        '+ Radiotherapy',
        '+ Surgical oncology',
        '+ Palliative care',
        '+ Dietitian',
        '+ Psycho-oncology counselling',
        '+ Fertility counselling',
        '+ Social worker (scheme papers)',
        '+ Cardiology (echo)',
      ]);
      byText(h.el, 'button.chip', 'Cardiology (echo)').click();
      expect(h.store.specialtyValue('onc_regimen')).toMatchObject({ referrals: ['CARDIOLOGY_ECHO'] });
    });

    it('ticks Pegfilgrastim at the start of a cycle with the note "suggested: fever / regimen" when the server suggests it', () => {
      const h = open(order(), [checker], true, true, { onco: { pegfilgrastimSuggested: true } });
      expect(h.store.specialtyValue('onc_regimen')).toMatchObject({
        takeHome: ['ONDANSETRON_8', 'DEXAMETHASONE_4', 'PANTOPRAZOLE_40', 'PEGFILGRASTIM_6', 'BENZYDAMINE_MOUTHWASH'],
      });
      flushTakeHomeLookups(h.http);
      h.fixture.detectChanges();
      expect(byText(h.el, 'li', 'Pegfilgrastim').textContent).toContain('Prevents low counts / fever · suggested: fever / regimen');
      expect(h.store.prescriptionRows().map((r) => r.name)).toContain('Pegfilgrastim 6 mg');
      // The doctor's untick wins and the note goes.
      (byText(h.el, 'li', 'Pegfilgrastim').querySelector('input') as HTMLInputElement).click();
      h.fixture.detectChanges();
      expect(byText(h.el, 'li', 'Pegfilgrastim').textContent).not.toContain('suggested');
      expect((h.store.specialtyValue('onc_regimen') as { takeHome: string[] }).takeHome).not.toContain('PEGFILGRASTIM_6');
    });

    it('builds the lines and chips from the reference lists: defaultOn, the drawn dose, the medicine and eight referrals', () => {
      const take = (key: string, label: string, note: string, defaults: object, defaultOn: boolean) => ({ key, label, note, defaultOn, defaults, medicine: null });
      const h = open(order(), [checker], true, true, {
        reference: {
          lists: {
            oncTakeHome: [
              take('ONDANSETRON_8', 'Ondansetron 8 mg', 'Nausea', { frequency: '1-0-1', days: 3 }, true),
              take('LOPERAMIDE_2', 'Loperamide 2 mg', 'Loose motions', { frequencyText: 'After each loose motion (max 8)', days: null }, false),
            ],
            oncReferrals: [
              { key: 'DIETITIAN', label: 'Dietitian', defaultOn: false },
              { key: 'SOCIAL_WORKER_SCHEME_PAPERS', label: 'Social worker (scheme papers)', defaultOn: false },
            ],
          },
        },
      });
      flushTakeHomeLookups(h.http);
      h.fixture.detectChanges();
      expect(h.store.specialtyValue('onc_regimen')).toMatchObject({ takeHome: ['ONDANSETRON_8'] });
      expect(byText(h.el, 'li', 'Ondansetron').textContent).toContain('Nausea');
      (byText(h.el, 'li', 'Loperamide').querySelector('input') as HTMLInputElement).click();
      flushTakeHomeLookups(h.http);
      expect(h.store.prescriptionRows().find((r) => r.name === 'Loperamide 2 mg')).toMatchObject({ frequency: null, frequencyText: 'After each loose motion (max 8)', days: 5, note: 'Loose motions', source: 'SPECIALTY_LIST' });
      expect(Array.from(h.el.querySelectorAll('div[aria-label="Referrals"] button.chip'))).toHaveLength(2);
    });

    it('adds Pegfilgrastim as a fixed-text line and takes it out again when the tick is removed', () => {
      const h = open(order());
      h.fixture.detectChanges();
      const peg = byText(h.el, 'li', 'Pegfilgrastim').querySelector('input') as HTMLInputElement;
      peg.click();
      flushTakeHomeLookups(h.http);
      expect(h.store.prescriptionRows().find((r) => r.name === 'Pegfilgrastim 6 mg')).toMatchObject({ frequency: null, frequencyText: 'Once, day 2' });
      h.fixture.detectChanges();
      (byText(h.el, 'li', 'Pegfilgrastim').querySelector('input') as HTMLInputElement).click();
      expect(h.store.prescriptionRows().some((r) => r.name === 'Pegfilgrastim 6 mg')).toBe(false);
    });

    it('records referrals, keeps the WhatsApp calendar as Later and writes the cycle note from recorded facts', () => {
      const h = open(order());
      h.fixture.detectChanges();
      byText(h.el, 'button.chip', 'Palliative care').click();
      expect(h.store.specialtyValue('onc_regimen')).toMatchObject({ referrals: ['PALLIATIVE_CARE'] });
      const wa = byText(h.el, 'button', 'Send chemo calendar on WhatsApp') as HTMLButtonElement;
      expect(wa.disabled).toBe(true);
      const calendar = h.el.querySelector('[data-testid="wa-calendar"]')!;
      expect(Array.from(calendar.querySelectorAll('button.chip')).map((b) => [b.textContent?.trim(), (b as HTMLButtonElement).disabled])).toEqual([
        ['English', true],
        ['मराठी', true],
        ['हिंदी', true],
      ]);
      expect(calendar.textContent).toContain('Dates, fever warning (call if ≥ 38 °C), blood test reminders and the daily symptom check.');
      click(h.el, 'button[data-act="write-note"]');
      expect((h.store.specialtyValue('onc_regimen') as { cycleNote: string }).cycleNote).toContain('Order: AC at 100%: Doxorubicin 100 mg, Cyclophosphamide 1000 mg.');
    });
  });
});
