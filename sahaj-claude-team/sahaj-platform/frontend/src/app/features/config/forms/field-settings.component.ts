import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { EmrFormField, EmrFormOption, EmrFormScoreRule, EmrFormWho } from '../../emr/emr-phase3.model';
import { optionsOf } from '../../emr/forms/form.logic';
import {
  BANDS_MAX,
  CAN_BE_REQUIRED,
  CAN_COPY_LAST,
  CAN_HAVE_HELP,
  HAS_OPTIONS,
  LABEL_MAX,
  RULES_MAX,
  SCORES_OPTIONS,
  TYPE_ICON,
  TYPE_LABEL,
  WHO_CHOICES,
  addOption,
  scoreSourceCandidates,
  showIfCandidates,
  showIfFor,
  stepScore,
} from './form-builder.logic';
import { FormBuilderStore } from './form-builder.store';

const WIDTHS: readonly { value: number; label: string }[] = [
  { value: 6, label: 'Full' },
  { value: 3, label: 'Half' },
  { value: 2, label: 'Third' },
];

/**
 * The right-hand "Field" panel of the form builder (SAH-59-1 to SAH-59-3): label, help, width,
 * required, print and copy switches, options with scores, number limits and normal range, the
 * Score (Σ) sources and points rules, Score band bands, the show-if rule, who can fill, and the
 * standard code. Only the settings that fit the type are shown. Read-only for anyone but the owner.
 */
@Component({
  selector: 'app-field-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './field-settings.component.scss',
  templateUrl: './field-settings.component.html',
})
export class FieldSettingsComponent {
  protected readonly store = inject(FormBuilderStore);

  protected readonly widths = WIDTHS;
  protected readonly who = WHO_CHOICES;
  protected readonly labelMax = LABEL_MAX;
  protected readonly bandsMax = BANDS_MAX;
  protected readonly rulesMax = RULES_MAX;

  protected readonly field = this.store.selected;
  protected readonly editable = this.store.canEdit;
  protected readonly typeName = computed(() => (this.field() ? TYPE_LABEL[this.field()!.type] : ''));
  protected readonly typeIcon = computed(() => (this.field() ? TYPE_ICON[this.field()!.type] : ''));
  protected readonly labelCaption = computed(() => {
    const t = this.field()?.type;
    return t === 'INFO_NOTE' ? 'Text' : t === 'SECTION_HEADING' ? 'Heading' : 'Label';
  });
  protected readonly canHelp = computed(() => !!this.field() && CAN_HAVE_HELP(this.field()!.type));
  protected readonly canRequire = computed(() => !!this.field() && CAN_BE_REQUIRED(this.field()!.type));
  protected readonly canCopy = computed(() => !!this.field() && CAN_COPY_LAST(this.field()!.type));
  protected readonly hasChoices = computed(() => !!this.field() && HAS_OPTIONS(this.field()!.type));
  protected readonly isYesNo = computed(() => this.field()?.type === 'YES_NO');
  protected readonly hasScoredOptions = computed(() => !!this.field() && SCORES_OPTIONS(this.field()!.type));
  protected readonly optionList = computed(() => (this.field() ? optionsOf(this.field()!) : []));
  protected readonly isNumber = computed(() => this.field()?.type === 'NUMBER');
  protected readonly isScore = computed(() => this.field()?.type === 'SCORE');
  protected readonly isBand = computed(() => this.field()?.type === 'SCORE_BAND');
  protected readonly secondLabels = computed(() => {
    const lang = this.store.settings()?.printLanguage;
    return lang === 'EN_MR' ? 'Marathi' : lang === 'EN_HI' ? 'Hindi' : null;
  });

  protected readonly sources = computed(() => scoreSourceCandidates(this.store.fields()).filter((f) => f.key !== this.field()?.key));
  protected readonly numberFields = computed(() => this.store.fields().filter((f) => f.type === 'NUMBER'));
  protected readonly multiFields = computed(() => this.store.fields().filter((f) => f.type === 'MULTI_CHOICE'));
  protected readonly scoreFields = computed(() => this.store.fields().filter((f) => f.type === 'SCORE'));
  protected readonly logicSources = computed(() => showIfCandidates(this.store.fields(), this.store.selectedIndex()));
  protected readonly logicSource = computed(() => {
    const rule = this.field()?.showIf;
    return rule ? (this.store.fields().find((f) => f.key === rule.fieldKey) ?? null) : null;
  });
  protected readonly logicOptions = computed(() => (this.logicSource() ? optionsOf(this.logicSource()!) : []));

  private patch(p: Partial<EmrFormField>): void {
    const f = this.field();
    if (f) {
      this.store.patchField(f.key, p);
    }
  }

