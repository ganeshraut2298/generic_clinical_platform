import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { EmrLayoutSection } from '../emr.model';
import { SectionCardComponent } from '../record/section-card.component';
import { EmrRecordStore } from '../record/emr-record.store';
import { GmToolsComponent } from './gm/gm-tools.component';
import { chromeOf } from './specialty-registry';
import { SpecialtyHostComponent } from './specialty-host.component';

/** Titles of the cards the design puts in Quick tools (SAH-75-1; the library names differ). */
const TOOL_TITLES: Readonly<Record<string, string>> = {
  wtdose: 'Dose by weight',
  feeding: 'Feeding',
  hep: 'Home exercises',
  goals: 'Goals · tap when achieved',
};

/**
 * The "Quick tools" tab, "Helpers for this visit" (SAH-75-1, ADR-026 D1). What it holds is the registry's
 * decision per profile: General Medicine the visit templates and the trend; Paediatrics "Dose by weight"
 * and "Feeding"; Physiotherapy "Home exercises (n)" and "Goals · tap when achieved"; Obstetrics in ANC mode
 * "Due now", "PCPNDT Form F" and "Supplements", in Gynae mode "Quick orders". A moved card is still the
 * layout's section: hidden in the layout, it is not here either.
 */
@Component({
  selector: 'app-quick-tools',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [GmToolsComponent, SectionCardComponent, SpecialtyHostComponent],
  template: `
    @switch (kind()) {
      @case ('gm') {
        <app-gm-tools />
      }
      @case ('og') {
        @if (ogSection(); as section) {
          <app-specialty-host [section]="section" area="tools" />
        } @else {
          <p class="faint t-xs" data-testid="tools-empty">Nothing to show for this visit.</p>
        }
      }
      @default {
        @for (section of moved(); track section.key) {
          <app-section-card [section]="section" [title]="titleOf(section)" />
        } @empty {
          <p class="faint t-xs" data-testid="tools-empty">Nothing to show for this visit.</p>
        }
      }
    }
  `,
})
export class QuickToolsComponent {
  private readonly store = inject(EmrRecordStore);
  protected readonly chrome = computed(() => chromeOf(this.store.profile()));
  protected readonly kind = computed(() => this.chrome().quickTools?.kind ?? null);
  /** The cards of this profile that are in the layout and shown. */
  protected readonly moved = computed<EmrLayoutSection[]>(() => {
    const keys = this.chrome().quickTools?.sectionKeys ?? [];
    return this.store.visibleLayout().filter((s) => keys.includes(s.key));
  });
  /** The ANC editor (ANC mode) or the Gynae examination (Gynae mode), when the layout shows it. */
  protected readonly ogSection = computed<EmrLayoutSection | null>(() => {
    const key = this.store.visitMode() === 'GYNAE' ? 'gyn_exam' : 'anc';
    return this.store.visibleLayout().find((s) => s.key === key) ?? null;
  });

  protected titleOf(section: EmrLayoutSection): string {
    if (section.key === 'hep') {
      const value = this.store.specialtyValue('hep');
      const n = value && value.component === 'HOME_EXERCISES' ? value.items.length : 0;
      return `Home exercises (${n})`;
    }
    return TOOL_TITLES[section.key] ?? section.name;
  }
}
