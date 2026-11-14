import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { noAllergyText } from '../../record/allergy-chip.logic';
import { SpecialtyContext } from '../specialty.context';
import { OncJourneyDialogComponent, OncJourneyDialogData } from './onc-journey-dialog.component';
import {
  CYCLE_TIP,
  NO_PLAN,
  NOT_RECORDED,
  biologyCell,
  cycleText,
  diagnosisCell,
  doxorubicinChip,
  intentCell,
  responseCell,
} from './onc-journey.logic';

/**
 * "Cancer journey snapshot" under the patient line of the Oncology screen (SAH-75-7): Diagnosis, Biology,
 * Intent · line, the plan with one chip per cycle, and the clinical response; and the watch chip "Doxorubicin
 * <given> / 450 mg/m²". The server sends the whole strip on every read and save, so it is never half filled.
 * Stage, biology, intent, line and board date come from the patient's Cancer journey record; "Edit" opens its
 * form (ours, OQ-75-7). The scheme balance chip has no data source and is not drawn (OQ-75-8). Clinical data:
 * never logged.
 */
@Component({
  selector: 'app-onc-journey',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule],
  template: `
    @if (journey(); as j) {
      <section class="jr surf" aria-label="Cancer journey snapshot" data-testid="journey-strip">
        @if (watch() || noAllergy()) {
          <div class="watch" data-testid="watch-chips">
            @if (watch(); as w) {
              <span class="chip amber" data-testid="dox-chip">{{ w }}</span>
            }
            @if (noAllergy(); as na) {
              <span class="chip grey" data-testid="no-allergy-chip">{{ na }}</span>
            }
          </div>
        }
        <div class="cells">
          <div class="cell">
            <span class="cl">{{ diagnosis().label }}</span>
            <b>{{ diagnosis().line1 }}</b>
            @if (diagnosis().line2; as l) {
              <span class="accent">{{ l }}</span>
            }
          </div>
          <div class="cell">
            <span class="cl">{{ biology().label }}</span>
            <b>{{ biology().line1 }}</b>
            @if (biology().line2; as l) {
              <span class="sub">{{ l }}</span>
            }
          </div>
          <div class="cell">
            <span class="cl">{{ intent().label }}</span>
            <b>{{ intent().line1 }}</b>
            @if (intent().line2; as l) {
              <span class="sub">{{ l }}</span>
            }
          </div>
          <div class="cell plan">
            @if (j.plan; as plan) {
              <div class="ph">
                <span class="cl">Plan · {{ plan.title }}</span>
                <b class="ct" data-testid="cycle-text">{{ cycleLine() }}</b>
              </div>
              <div class="cycles">
                @for (c of plan.cycles; track c.sittingId) {
                  <span class="cy" [attr.data-state]="c.state" [title]="tip(c.state)">{{ c.label }}</span>
                }
              </div>
            } @else {
              <span class="cl">Plan</span>
              <b data-testid="no-plan">{{ noPlan }}</b>
            }
          </div>
          <div class="cell">
            <span class="cl">Response (clinical)</span>
            <b [attr.data-tone]="response().tone" data-testid="response-cell">{{ response().line1 }}</b>
            @if (response().line2; as l) {
              <span class="sub">{{ l }}</span>
            }
          </div>
        </div>
        @if (canEdit()) {
          <button type="button" mat-stroked-button class="edit" data-act="edit-journey" (click)="edit()">Edit</button>
        }
      </section>
    }
  `,
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
      }
      .jr {
        display: flex;
        flex-direction: column;
        gap: 8px;
        padding: 10px 14px;
      }
      .watch {
        display: flex;
        gap: 8px;
        flex-wrap: wrap;
        justify-content: flex-end;
      }
      .chip {
        font-size: 0.78rem;
        font-weight: 700;
        border-radius: 8px;
        padding: 6px 10px;
      }
      .chip.amber {
        background: var(--warn-soft);
        color: var(--warn);
      }
      .chip.grey {
        background: var(--muted-soft);
        color: var(--ink-2);
      }
      .cells {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
        gap: 1px;
        background: var(--line);
        border: 1px solid var(--line);
        border-radius: 12px;
        overflow: hidden;
      }
      .cell {
        background: var(--surface);
        padding: 10px 14px;
        display: flex;
        flex-direction: column;
        gap: 3px;
        min-width: 0;
      }
      .cell.plan {
        grid-column: span 2;
        gap: 6px;
      }
      .cl {
        font-size: 0.7rem;
        font-weight: 600;
        color: var(--ink-3);
        text-transform: uppercase;
        letter-spacing: 0.04em;
      }
      .ph {
        display: flex;
        justify-content: space-between;
        gap: 8px;
        flex-wrap: wrap;
      }
      .ct {
        font-size: 0.78rem;
      }
      .accent {
        color: var(--pri);
        font-weight: 700;
        font-size: 0.78rem;
      }
      .sub {
        color: var(--ink-3);
        font-size: 0.78rem;
      }
      .cycles {
        display: flex;
        gap: 4px;
        flex-wrap: wrap;
      }
      .cy {
        flex: 1 1 36px;
        text-align: center;
        font-size: 0.72rem;
        font-weight: 700;
        border-radius: 6px;
        padding: 5px 0;
        background: var(--muted-soft);
        color: var(--ink-2);
      }
      .cy[data-state='GIVEN'] {
        background: var(--good);
        color: var(--crit-ink);
      }
      .cy[data-state='TODAY'] {
        background: var(--pri);
        color: var(--pri-ink);
        box-shadow: 0 0 0 2px var(--pri-soft);
      }
      .cy[data-state='DELAYED'] {
        background: var(--warn);
        color: var(--crit-ink);
      }
      .cy[data-state='MISSED'] {
        background: var(--crit);
        color: var(--crit-ink);
      }
      [data-tone='good'] {
        color: var(--good);
      }
      [data-tone='warn'] {
        color: var(--warn);
      }
      [data-tone='crit'] {
        color: var(--crit);
      }
      .edit {
        align-self: flex-start;
        min-height: 44px;
      }
      @media (max-width: 720px) {
        .cell.plan {
          grid-column: span 1;
        }
      }
    `,
  ],
})
export class OncJourneyStripComponent {
  private readonly ctx = inject(SpecialtyContext);
  private readonly dialog = inject(MatDialog);

  protected readonly noPlan = NO_PLAN;
  protected readonly notRecorded = NOT_RECORDED;
  private readonly onco = computed(() => this.ctx.view()?.onco ?? null);
  /** The strip as the server sent it; null only before the record has loaded or from an older server. */
  protected readonly journey = computed(() => this.onco()?.journey ?? null);
  protected readonly diagnosis = computed(() => diagnosisCell(this.journey()?.diagnosisText ?? null, this.journey()?.record ?? null));
  protected readonly biology = computed(() => biologyCell(this.journey()?.record ?? null));
  protected readonly intent = computed(() => intentCell(this.journey()?.record ?? null));
  protected readonly response = computed(() => responseCell(this.onco()));
  protected readonly watch = computed(() => doxorubicinChip(this.onco()?.watch));
  /** "No known drug allergy" (grey), only when the patient record says none (ADR-026 D12). */
  protected readonly noAllergy = computed(() => noAllergyText('onco', this.ctx.store.record()?.header.allergyStatus));
  protected readonly cycleLine = computed(() => {
    const plan = this.journey()?.plan;
    return plan ? cycleText(plan) : '';
  });
  /** The journey is the doctor's to edit while the visit is a draft. */
  protected readonly canEdit = computed(() => !this.ctx.readOnly());

  protected tip(state: keyof typeof CYCLE_TIP): string {
    return CYCLE_TIP[state];
  }

  protected edit(): void {
    const recordId = this.ctx.store.recordId();
    if (!recordId) {
      return;
    }
    this.dialog
      .open<OncJourneyDialogComponent, OncJourneyDialogData, boolean>(OncJourneyDialogComponent, {
        panelClass: 'sheet-panel',
        width: '560px',
        maxWidth: '100vw',
        ariaLabelledBy: 'journey-title',
        data: { recordId, current: this.journey()?.record ?? null },
      })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.ctx.store.refreshRecord(true);
        }
      });
  }
}