  private text(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  private num(event: Event): number | null {
    const raw = (event.target as HTMLInputElement).value;
    return raw === '' || !Number.isFinite(Number(raw)) ? null : Number(raw);
  }

  protected onLabel(event: Event): void {
    this.patch({ label: this.text(event).slice(0, LABEL_MAX) });
  }

  protected onLabelMr(event: Event): void {
    this.patch({ labelMr: this.text(event) || null });
  }

  protected onLabelHi(event: Event): void {
    this.patch({ labelHi: this.text(event) || null });
  }

  protected onHelp(event: Event): void {
    this.patch({ help: this.text(event) || null });
  }

  protected onCode(event: Event): void {
    this.patch({ standardCode: this.text(event) || null });
  }

  protected onUnit(event: Event): void {
    this.patch({ unit: this.text(event) || null });
  }

  protected onRange(key: 'min' | 'max' | 'normalFrom' | 'normalTo', event: Event): void {
    this.patch({ [key]: this.num(event) } as Partial<EmrFormField>);
  }

  protected setWidth(width: number): void {
    this.patch({ width });
  }

  protected toggle(key: 'required' | 'print' | 'copyFromLast'): void {
    const f = this.field();
    if (f) {
      this.patch({ [key]: !f[key] } as Partial<EmrFormField>);
    }
  }

  protected toggleWho(who: EmrFormWho): void {
    const f = this.field();
    if (f) {
      this.patch({ whoCanFill: f.whoCanFill.includes(who) ? f.whoCanFill.filter((w) => w !== who) : [...f.whoCanFill, who] });
    }
  }

  // ---- options ----

  private setOptions(options: EmrFormOption[]): void {
    this.patch({ options });
  }

  protected onOptionLabel(index: number, event: Event): void {
    const f = this.field();
    if (f) {
      this.setOptions((f.options ?? []).map((o, i) => (i === index ? { ...o, label: this.text(event) } : o)));
    }
  }

  protected scoreOption(key: string, direction: 1 | -1): void {
    const f = this.field();
    if (!f) {
      return;
    }
    const current = optionsOf(f);
    this.setOptions(current.map((o) => (o.key === key ? { ...o, score: stepScore(o.score, direction) } : o)));
  }

  protected removeOption(index: number): void {
    const f = this.field();
    if (f && (f.options ?? []).length > 1) {
      this.setOptions((f.options ?? []).filter((_, i) => i !== index));
    }
  }

  protected addOneOption(): void {
    const f = this.field();
    if (f) {
      this.setOptions(addOption(f));
    }
  }

  // ---- show-if ----

  protected onLogicField(event: Event): void {
    const key = (event.target as HTMLSelectElement).value;
    this.patch({ showIf: key ? showIfFor(this.store.fields().find((f) => f.key === key)) : null });
  }

  protected pickLogicValue(optionKey: string): void {
    const rule = this.field()?.showIf;
    if (rule) {
      this.patch({ showIf: { fieldKey: rule.fieldKey, optionKey } });
    }
  }

  // ---- score ----

  protected isSource(key: string): boolean {
    return this.field()?.score?.sourceFieldKeys.includes(key) ?? false;
  }

  protected toggleSource(key: string): void {
    const f = this.field();
    if (!f?.score) {
      return;
    }
    const keys = f.score.sourceFieldKeys;
    this.patch({ score: { ...f.score, sourceFieldKeys: keys.includes(key) ? keys.filter((k) => k !== key) : [...keys, key] } });
  }

  protected addRule(kind: EmrFormScoreRule['kind']): void {
    const f = this.field();
    if (!f?.score || f.score.rules.length >= RULES_MAX) {
      return;
    }
    const source = kind === 'NUMBER_BELOW' ? this.numberFields()[0] : this.multiFields()[0];
    if (!source) {
      return;
    }
    const threshold = kind === 'NUMBER_BELOW' ? (source.normalFrom ?? 1) : (source.options?.length ?? 1);
    this.patch({ score: { ...f.score, rules: [...f.score.rules, { kind, fieldKey: source.key, threshold, points: 1 }] } });
  }

  protected patchRule(index: number, p: Partial<EmrFormScoreRule>): void {
    const f = this.field();
    if (f?.score) {
      this.patch({ score: { ...f.score, rules: f.score.rules.map((r, i) => (i === index ? { ...r, ...p } : r)) } });
    }
  }

  protected removeRule(index: number): void {
    const f = this.field();
    if (f?.score) {
      this.patch({ score: { ...f.score, rules: f.score.rules.filter((_, i) => i !== index) } });
    }
  }

  protected onRuleField(index: number, event: Event): void {
    this.patchRule(index, { fieldKey: (event.target as HTMLSelectElement).value });
  }

  protected onRuleNumber(index: number, key: 'threshold' | 'points', event: Event): void {
    const n = this.num(event);
    if (n !== null) {
      this.patchRule(index, { [key]: key === 'points' ? Math.round(Math.min(100, Math.max(-100, n))) : n });
    }
  }

  protected ruleText(rule: EmrFormScoreRule): string {
    const name = this.store.fields().find((f) => f.key === rule.fieldKey)?.label ?? '?';
    const sign = rule.points >= 0 ? '+' : '';
    return rule.kind === 'CHOSEN_COUNT_BELOW'
      ? `${sign}${rule.points} if ${name} has fewer than ${rule.threshold} ticked`
      : `${sign}${rule.points} if ${name} is below ${rule.threshold}`;
  }

  // ---- bands ----

  protected onBandSource(event: Event): void {
    const f = this.field();
    if (f?.band) {
      this.patch({ band: { ...f.band, sourceFieldKey: (event.target as HTMLSelectElement).value } });
    }
  }

  protected patchBand(index: number, p: Partial<{ from: number; label: string }>): void {
    const f = this.field();
    if (f?.band) {
      this.patch({ band: { ...f.band, bands: f.band.bands.map((b, i) => (i === index ? { ...b, ...p } : b)) } });
    }
  }

  protected onBandFrom(index: number, event: Event): void {
    const n = this.num(event);
    if (n !== null) {
      this.patchBand(index, { from: n });
    }
  }

  protected onBandLabel(index: number, event: Event): void {
    this.patchBand(index, { label: this.text(event) });
  }

  protected addBand(): void {
    const f = this.field();
    if (f?.band && f.band.bands.length < BANDS_MAX) {
      const last = f.band.bands[f.band.bands.length - 1];
      this.patch({ band: { ...f.band, bands: [...f.band.bands, { from: (last?.from ?? 0) + 1, label: 'Band' }] } });
    }
  }

  protected removeBand(index: number): void {
    const f = this.field();
    if (f?.band && f.band.bands.length > 1) {
      this.patch({ band: { ...f.band, bands: f.band.bands.filter((_, i) => i !== index) } });
    }
  }
}
