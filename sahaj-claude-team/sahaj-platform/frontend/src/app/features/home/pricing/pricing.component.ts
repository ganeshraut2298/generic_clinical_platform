import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { RouterLink } from '@angular/router';
import { InViewDirective } from '../../../core/motion/in-view.directive';
import { runScene } from '../../../core/motion/scene';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EnquirySheet } from '../enquiry/enquiry-sheet.service';
import { SectionHeadComponent } from '../shared/section-head.component';
import { GROWTH, PLANS, PRICING_NOTE, Plan, PlanKey, planPrice } from './pricing.data';

export type BillingPeriod = 'yearly' | 'monthly';

/** Section 8: Pricing. Billing period toggle, four plans, and how a clinic grows. */
@Component({
  selector: 'app-pricing-section',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    RouterLink,
    InViewDirective,
    IconComponent,
    SectionHeadComponent,
  ],
  styleUrl: './pricing.component.scss',
  templateUrl: './pricing.component.html',
})
export class PricingSectionComponent {
  private readonly enquiry = inject(EnquirySheet);

  protected readonly inView = signal(false);
  protected readonly plans = PLANS;
  protected readonly growth = GROWTH;
  protected readonly note = PRICING_NOTE;

  /** Yearly is the default, as in the mockup. */
  readonly period = signal<BillingPeriod>('yearly');
  readonly growIndex = signal(1);
  /** True until the visitor picks a clinic size; then the sizes stop changing by themselves. */
  readonly autoGrow = signal(true);

  protected readonly yearly = computed(() => this.period() === 'yearly');
  protected readonly grow = computed(() => GROWTH[this.growIndex()]);
  protected readonly growPlanNames = computed(() =>
    this.grow()
      .plans.map((key) => this.plans.find((p) => p.key === key)?.name)
      .join(' or '),
  );
  protected readonly branches = computed(() =>
    Array.from({ length: this.grow().branches }, (_, i) => ({
      title: i ? 'Branch 2 · Wakad' : 'Main clinic · Ravet',
      doctors: i ? 3 : this.grow().doctors,
      staff: i ? 3 : this.grow().staff,
    })),
  );

  private timer: ReturnType<typeof setInterval> | undefined;

  constructor() {
    runScene(this.inView, {
      play: () => {
        if (this.autoGrow()) {
          this.timer = setInterval(
            () => this.growIndex.update((i) => (i + 1) % GROWTH.length),
            2400,
          );
        }
      },
      settle: () => undefined,
      stop: () => clearInterval(this.timer),
    });
  }

  protected price(key: PlanKey) {
    return planPrice(key, this.yearly());
  }

  protected matches(plan: Plan): boolean {
    return this.grow().plans.includes(plan.key);
  }

  protected seats(count: number): number[] {
    return Array.from({ length: count }, (_, i) => i);
  }

  protected chooseSize(index: number): void {
    this.autoGrow.set(false);
    clearInterval(this.timer);
    this.growIndex.set(index);
  }

  protected contactSales(): void {
    this.enquiry.open('CONTACT_SALES');
  }
}
