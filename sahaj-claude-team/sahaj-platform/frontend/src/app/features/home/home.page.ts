import { ChangeDetectionStrategy, Component } from '@angular/core';
import { KineticBackgroundComponent } from '../../shared/ui/kinetic-background/kinetic-background.component';
import { CompareSectionComponent } from './compare/compare.component';
import { FaqSectionComponent } from './faq/faq.component';
import { FinalCtaComponent } from './final-cta/final-cta.component';
import { HomeFooterComponent } from './footer/home-footer.component';
import { HomeHeaderComponent } from './header/home-header.component';
import { HeroComponent } from './hero/hero.component';
import { JourneySectionComponent } from './journey/journey.component';
import { PricingSectionComponent } from './pricing/pricing.component';
import { RolesSectionComponent } from './roles/roles.component';
import { SetupSectionComponent } from './setup/setup.component';
import { SpecialtiesSectionComponent } from './specialties/specialties.component';
import { TrustSectionComponent } from './trust/trust.component';
import { WorkflowSectionComponent } from './workflow/workflow.component';

/**
 * The public home page. Sections appear in the mockup order:
 * hero, Clinic setup, Workflow builder, Roles and responsibilities, Patient journey,
 * Specialties, Compare, Pricing, Trust and control, Questions, final call to action, footer.
 */
@Component({
  selector: 'app-home-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    KineticBackgroundComponent,
    HomeHeaderComponent,
    HeroComponent,
    SetupSectionComponent,
    WorkflowSectionComponent,
    RolesSectionComponent,
    JourneySectionComponent,
    SpecialtiesSectionComponent,
    CompareSectionComponent,
    PricingSectionComponent,
    TrustSectionComponent,
    FaqSectionComponent,
    FinalCtaComponent,
    HomeFooterComponent,
  ],
  styleUrl: './home.page.scss',
  template: `
    <app-kinetic-background />
    <div class="pubwrap">
      <app-home-header />
      <main id="main" tabindex="-1">
        <app-hero />
        <app-setup-section />
        <app-workflow-section />
        <app-roles-section />
        <app-journey-section />
        <app-specialties-section />
        <app-compare-section />
        <app-pricing-section />
        <app-trust-section />
        <app-faq-section />
        <app-final-cta />
      </main>
      <app-home-footer />
    </div>
  `,
})
export class HomePage {}
