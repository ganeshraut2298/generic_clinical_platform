import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { SpecialtyContext } from '../specialty.context';
import { DECISIONS, DEVIATION_REASONS, decisionLabel, factsChangedText, needsReason } from '../onco.logic';
import { OncDecisionKey, OncDeviationReason } from '../specialty.model';

/**
 * "5 · Today's decision" (SAH-65-4; tagged C-8; ADR-023 D5). The suggestion is worked out from the
 * side effects, examination, labs, ECOG and response, and shows with the reasons (tick or cross); the
 * doctor makes the final call. Choosing something other than the suggestion needs a reason, saved with
 * the note. The decision is written by the draft save; the server stores it with the suggestion at
 * that moment, and says "Facts changed. Suggested now: …" when the facts move afterwards. Nothing is
 * sent or ordered by the decision. Confirming opens the order sheet in Treatment.
 */
@Component({
  selector: 'app-onc-decision',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['../specialty.shared.scss', './onco.scss'],
  template: `
    <section class="sp card" aria-labelledby="onc-decision-h">
      <h3 id="onc-decision-h" class="sp-h big">5 · Today’s decision</h3>
      <div class="sp-row">
        <b data-testid="suggested">{{ suggestedText() }}</b>
        <span class="pill" [class.p-good]="!!recorded()" [class.p-wait]="!recorded()" data-testid="decision-badge">
          {{ recorded() ? '✓ Confirmed: ' + label(recorded()) : 'Not confirmed' }}
        </span>
      </div>
      <p class="sp-sub">Worked out from side effects, examination, labs, ECOG and response. You make the final call.</p>
      @if (factsChanged(); as fc) {
        <p class="msg warn" role="status" data-testid="facts-changed">{{ fc }}</p>
      }
      <ul class="reasons" aria-label="Reasons for the suggestion">
        @for (r of reasons(); track r.key) {
          <li [class.bad]="!r.ok"><span aria-hidden="true">{{ r.ok ? '✓' : '✗' }}</span><span class="sr-only">{{ r.ok ? 'OK:' : 'Needs a look:' }}</span> {{ r.text }}</li>
        }
      </ul>
      <div class="grid5" role="group" aria-label="Decision">
        @for (d of decisions; track d.key) {
          <button type="button" class="tile" [class.on]="chosen() === d.key" [disabled]="disabled()" [attr.aria-pressed]="chosen() === d.key" (click)="choose(d.key)">
            <b>{{ d.label }}</b><span class="sp-sub">{{ d.sub }}</span>
            @if (suggestion() === d.key) {
              <span class="pill p-teal">Suggested</span>
            }
          </button>
        }
      </div>
      @if (deviates()) {
        <p class="sp-lbl">You chose differently from the suggestion. Pick a reason (saved with the note):</p>
        <div class="sp-row" role="group" aria-label="Reason">
          @for (r of reasonsList; track r.key) {
            <button type="button" class="chip sq" [class.on]="reason() === r.key" [disabled]="disabled()" (click)="pickReason(r.key)">{{ r.label }}</button>
          }
        </div>
      }
      <p class="sp-sub" data-testid="recap">{{ recap() }}</p>
      @if (error(); as e) {
        <p class="msg crit" role="alert">{{ e }}</p>
      }
      <div class="sp-row">
        <button type="button" class="btn pri" data-act="confirm-decision" [disabled]="!canConfirm() || busy()" (click)="confirm()">{{ confirmLabel() }}</button>
        <span class="sp-sub">Nothing is ordered or sent by the decision.</span>
      </div>
    </section>
  `,
  styles: [
    `
      .card {
        padding: 12px 14px;
        border-radius: var(--r);
        border: 1px solid var(--glass-edge);
        background: var(--glass-hi);
        box-shadow: var(--shadow-sm);
      }
      .big {
        font-size: 1rem;
        color: var(--ink);
      }
      .reasons {
        display: flex;
        flex-direction: column;
        gap: 2px;
        margin: 0;
        padding: 0;
        list-style: none;
        font-size: 0.8125rem;
      }
      .reasons .bad {
        color: var(--crit);
        font-weight: 600;
      }
    `,
  ],
})
export class OncDecisionComponent {
  private readonly ctx = inject(SpecialtyContext);
  protected readonly disabled = this.ctx.readOnly;
  private readonly onco = this.ctx.onco;
  protected readonly decisions = DECISIONS;
  protected readonly reasonsList = DEVIATION_REASONS;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  /** The decision the doctor has picked on screen, else the one the server recorded. */
  private readonly screenDecision = computed(() => this.ctx.store.screen().oncDecision);
  protected readonly chosen = computed<OncDecisionKey | null>(() => this.screenDecision()?.choice ?? this.onco()?.decision?.choice ?? null);
  protected readonly reason = computed<OncDeviationReason | null>(() => this.screenDecision()?.reasonKey ?? this.onco()?.decision?.reasonKey ?? null);
  protected readonly suggestion = computed(() => this.onco()?.suggestion ?? null);
  /** Recorded means the server has it (saved); a pick on screen is confirmed only after the save. */
  protected readonly recorded = computed<OncDecisionKey | null>(() => (this.ctx.store.dirty() ? null : (this.onco()?.decision?.choice ?? null)));
  protected readonly reasons = computed(() => this.onco()?.reasons ?? []);
  protected readonly deviates = computed(() => needsReason(this.chosen(), this.suggestion()));
  protected readonly factsChanged = computed(() => {
    const o = this.onco();
    return o && !this.ctx.store.dirty() ? factsChangedText(o) : null;
  });
  protected readonly suggestedText = computed(() => (this.suggestion() ? `Suggested: ${decisionLabel(this.suggestion())}` : 'Suggested: choose the ECOG grade first'));
  protected readonly canConfirm = computed(() => !this.disabled() && !!this.chosen() && (!this.deviates() || !!this.reason()) && !!this.suggestion());
  protected readonly confirmLabel = computed(() =>
    this.recorded() ? 'Open order sheet →' : this.chosen() ? `Confirm “${decisionLabel(this.chosen())}” →` : 'Pick a decision',
  );
  protected readonly recap = computed(() => {
    const o = this.onco();
    return o ? `${o.ecogText ?? 'ECOG not chosen'} · worst grade ${o.worstGrade ?? 0} · ${o.response ? o.response.toLowerCase().replace(/_/g, ' ') : 'response not measured'}` : '';
  });

  protected label(key: OncDecisionKey | null): string {
    return decisionLabel(key);
  }

  protected choose(choice: OncDecisionKey): void {
    this.error.set(null);
    this.ctx.store.setOncDecision(choice, choice === this.suggestion() ? null : this.reason());
  }

  protected pickReason(reason: OncDeviationReason): void {
    const choice = this.chosen();
    if (choice) {
      this.ctx.store.setOncDecision(choice, reason);
    }
  }

  protected confirm(): void {
    if (this.recorded()) {
      this.ctx.store.setPart('TREATMENT');
      return;
    }
    const choice = this.chosen();
    if (!choice) {
      return;
    }
    this.ctx.store.setOncDecision(choice, this.deviates() ? this.reason() : null);
    this.busy.set(true);
    this.ctx.store.save().subscribe((ok) => {
      this.busy.set(false);
      if (ok) {
        this.ctx.store.setPart('TREATMENT');
      } else if (!this.ctx.store.saveError()) {
        this.error.set('We could not record the decision. Try again.');
      }
    });
  }

}
