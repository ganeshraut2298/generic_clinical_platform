import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { OWNER_CONTEXT, PHARMACIST_CONTEXT } from '../pharmacy.fixtures';
import { approvalRules } from '../pharmacy-c.fixtures';
import { ApprovalRules, PharmacyContext } from '../pharmacy.model';
import { ApprovalRulesSection } from './approval-rules.section';
import { PRESET_DIALOG_CONFIG } from './preset.dialog';

const BASE = '/api/v1/pharmacy';
const RULES = `${BASE}/approval-rules`;

interface Internals {
  setLevels(index: number, count: number): void;
  patchLevel(index: number, slot: number, patch: Record<string, unknown>): void;
  patchRule(index: number, patch: Record<string, unknown>): void;
  setHighValue(text: string): void;
  save(): void;
  presetShown(): string;
  conditionsFor(type: string, current: string): string[];
  conditionsFor(type: string, current: string): string[];
  choosePreset(key: string, select: { value: unknown }): void;
}

describe('ApprovalRulesSection', () => {
  let fixture: ComponentFixture<ApprovalRulesSection>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext, rules: ApprovalRules = approvalRules()) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(ApprovalRulesSection);
    fixture.detectChanges();
    http.expectOne(RULES).flush(rules);
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const me = () => fixture.componentInstance as unknown as Internals;
  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

  afterEach(() => http.verify());

  it('draws one row for each of the nine transaction types, with the contract labels', async () => {
    const el = await open(OWNER_CONTEXT);
    const names = Array.from(el.querySelectorAll('tbody tr > td:first-child strong')).map((s) => s.textContent?.trim());
    expect(names).toEqual([
      'Purchase order',
      'Receive stock (rate rise)',
      'Vendor return',
      'Indent',
      'Stock transfer',
      'Write-off',
      'Stock adjustment',
      'Discount at sale',
      'Sales return',
    ]);
  });

  it('lists the three fixed rules with no switch, and the delegates read-only', async () => {
    const el = await open(OWNER_CONTEXT);
    expect(text(el, 'fixed-NO_SELF_APPROVAL')).toBe('Nobody approves their own request.');
    expect(text(el, 'fixed-SCHEDULE_X_TWO_SIGNATURES')).toBe('Schedule X needs a pharmacist and a second signature.');
    expect(text(el, 'fixed-LEDGER_APPEND_ONLY')).toBe(
      'The stock ledger cannot be edited; corrections are reversing lines.',
    );
    expect(q(el, 'fixed-rules')?.querySelector('button, input, mat-select')).toBeNull();
    expect(text(el, 'current-delegates')).toContain('Test OPD Store: Test Doctor Two until 30 Nov 2026');
  });

  it('shows the escalation note and, for a store approver row, "<store> has no approver"', async () => {
    const el = await open(OWNER_CONTEXT);
    expect(text(el, 'escalation-note')).toBe('Escalation starts working when the approvals inbox is released.');
    expect(text(el, 'no-approver-INDENT')).toBe('Test OT Store has no approver');
    expect(q(el, 'no-approver-PURCHASE_ORDER')).toBeNull();
  });

  it('shows the banner "Review these rules before use." before the first save', async () => {
    const el = await open(
      OWNER_CONTEXT,
      approvalRules({ versionNo: 0, preset: 'STANDARD', reviewNeeded: true, savedAt: null, savedBy: null }),
    );
    expect(text(el, 'rules-review')).toBe('Review these rules before use.');
    expect(q(el, 'rules-version')).toBeNull();
  });

  it('shows no banner once the rules were saved', async () => {
    const el = await open(OWNER_CONTEXT);
    expect(q(el, 'rules-review')).toBeNull();
  });

  it('gives a reader of setup the rules as text and no button or field', async () => {
    const el = await open({ ...PHARMACIST_CONTEXT, capabilities: ['SETUP_VIEW'] });
    expect(text(el, 'rule-PURCHASE_ORDER-l1')).toBe('Owner · Above ₹ 50,000');
    expect(text(el, 'rule-INDENT-l1')).toBe('Store approver · Always');
    expect(text(el, 'rule-SALE_DISCOUNT-l1')).toBe('Owner · Above 10%');
    expect(text(el, 'rule-WRITE_OFF-count')).toBe('No approval');
    expect(text(el, 'rule-INDENT-escalation')).toBe('after 2 hours, urgent after 30 minutes');
    expect(q(el, 'save-rules')).toBeNull();
    expect(q(el, 'preset-select')).toBeNull();
    expect(el.querySelector('table input, table mat-select')).toBeNull();
    expect(text(el, 'preset-readout')).toBe('Custom');
  });

  it('gives the owner the preset selector, the Save button and the fields', async () => {
    const el = await open(OWNER_CONTEXT);
    expect(q(el, 'preset-select')).not.toBeNull();
    expect(q(el, 'save-rules')).not.toBeNull();
    expect(q(el, 'rule-PURCHASE_ORDER-levels')).not.toBeNull();
    expect(q(el, 'rule-PURCHASE_ORDER-l1-amount')).not.toBeNull();
    expect(q(el, 'rule-SALE_DISCOUNT-l1-percent')).not.toBeNull();
    expect(q(el, 'rule-INDENT-urgent')).not.toBeNull();
  });

  it('refuses an amount condition with no limit and sends nothing', async () => {
    const el = await open(OWNER_CONTEXT);
    me().patchLevel(0, 0, { amount: '' });
    me().save();
    await settle();
    expect(el.textContent).toContain('Enter the limit');
    http.expectNone(RULES);
  });

  it('saves the nine rows in one call with the version it read and amounts in paise', async () => {
    const el = await open(OWNER_CONTEXT);
    me().patchLevel(0, 0, { amount: '75000.50' });
    me().setLevels(6, 1);
    me().patchLevel(6, 0, { approver: 'OWNER', kind: 'ABOVE_AMOUNT', amount: '1000' });
    me().patchRule(3, { escalateAfterHours: '4' });
    me().setHighValue('5000');
    expect(me().presetShown()).toBe('CUSTOM');
    me().save();
    const request = http.expectOne(RULES);
    expect(request.request.method).toBe('PUT');
    const body = request.request.body;
    expect(body.baseVersionNo).toBe(2);
    expect(body.highValueThresholdPaise).toBe(500000);
    expect(body.rules).toHaveLength(9);
    expect(body.rules[0].levels[0].condition).toEqual({
      kind: 'ABOVE_AMOUNT',
      amountPaise: 7500050,
      percent: null,
      storeTypes: null,
    });
    expect(body.rules[6].levels[0]).toMatchObject({ level: 1, approver: 'OWNER' });
    expect(body.rules[3].escalateAfterHours).toBe(4);
    expect(body.rules[3].urgentEscalateAfterMinutes).toBe(30);
    request.flush(approvalRules({ versionNo: 3 }));
    await settle();
    expect(q(el, 'rules-saved')).not.toBeNull();
  });

  it('keeps what was typed and shows the sentence when the save fails', async () => {
    const el = await open(OWNER_CONTEXT);
    me().patchLevel(0, 0, { amount: '75000' });
    me().save();
    http.expectOne(RULES).error(new ProgressEvent('error'));
    await settle();
    expect(text(el, 'rules-save-error')).toBe('Could not save. Check your connection and try again.');
    expect((q(el, 'rule-PURCHASE_ORDER-l1-amount') as HTMLInputElement).value).toBe('75000');
  });

  it('shows the server sentence and reads the current rules after a conflict', async () => {
    const el = await open(OWNER_CONTEXT);
    me().patchLevel(0, 0, { amount: '75000' });
    me().save();
    http.expectOne(RULES).flush(
      { type: 'https://sahaj.example/problems/rules-changed', title: 'Conflict', status: 409, detail: 'The rules were changed by someone else.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    http.expectOne(RULES).flush(approvalRules({ versionNo: 3 }));
    await settle();
    expect(text(el, 'rules-save-error')).toBe('The rules were changed by someone else.');
    expect(text(el, 'rules-version')).toContain('Version 3');
  });

  it('asks for confirmation through the preset sheet, and applies nothing when it is cancelled', async () => {
    await open(OWNER_CONTEXT);
    const spy = vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(null) } as never);
    const select = { value: 'x' };
    me().choosePreset('STRICT', select);
    expect(spy).toHaveBeenCalledOnce();
    expect(spy.mock.calls[0][1]).toMatchObject({
      ariaLabelledBy: PRESET_DIALOG_CONFIG.ariaLabelledBy,
      data: { preset: 'STRICT', baseVersionNo: 2, limitTypes: approvalRules().presets?.[2].limitTypes },
    });
    expect(select.value).toBe('CUSTOM');
    // A cancelled sheet reads the current rules again to learn of a conflict.
    http.expectOne(RULES).flush(approvalRules());
  });

  it('shows the preset the server applied', async () => {
    const el = await open(OWNER_CONTEXT);
    const applied = approvalRules({ versionNo: 3, preset: 'LEAN' });
    vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(applied) } as never);
    me().choosePreset('LEAN', { value: '' });
    await settle();
    expect(text(el, 'rules-version')).toContain('Version 3');
    expect(me().presetShown()).toBe('LEAN');
  });

  it('offers the rate rise only Always and Above a percentage, and the preset sheet the types the server listed', async () => {
    const rules = approvalRules({
      presets: [
        { preset: 'LEAN', limitTypes: ['VENDOR_RETURN'] },
        { preset: 'STANDARD', limitTypes: [] },
        { preset: 'STRICT', limitTypes: ['SALE_DISCOUNT', 'INDENT'] },
      ],
    });
    await open(OWNER_CONTEXT, rules);
    expect(me().conditionsFor('GRN_RATE_RISE', 'ALWAYS')).toEqual(['ALWAYS', 'ABOVE_PERCENT']);
    expect(me().conditionsFor('PURCHASE_ORDER', 'ALWAYS')).toContain('ABOVE_AMOUNT');
    const spy = vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(null) } as never);
    me().choosePreset('STRICT', { value: 'x' });
    expect((spy.mock.calls[0][1] as { data: { limitTypes: string[] } }).data.limitTypes).toEqual(['SALE_DISCOUNT', 'INDENT']);
    http.expectOne(RULES).flush(rules);
  });

  it('offers the rate rise only Always and Above a percentage, and the preset sheet the types the server listed', async () => {
    const rules = approvalRules({
      presets: [
        { preset: 'LEAN', limitTypes: ['VENDOR_RETURN'] },
        { preset: 'STANDARD', limitTypes: [] },
        { preset: 'STRICT', limitTypes: ['SALE_DISCOUNT', 'INDENT'] },
      ],
    });
    await open(OWNER_CONTEXT, rules);
    expect(me().conditionsFor('GRN_RATE_RISE', 'ALWAYS')).toEqual(['ALWAYS', 'ABOVE_PERCENT']);
    expect(me().conditionsFor('PURCHASE_ORDER', 'ALWAYS')).toContain('ABOVE_AMOUNT');
    const spy = vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(null) } as never);
    me().choosePreset('STRICT', { value: 'x' });
    expect((spy.mock.calls[0][1] as { data: { limitTypes: string[] } }).data.limitTypes).toEqual(['SALE_DISCOUNT', 'INDENT']);
    http.expectOne(RULES).flush(rules);
  });

  it('says "Custom" once the rows are changed by hand after a preset', async () => {
    await open(OWNER_CONTEXT, approvalRules({ preset: 'STANDARD' }));
    expect(me().presetShown()).toBe('STANDARD');
    me().patchRule(5, { escalateAfterHours: '12' });
    expect(me().presetShown()).toBe('CUSTOM');
  });

  it('shows "Could not load. Try again." when the rules cannot be read', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(OWNER_CONTEXT);
    fixture = TestBed.createComponent(ApprovalRulesSection);
    fixture.detectChanges();
    http.expectOne(RULES).error(new ProgressEvent('error'));
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    expect(text(el, 'rules-error')).toBe('Could not load. Try again.');
    expect(q(el, 'rules-table')).toBeNull();
  });
});
