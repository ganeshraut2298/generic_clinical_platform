import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelect, MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { formatInrPaise } from '../../../shared/format/inr';
import { formatDisplayDate, formatDisplayDateOfInstant } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { sheetToast } from '../../../shared/ui/sheet-toast';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, problemSlug, saveErrorMessage } from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import {
  ApprovalRules,
  ApproverKind,
  ConditionKind,
  RulePreset,
  STORE_TYPE_LABELS,
  TransactionType,
} from '../pharmacy.model';
import {
  APPROVER_LABELS,
  CONDITION_LABELS,
  DraftErrors,
  ESCALATION_NOTE,
  LevelDraft,
  PRESET_DESCRIPTIONS,
  PRESET_LABELS,
  PresetKey,
  REVIEW_BANNER,
  RuleDraft,
  STORE_TYPE_OPTIONS,
  TRANSACTION_LABELS,
  allowedConditions,
  levelText,
  newLevel,
  paiseToRupeesText,
  rupeesTextToPaise,
  sameRules,
  toRule,
  toRuleDraft,
  validateDrafts,
} from './approval-rules.util';
import { PRESET_DIALOG_CONFIG, PresetDialog, PresetDialogData } from './preset.dialog';

const PRESETS: readonly PresetKey[] = ['LEAN', 'STANDARD', 'STRICT'];

/**
 * "Approval rules" section of Inventory setup (story 78-9; a NEW screen, no mockup draws it, so it follows
 * the Setup tab look). One row per transaction type from the server: the number of levels (0, 1 or 2), the
 * role of each level, the condition and the limit, and the escalation. The preset selector replaces all rows
 * after a confirmation. Three fixed rules are listed and have no switch. The owner edits and saves the nine
 * rows in one save; a reader of setup sees the same rules as text and no button.
 */
