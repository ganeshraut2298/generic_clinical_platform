import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSliderModule } from '@angular/material/slider';
import { RouterLink } from '@angular/router';
import { formatIndian, formatInr } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { SectionHeadComponent } from '../shared/section-head.component';
import {
  CalcState,
  SliderKey,
  applyPreset,
  calcNums,
  cycleFeature,
  headline,
  initialCalcState,
  levelOf,
  parseAmount,
  setNumber,
} from './calculator';
import {
  COMPARE_FEATURES,
  CompareFeature,
  FeatureKey,
  LEVEL_LABELS,
  PRESETS,
  PRESET_KEYS,
  PresetKey,
} from './compare.data';

interface SliderDef {
  key: SliderKey;
  label: string;
  min: number;
  max: number;
  step: number;
  /** Text written after the value, for example "%". */
  suffix?: string;
  /** Text written before the value, for example "₹". */
  prefix?: string;
}

const MAIN_SLIDERS: SliderDef[] = [
  { key: 'doctors', label: 'Doctors', min: 1, max: 6, step: 1 },
  { key: 'ppd', label: 'Patients a day', min: 10, max: 150, step: 5 },
];

const ASSUMPTION_SLIDERS: SliderDef[] = [
  { key: 'fuShare', label: 'Visits that need a follow-up', min: 5, max: 60, step: 5, suffix: '%' },
  { key: 'fuMiss', label: 'Follow-ups that never come back', min: 5, max: 60, step: 5, suffix: '%' },
  { key: 'recover', label: 'Of those, brought back by reminders', min: 5, max: 60, step: 5, suffix: '%' },
  { key: 'avgBill', label: 'Average bill on a follow-up visit', min: 0, max: 1500, step: 50, prefix: '₹' },
  { key: 'minsNow', label: 'Writing time per patient today', min: 1, max: 8, step: 0.5, suffix: ' min' },
  { key: 'minsApp', label: 'With stylus or voice', min: 0.5, max: 4, step: 0.5, suffix: ' min' },
];

/** Section 7: Compare. What you use today, against Sahaj, with estimates you can change. */
@Component({
  selector: 'app-compare-section',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSliderModule,
    RouterLink,
    IconComponent,
    SectionHeadComponent,
  ],
  styleUrl: './compare.component.scss',
  templateUrl: './compare.component.html',
})
export class CompareSectionComponent {
  protected readonly presets = PRESETS;
  protected readonly presetKeys = PRESET_KEYS;
  protected readonly mainSliders = MAIN_SLIDERS;
  protected readonly assumptionSliders = ASSUMPTION_SLIDERS;
  protected readonly featureCount = COMPARE_FEATURES.length;
  protected readonly levelLabels = LEVEL_LABELS;
  protected readonly inr = formatInr;
  protected readonly indian = formatIndian;

  /** Table rows with a group heading before the first row of each group. */
  protected readonly rows: { feature: CompareFeature; heading: string | null }[] =
    COMPARE_FEATURES.map((feature, i) => ({
      feature,
      heading: feature.group !== COMPARE_FEATURES[i - 1]?.group ? feature.group : null,
    }));

  readonly state = signal<CalcState>(initialCalcState());
  /** What the visitor sees in the two money boxes. Kept as text so typing is not rewritten. */
  protected readonly spendText = signal(String(this.state().spend));
  protected readonly msgText = signal(String(this.state().msg));

  protected readonly nums = computed(() => calcNums(this.state()));
  protected readonly headline = computed(() => headline(this.state(), this.nums()));
  protected readonly yoursLabel = computed(() => {
    const yours = this.nums().yours;
    return yours % 1 ? yours.toFixed(1) : String(yours);
  });
  protected readonly maxCost = computed(() => Math.max(this.nums().cur3, this.nums().sah3 ?? 0, 1));
  protected readonly gapsShown = computed(() => this.nums().gaps.slice(0, 5));

  protected levelOf(key: FeatureKey): 0 | 1 | 2 {
    return levelOf(this.state(), key);
  }

  protected choosePreset(key: PresetKey): void {
    this.state.update((s) => applyPreset(s, key));
    this.syncMoneyText();
  }

  protected cycle(key: FeatureKey): void {
    this.state.update((s) => cycleFeature(s, key));
  }

  protected setSlider(key: SliderKey, value: number): void {
    this.state.update((s) => setNumber(s, key, value));
    if (key === 'doctors') {
      this.syncMoneyText();
    }
  }

  protected setMoney(key: 'spend' | 'msg', text: string): void {
    (key === 'spend' ? this.spendText : this.msgText).set(text);
    this.state.update((s) => setNumber(s, key, parseAmount(text)));
  }

  protected sliderValue(key: SliderKey): number {
    return this.state()[key];
  }

  protected sliderLabel(def: SliderDef): string {
    return `${def.prefix ?? ''}${this.state()[def.key]}${def.suffix ?? ''}`;
  }

  protected levelClass(level: number): string {
    return `t${level}`;
  }

  private syncMoneyText(): void {
    this.spendText.set(String(this.state().spend));
    this.msgText.set(String(this.state().msg));
  }
}
