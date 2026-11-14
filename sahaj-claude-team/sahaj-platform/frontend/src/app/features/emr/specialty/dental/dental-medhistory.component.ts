import { ChangeDetectionStrategy, Component, computed } from '@angular/core';
import { chipText } from '../../components/chips.logic';
import { SpEditor } from '../shared/sp-editor';
import { DentalMedAllergy, DentalMedCondition, DentalMedMedicine, SpDentalMedHistory } from '../specialty.model';
import { ALLERGIES, CONDITIONS, MEDICINES, MedChip, blankMedHistory, medicalAlerts, toggleGroup } from './dental-medhistory.logic';

/**
 * DENTAL_MED_HISTORY (SAH-75-6, ADR-026 D6; tagged): "Medical history and alerts" / "Ask before any injection,
 * extraction or surgery". Three groups of round chips (many at once; "None" clears the others and any other chip
 * clears "None"; a chosen chip other than "None" is red, with "✓ " before it). "Mark as reviewed" at the top right
 * becomes "✓ Reviewed with patient" (green); pressing it again takes the review back; until then the card border is
 * red. The review is the section's Done and its stamp (who and when) comes from the server. The alert banners are the
 * server's (the same rules run here while the chips are unsaved); they warn and never block. Clinical data: never logged.
 */
@Component({
  selector: 'app-sp-dental-medhistory',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: '../specialty.shared.scss',
  template: `
    <div class="sp med" [class.unreviewed]="!val().reviewedWithPatient" data-testid="med-card">
      <div class="sp-row top">
        <span class="sp-sub">Ask before any injection, extraction or surgery</span>
        @if (!disabled() || val().reviewedWithPatient) {
          <button
            type="button"
            class="rev"
            [class.done]="val().reviewedWithPatient"
            [disabled]="disabled()"
            [attr.aria-pressed]="val().reviewedWithPatient"
            data-act="review"
            (click)="toggleReview()"
          >
            {{ val().reviewedWithPatient ? '✓ Reviewed with patient' : 'Mark as reviewed' }}
          </button>
        }
      </div>
      @if (reviewLine(); as line) {
        <p class="sp-sub" data-testid="review-line">{{ line }}</p>
      }

      <span class="sp-lbl">Conditions</span>
      <div class="sp-row" role="group" aria-label="Conditions">
        @for (c of conditions; track c.key) {
          <button type="button" class="chip" [class.on]="has(val().conditions, c.key)" [class.bad]="has(val().conditions, c.key) && !c.isNone" [disabled]="disabled()" [attr.aria-pressed]="has(val().conditions, c.key)" (click)="setConditions(c.key)">
            {{ text(c, val().conditions) }}
          </button>
        }
      </div>
      <span class="sp-lbl">Medicines</span>
      <div class="sp-row" role="group" aria-label="Medicines">
        @for (c of medicines; track c.key) {
          <button type="button" class="chip" [class.on]="has(val().medicines, c.key)" [class.bad]="has(val().medicines, c.key) && !c.isNone" [disabled]="disabled()" [attr.aria-pressed]="has(val().medicines, c.key)" (click)="setMedicines(c.key)">
            {{ text(c, val().medicines) }}
          </button>
        }
      </div>
      <span class="sp-lbl">Allergies</span>
      <div class="sp-row" role="group" aria-label="Allergies">
        @for (c of allergies; track c.key) {
          <button type="button" class="chip" [class.on]="has(val().allergies, c.key)" [class.bad]="has(val().allergies, c.key) && !c.isNone" [disabled]="disabled()" [attr.aria-pressed]="has(val().allergies, c.key)" (click)="setAllergies(c.key)">
            {{ text(c, val().allergies) }}
          </button>
        }
      </div>

      @for (a of alerts(); track a.key) {
        <div class="msg crit" role="status" data-testid="med-alert">
          <div>
            <b>{{ a.title }}</b> <span>{{ a.text }}</span>
          </div>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .med {
        border: 2px solid var(--line);
        border-radius: var(--r-sm);
        padding: 10px;
      }
      .med.unreviewed {
        border-color: var(--crit);
      }
      .top {
        justify-content: space-between;
      }
      .rev {
        min-height: 44px;
        padding: 0 14px;
        border: 1px solid var(--pri);
        border-radius: var(--r-sm);
        background: var(--surface);
        color: var(--pri-text);
        font-weight: 700;
      }
      .rev.done {
        border-color: var(--good);
        background: var(--good-soft);
        color: var(--good);
      }
    `,
  ],
})
export class DentalMedHistoryComponent extends SpEditor<SpDentalMedHistory> {
  protected readonly component = 'DENTAL_MED_HISTORY' as const;
  protected blank(): SpDentalMedHistory {
    return blankMedHistory();
  }

  protected readonly conditions = CONDITIONS;
  protected readonly medicines = MEDICINES;
  protected readonly allergies = ALLERGIES;

  /** The tobacco habits of "Dental history and habits" in this visit, for the banner while nothing is saved. */
  private readonly habits = computed<string[]>(() => {
    const raw = this.ctx.store.values()['dental_hx']?.['habits'];
    return Array.isArray(raw) ? (raw as string[]) : [];
  });

  /** The server's banners while nothing here is unsaved, else the same rules on the chips on screen. */
  protected readonly alerts = computed(() => {
    const server = this.view()?.dental?.medicalAlerts;
    return this.fresh() && server ? server : medicalAlerts(this.val(), this.habits());
  });

  protected readonly reviewLine = computed(() => {
    const review = this.val().review;
    return this.val().reviewedWithPatient && review?.byName ? `Reviewed by ${review.byName}` : null;
  });

  protected has<K extends string>(list: readonly K[], key: K): boolean {
    return list.includes(key);
  }

  protected text<K extends string>(chip: MedChip<K>, list: readonly K[]): string {
    return chipText(chip.label, list.includes(chip.key));
  }

  /** A chip tap; "None" clears the others of its group and any other chip clears "None". */
  protected setConditions(key: DentalMedCondition): void {
    this.patch({ conditions: toggleGroup(CONDITIONS, this.val().conditions, key) });
  }
  protected setMedicines(key: DentalMedMedicine): void {
    this.patch({ medicines: toggleGroup(MEDICINES, this.val().medicines, key) });
  }
  protected setAllergies(key: DentalMedAllergy): void {
    this.patch({ allergies: toggleGroup(ALLERGIES, this.val().allergies, key) });
  }

  protected toggleReview(): void {
    this.patch({ reviewedWithPatient: !this.val().reviewedWithPatient });
  }
}