@Component({
  selector: 'app-approval-rules',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, IconComponent],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px;
    }
    .preset {
      width: 280px;
      max-width: 100%;
    }
    .opt-desc {
      display: block;
      font-size: 0.76rem;
      color: var(--ink-3);
    }
    table {
      min-width: 980px;
    }
    td {
      vertical-align: top;
    }
    .cell {
      display: flex;
      flex-direction: column;
      gap: 6px;
      min-width: 190px;
    }
    .small {
      width: 130px;
    }
    .sub {
      font-size: 0.76rem;
      color: var(--ink-3);
    }
    .err-text {
      font-size: 0.76rem;
      color: var(--crit);
    }
    .warn-text {
      font-size: 0.76rem;
      color: var(--warn);
    }
    .fixed li,
    .delegates li {
      margin: 2px 0;
    }
    .high {
      max-width: 320px;
    }
    .save {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="ar-h" data-testid="approval-rules">
      <div class="panel-h">
        <h2 id="ar-h">Approval rules</h2>
        @if (rules(); as r) {
          @if (canEdit()) {
            <div class="head">
              <mat-form-field appearance="outline" subscriptSizing="dynamic" class="preset">
                <mat-label>Preset</mat-label>
                <mat-select
                  #presetSelect
                  [value]="presetShown()"
                  (selectionChange)="choosePreset($event.value, presetSelect)"
                  data-testid="preset-select"
                >
                  @for (key of presets; track key) {
                    <mat-option [value]="key" [attr.data-testid]="'preset-' + key">
                      {{ presetLabels[key] }}
                      <span class="opt-desc">{{ presetDescriptions[key] }}</span>
                    </mat-option>
                  }
                  @if (presetShown() === 'CUSTOM') {
                    <mat-option value="CUSTOM" disabled>Custom</mat-option>
                  }
                </mat-select>
              </mat-form-field>
            </div>
          } @else {
            <span class="pill p-blue" data-testid="preset-readout">{{ presetLabels[r.preset] }}</span>
          }
        }
      </div>

      @if (loading()) {
        <p class="loading" role="status" data-testid="rules-loading">Loading approval rules</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="rules-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
        <div class="actions">
          <button mat-stroked-button type="button" (click)="load()">Try again</button>
        </div>
      } @else if (rules(); as r) {
        @if (r.reviewNeeded) {
          <div class="notice warn" role="status" data-testid="rules-review">
            <app-icon name="alert" [size]="16" />
            <span>{{ reviewBanner }}</span>
          </div>
        }

        <div class="tbl-wrap" data-testid="rules-table">
          <table>
            <thead>
              <tr>
                <th scope="col">Transaction</th>
                <th scope="col">Levels</th>
                <th scope="col">Level 1</th>
                <th scope="col">Level 2</th>
                <th scope="col">Escalation</th>
              </tr>
            </thead>
            <tbody>
              @for (row of rows(); track row.transactionType; let i = $index) {
                <tr [attr.data-testid]="'rule-' + row.transactionType">
                  <td>
                    <strong>{{ typeLabels[row.transactionType] }}</strong>
                    @for (name of noApproverFor(row); track name) {
                      <div class="warn-text" [attr.data-testid]="'no-approver-' + row.transactionType">
                        {{ name }} has no approver
                      </div>
                    }
                  </td>
                  @if (canEdit()) {
                    <td>
                      <mat-form-field appearance="outline" subscriptSizing="dynamic" class="small">
                        <mat-label>Levels</mat-label>
                        <mat-select
                          [value]="row.levels.length"
                          (selectionChange)="setLevels(i, $event.value)"
                          [attr.data-testid]="'rule-' + row.transactionType + '-levels'"
                        >
                          <mat-option [value]="0">0 (none)</mat-option>
                          <mat-option [value]="1">1</mat-option>
                          <mat-option [value]="2">2</mat-option>
                        </mat-select>
                      </mat-form-field>
                    </td>
                    @for (slot of slots; track slot) {
                      <td>
                        @if (row.levels[slot]; as level) {
                          <div class="cell">
                            <mat-form-field appearance="outline" subscriptSizing="dynamic">
                              <mat-label>Role</mat-label>
                              <mat-select
                                [value]="level.approver"
                                (selectionChange)="patchLevel(i, slot, { approver: $event.value })"
                                [attr.data-testid]="'rule-' + row.transactionType + '-l' + (slot + 1) + '-approver'"
                              >
                                @for (kind of approverKinds; track kind) {
                                  <mat-option [value]="kind">{{ approverLabels[kind] }}</mat-option>
                                }
                              </mat-select>
                            </mat-form-field>
                            <mat-form-field appearance="outline" subscriptSizing="dynamic">
                              <mat-label>Condition</mat-label>
                              <mat-select
                                [value]="level.kind"
                                (selectionChange)="patchLevel(i, slot, { kind: $event.value })"
                                [attr.data-testid]="'rule-' + row.transactionType + '-l' + (slot + 1) + '-condition'"
                              >
                                @for (kind of conditionsFor(row.transactionType, level.kind); track kind) {
                                  <mat-option [value]="kind">{{ conditionLabels[kind] }}</mat-option>
                                }
                              </mat-select>
                            </mat-form-field>
                            @if (level.kind === 'ABOVE_AMOUNT') {
                              <mat-form-field appearance="outline" subscriptSizing="dynamic">
                                <mat-label>Limit, ₹</mat-label>
                                <input
                                  matInput
                                  inputmode="decimal"
                                  autocomplete="off"
                                  [ngModel]="level.amount"
                                  (ngModelChange)="patchLevel(i, slot, { amount: $event })"
                                  [attr.data-testid]="'rule-' + row.transactionType + '-l' + (slot + 1) + '-amount'"
                                />
                              </mat-form-field>
                            }
                            @if (level.kind === 'ABOVE_PERCENT') {
                              <mat-form-field appearance="outline" subscriptSizing="dynamic">
                                <mat-label>Limit, %</mat-label>
                                <input
                                  matInput
                                  inputmode="decimal"
                                  autocomplete="off"
                                  [ngModel]="level.percent"
                                  (ngModelChange)="patchLevel(i, slot, { percent: $event })"
                                  [attr.data-testid]="'rule-' + row.transactionType + '-l' + (slot + 1) + '-percent'"
                                />
                              </mat-form-field>
                            }
                            @if (level.kind === 'STORE_TYPE') {
                              <mat-form-field appearance="outline" subscriptSizing="dynamic">
                                <mat-label>Store types</mat-label>
                                <mat-select
                                  multiple
                                  [value]="level.storeTypes"
                                  (selectionChange)="patchLevel(i, slot, { storeTypes: $event.value })"
                                  [attr.data-testid]="'rule-' + row.transactionType + '-l' + (slot + 1) + '-storetypes'"
                                >
                                  @for (type of storeTypes; track type) {
                                    <mat-option [value]="type">{{ storeTypeLabels[type] }}</mat-option>
                                  }
                                </mat-select>
                              </mat-form-field>
                            }
                            @if (errors().rows[row.transactionType]?.level?.[slot]; as text) {
                              <span class="err-text" role="alert">{{ text }}</span>
                            }
                          </div>
                        } @else {
                          <span class="sub">—</span>
                        }
                      </td>
                    }
                    <td>
                      <div class="cell">
                        <mat-form-field appearance="outline" subscriptSizing="dynamic">
                          <mat-label>Escalate after, hours</mat-label>
                          <input
                            matInput
                            inputmode="numeric"
                            maxlength="3"
                            autocomplete="off"
                            [ngModel]="row.escalateAfterHours"
                            (ngModelChange)="patchRule(i, { escalateAfterHours: $event })"
                            [attr.data-testid]="'rule-' + row.transactionType + '-escalate'"
                          />
                        </mat-form-field>
                        @if (errors().rows[row.transactionType]?.escalate; as text) {
                          <span class="err-text" role="alert">{{ text }}</span>
                        }
                        @if (row.transactionType === 'INDENT') {
                          <mat-form-field appearance="outline" subscriptSizing="dynamic">
                            <mat-label>Urgent, minutes</mat-label>
                            <input
                              matInput
                              inputmode="numeric"
                              maxlength="4"
                              autocomplete="off"
                              [ngModel]="row.urgentEscalateAfterMinutes"
                              (ngModelChange)="patchRule(i, { urgentEscalateAfterMinutes: $event })"
                              [attr.data-testid]="'rule-INDENT-urgent'"
                            />
                          </mat-form-field>
                          @if (errors().rows[row.transactionType]?.urgent; as text) {
                            <span class="err-text" role="alert">{{ text }}</span>
                          }
                        }
                      </div>
                    </td>
                  } @else {
                    <td [attr.data-testid]="'rule-' + row.transactionType + '-count'">
                      {{ row.levels.length === 0 ? 'No approval' : row.levels.length }}
                    </td>
                    @for (slot of slots; track slot) {
                      <td [attr.data-testid]="'rule-' + row.transactionType + '-l' + (slot + 1)">
                        {{ readLevel(row.transactionType, slot) }}
                      </td>
                    }
                    <td [attr.data-testid]="'rule-' + row.transactionType + '-escalation'">
                      {{ readEscalation(row) }}
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>

        <p class="note" data-testid="escalation-note">{{ escalationNote }}</p>

        @if (canEdit()) {
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="high">
            <mat-label>High-value item, ₹</mat-label>
            <input
              matInput
              inputmode="decimal"
              autocomplete="off"
              [ngModel]="highValue()"
              (ngModelChange)="setHighValue($event)"
              data-testid="rule-high-value"
            />
            <mat-hint>Used by the condition High-value item. Sahaj sets no amount.</mat-hint>
          </mat-form-field>
          @if (errors().highValue; as text) {
            <span class="err-text" role="alert">{{ text }}</span>
          }
        } @else if (r.highValueThresholdPaise !== null) {
          <p class="note" data-testid="rule-high-value-read">
            High-value item: {{ highValueRead(r) }}
          </p>
        }

        @if (formError(); as text) {
          <div class="notice err" role="alert" data-testid="rules-save-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }
        @if (canEdit()) {
          <div class="save">
            <button
              mat-flat-button
              type="button"
              data-testid="save-rules"
              [disabled]="saving()"
              (click)="save()"
            >
              Save rules
            </button>
            @if (savedNote()) {
              <span class="saved" role="status" data-testid="rules-saved">Saved</span>
            }
          </div>
        }
        @if (r.savedAt && r.savedBy) {
          <p class="note" data-testid="rules-version">
            Version {{ r.versionNo }}, saved {{ savedOn(r.savedAt) }} by {{ r.savedBy.name }}
          </p>
        }
      }
    </section>

    @if (rules(); as r) {
      @if (!loading() && !loadError()) {
        <section class="panel surf" aria-labelledby="fr-h" data-testid="fixed-rules">
          <h2 id="fr-h">Fixed rules</h2>
          <ul class="fixed">
            @for (fixed of r.fixedRules; track fixed.key) {
              <li [attr.data-testid]="'fixed-' + fixed.key">{{ fixed.text }}</li>
            }
          </ul>
          <p class="note">These rules cannot be switched off.</p>
        </section>

        @if (r.currentDelegates.length > 0) {
          <section class="panel surf" aria-labelledby="dl-h" data-testid="current-delegates">
            <h2 id="dl-h">Delegate approvers now</h2>
            <ul class="delegates">
              @for (d of r.currentDelegates; track d.storeId + d.membershipId) {
                <li>{{ d.storeName }}: {{ d.name }} until {{ date(d.to) }}</li>
              }
            </ul>
            <p class="note">Delegates are set on the store, in Stores &amp; staff.</p>
          </section>
        }
      }
    }
  `,
})
export class ApprovalRulesSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly context = inject(PharmacyContextStore);

  protected readonly canEdit = this.context.canEditSetup;

  protected readonly typeLabels = TRANSACTION_LABELS;
  protected readonly approverLabels = APPROVER_LABELS;
  protected readonly conditionLabels = CONDITION_LABELS;
  protected readonly presetLabels = PRESET_LABELS;
  protected readonly presetDescriptions = PRESET_DESCRIPTIONS;
  protected readonly storeTypeLabels = STORE_TYPE_LABELS;
  protected readonly storeTypes = STORE_TYPE_OPTIONS;
  protected readonly presets = PRESETS;
  protected readonly approverKinds: readonly ApproverKind[] = ['STORE_APPROVER', 'OWNER'];
  protected readonly slots = [0, 1] as const;
  protected readonly escalationNote = ESCALATION_NOTE;
  protected readonly reviewBanner = REVIEW_BANNER;
  protected readonly date = formatDisplayDate;
  protected readonly savedOn = formatDisplayDateOfInstant;

  protected readonly rules = signal<ApprovalRules | null>(null);
  protected readonly rows = signal<RuleDraft[]>([]);
  protected readonly highValue = signal('');
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly savedNote = signal(false);
  /** Set once the owner tried to save, so the messages appear then and not while typing the first value. */
  private readonly checked = signal(false);

  private saved: RuleDraft[] = [];
  private savedHighValue = '';

  /** The checks of the draft; shown after the first save attempt. */
  protected readonly errors = computed<DraftErrors>(() =>
    this.checked()
      ? validateDrafts(this.rows(), this.highValue())
      : { rows: {}, highValue: null, any: false },
  );

  /** The selector shows "Custom" once the rows differ from what was saved (story 78-9). */
  protected readonly presetShown = computed<RulePreset>(() => {
    const r = this.rules();
    if (!r) {
      return 'CUSTOM';
    }
    const dirty = !sameRules(this.rows(), this.saved) || this.highValue() !== this.savedHighValue;
    return dirty ? 'CUSTOM' : r.preset;
  });

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.approvalRules().subscribe({
      next: (rules) => {
        this.apply(rules);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }

  private apply(rules: ApprovalRules): void {
    this.rules.set(rules);
    const drafts = rules.rules.map(toRuleDraft);
    this.rows.set(drafts);
    this.saved = drafts;
    this.savedHighValue = paiseToRupeesText(rules.highValueThresholdPaise);
    this.highValue.set(this.savedHighValue);
    this.checked.set(false);
  }

  // ---- editing

  protected conditionsFor(type: TransactionType, current: ConditionKind): readonly ConditionKind[] {
    const allowed = allowedConditions(type);
    return allowed.includes(current) ? allowed : [...allowed, current];
  }

  protected setLevels(index: number, count: number): void {
    this.touch();
    this.rows.update((rows) =>
      rows.map((row, i) => {
        if (i !== index) {
          return row;
        }
        const levels = row.levels.slice(0, count);
        while (levels.length < count) {
          levels.push(levels.length === 0 ? newLevel() : { ...newLevel(), approver: 'OWNER' });
        }
        return { ...row, levels };
      }),
    );
  }

  protected patchLevel(index: number, slot: number, patch: Partial<LevelDraft>): void {
    this.touch();
    this.rows.update((rows) =>
      rows.map((row, i) =>
        i === index
          ? { ...row, levels: row.levels.map((level, s) => (s === slot ? { ...level, ...patch } : level)) }
          : row,
      ),
    );
  }

  protected patchRule(index: number, patch: Partial<RuleDraft>): void {
    this.touch();
    this.rows.update((rows) => rows.map((row, i) => (i === index ? { ...row, ...patch } : row)));
  }

  protected setHighValue(text: string): void {
    this.touch();
    this.highValue.set(text);
  }

  private touch(): void {
    this.savedNote.set(false);
    this.formError.set(null);
  }

  // ---- text for a reader and the "no approver" hints

  protected readLevel(type: TransactionType, slot: number): string {
    const rule = this.rules()?.rules.find((r) => r.transactionType === type);
    const level = rule?.levels.find((l) => l.level === slot + 1);
    return level ? levelText(level) : '—';
  }

  protected readEscalation(row: RuleDraft): string {
    const parts: string[] = [];
    if (row.escalateAfterHours !== '') {
      parts.push(`after ${row.escalateAfterHours} hours`);
    }
    if (row.transactionType === 'INDENT' && row.urgentEscalateAfterMinutes !== '') {
      parts.push(`urgent after ${row.urgentEscalateAfterMinutes} minutes`);
    }
    return parts.length > 0 ? parts.join(', ') : '—';
  }

  protected highValueRead(rules: ApprovalRules): string {
    return rules.highValueThresholdPaise === null
      ? ''
      : formatInrPaise(rules.highValueThresholdPaise).replace('₹', '₹ ');
  }

  /** "<store> has no approver" for a row whose rule names the store approver (the save is still allowed). */
  protected noApproverFor(row: RuleDraft): string[] {
    const stores = this.rules()?.storesWithoutApprover ?? [];
    return row.levels.some((l) => l.approver === 'STORE_APPROVER') ? stores.map((s) => s.name) : [];
  }

  // ---- presets

  protected choosePreset(key: RulePreset, select: MatSelect): void {
    const rules = this.rules();
    // The select shows what is saved or "Custom"; a choice is only a request until the owner confirms.
    select.value = this.presetShown();
    if (!rules || key === 'CUSTOM') {
      return;
    }
    const limitTypes = rules.presets?.find((p) => p.preset === key)?.limitTypes ?? [];
    const data: PresetDialogData = { preset: key, baseVersionNo: rules.versionNo, limitTypes };
    this.formError.set(null);
    this.dialog
      .open(PresetDialog, { ...PRESET_DIALOG_CONFIG, data })
      .afterClosed()
      .subscribe((applied: ApprovalRules | null | undefined) => {
        if (applied) {
          this.apply(applied);
          this.toast('Preset applied');
        } else {
          // A conflict may have changed the rules while the sheet was open; read the current ones.
          this.reloadQuietly();
        }
      });
  }

  private reloadQuietly(): void {
    this.api.approvalRules().subscribe({
      next: (rules) => {
        if (sameRules(this.rows(), this.saved) && this.highValue() === this.savedHighValue) {
          this.apply(rules);
        } else if (rules.versionNo !== this.rules()?.versionNo) {
          // The owner has unsaved edits; keep them but take the new version number for the next save.
          this.rules.set(rules);
        }
      },
      error: () => undefined,
    });
  }

  // ---- save

  protected save(): void {
    const rules = this.rules();
    if (!rules || this.saving()) {
      return;
    }
    this.formError.set(null);
    this.savedNote.set(false);
    this.checked.set(true);
    const check = validateDrafts(this.rows(), this.highValue());
    if (check.any) {
      return;
    }
    const text = this.highValue().trim();
    this.saving.set(true);
    this.api
      .saveApprovalRules({
        baseVersionNo: rules.versionNo,
        highValueThresholdPaise: text === '' ? null : rupeesTextToPaise(text),
        rules: this.rows().map(toRule),
      })
      .subscribe({
        next: (saved) => {
          this.saving.set(false);
          this.apply(saved);
          this.savedNote.set(true);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.formError.set(saveErrorMessage(error));
          if (problemSlug(error) === 'rules-changed') {
            // Someone else saved first: the server's sentence stays, and the rules now current are read.
            this.api.approvalRules().subscribe({
              next: (current) => {
                this.apply(current);
              },
              error: () => undefined,
            });
          }
        },
      });
  }

  private toast(text: string): void {
    this.snackBar.open(text, undefined, sheetToast(3000));
  }
}
