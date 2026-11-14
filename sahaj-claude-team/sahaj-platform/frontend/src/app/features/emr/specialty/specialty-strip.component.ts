import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { OncJourneyStripComponent } from './onco/onc-journey-strip.component';
import { ALERT_TEXT, RESPONSE_LABEL } from './onco.logic';
import { SpecialtyContext } from './specialty.context';
import { PROFILE_CHROME } from './specialty-registry';
import { headerTags } from './dental/dental-medhistory.logic';
import { EmrScreenMode, SpDentalMedHistory } from './specialty.model';

export const DOB_NOT_RECORDED = "Date of birth not recorded. Add it in Patients to see due vaccines and the child's age.";

/**
 * The summary strip of a specialty screen under the patient header (mockup header tags and the visit
 * mode switch): Dental "Estimate signed", Physiotherapy "Session n
 * of m", Oncology the cycle, the response and "Patient alerts (n)"; and the
 * mode switch of Obstetrics ("This visit is") and Physiotherapy. Text as well as colour.
 */
@Component({
  selector: 'app-specialty-strip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [OncJourneyStripComponent],
  styleUrl: './specialty.shared.scss',
  template: `
    @if (ctx.profile()) {
      <div class="strip surf" data-testid="specialty-strip">
        @if (modes(); as m) {
          <div class="sp-row">
            <span class="sp-lbl">{{ m.label }}</span>
            <div class="seg" role="group" [attr.aria-label]="m.label">
              @for (o of m.options; track o.key) {
                <button type="button" [attr.aria-pressed]="ctx.mode() === o.key" [disabled]="ctx.readOnly()" [attr.data-mode]="o.key" (click)="setMode(o.key)">{{ o.label }}</button>
              }
            </div>
            @if (m.hint) {
              <span class="sp-sub">{{ m.hint }}</span>
            }
          </div>
        }
        @if (ctx.profile() === 'onco') {
          <app-onc-journey />
        }
        @for (n of notes(); track n) {
          <p class="sp-sub" data-testid="sp-note">{{ n }}</p>
        }
        @if (tags().length > 0) {
          <div class="sp-row" aria-label="Summary">
            @for (t of tags(); track t.text) {
              <span class="pill" [class.p-crit]="t.tone === 'crit'" [class.p-good]="t.tone === 'good'" [class.p-warn]="t.tone === 'warn'" [class.p-teal]="t.tone === 'info'">{{ t.text }}</span>
            }
          </div>
        }
        @if (segments().length > 0) {
          <div class="segs" role="img" [attr.aria-label]="segmentsLabel()" data-testid="package-bar">
            @for (g of segments(); track $index) {
              <span class="seg-i" [attr.data-state]="g"></span>
            }
          </div>
        }
        @if (alerts().length > 0) {
          <details class="alerts" data-testid="patient-alerts">
            <summary>Patient alerts ({{ alerts().length }})</summary>
            <ul>
              @for (a of alerts(); track a) {
                <li>{{ a }}</li>
              }
            </ul>
          </details>
        }
      </div>
    }
  `,
  styles: [
    `
      .strip {
        display: flex;
        flex-direction: column;
        gap: 8px;
        padding: 8px 14px;
      }
      .segs {
        display: flex;
        gap: 3px;
      }
      .seg-i {
        flex: 1;
        height: 6px;
        border-radius: 3px;
        background: var(--line);
      }
      .seg-i[data-state='done'] {
        background: var(--good);
      }
      .seg-i[data-state='today'] {
        background: var(--pri);
      }
      .alerts summary {
        min-height: 44px;
        display: flex;
        align-items: center;
        cursor: pointer;
        font-weight: 700;
        color: var(--crit);
      }
      .alerts ul {
        margin: 0;
        padding-left: 18px;
      }
    `,
  ],
})
export class SpecialtyStripComponent {
  protected readonly ctx = inject(SpecialtyContext);

  protected readonly modes = computed(() => {
    const profile = this.ctx.profile();
    return profile ? PROFILE_CHROME[profile].modes : undefined;
  });

  protected setMode(mode: EmrScreenMode): void {
    this.ctx.setMode(mode);
  }

  /** Physiotherapy: a bar of m segments, the sessions done green, today in the accent colour, the rest grey (P-7). */
  protected readonly segments = computed<('done' | 'today' | 'later')[]>(() => {
    const p = this.ctx.profile() === 'physio' ? this.ctx.view()?.physio : null;
    const m = p?.sessionsPlanned ?? 0;
    const n = p?.sessionNo ?? 0;
    return m > 0 && n > 0 ? Array.from({ length: m }, (_, i) => (i < n - 1 ? 'done' : i === n - 1 ? 'today' : 'later')) : [];
  });
  protected readonly segmentsLabel = computed(() => {
    const p = this.ctx.view()?.physio;
    return `Session ${p?.sessionNo ?? 0} of ${p?.sessionsPlanned ?? 0}`;
  });

  protected readonly alerts = computed(() => (this.ctx.onco()?.alerts ?? []).map((a) => ALERT_TEXT[a]));

  /** Paediatrics without a date of birth (BR73-14, SO-73-6): plain line, no link or edit in the EMR. */
  protected readonly notes = computed<string[]>(() =>
    this.ctx.profile() === 'paeds' && this.ctx.view()?.paeds?.dateOfBirthRecorded === false ? [DOB_NOT_RECORDED] : [],
  );
  protected readonly tags = computed<{ text: string; tone: 'crit' | 'good' | 'warn' | 'info' }[]>(() => {
    const v = this.ctx.view();
    const out: { text: string; tone: 'crit' | 'good' | 'warn' | 'info' }[] = [];
    switch (this.ctx.profile()) {
      case 'dental':
        if (v?.dental?.estimateState === 'SIGNED') {
          out.push({ text: 'Estimate signed', tone: 'good' });
        }
        {
          // The header tags are built from the chips of "Medical history and alerts" (SAH-75-6).
          const key = this.ctx.sectionKeyOf('DENTAL_MED_HISTORY');
          const tags = headerTags(key ? this.ctx.value<SpDentalMedHistory>(key, 'DENTAL_MED_HISTORY') : null);
          if (tags.allergy) {
            out.push({ text: tags.allergy, tone: 'crit' });
          }
          if (tags.medical) {
            out.push({ text: tags.medical, tone: 'warn' });
          }
        }
        break;
      case 'og':
        // Header risk tags of the design (O7): "High risk: previous LSCS", the anaemia grade with Hb, "BP high today". Husband and blood group have no source (H5).
        for (const chip of v?.og?.riskChips ?? []) {
          out.push({ text: chip.label, tone: chip.alert ? 'crit' : 'warn' });
        }
        break;
      case 'physio': {
        const p = v?.physio;
        if (p?.sessionNo) {
          out.push({ text: `Session ${p.sessionNo}${p.sessionsPlanned ? ` of ${p.sessionsPlanned}` : ''}`, tone: 'info' });
        }
        if (p?.packageName) {
          out.push({ text: p.packageName, tone: 'info' });
        }
        break;
      }
      case 'onco': {
        const o = this.ctx.onco();
        if (this.ctx.view()?.onco?.journey) {
          break;
        }
        if (o?.cycleNo) {
          out.push({ text: `Cycle ${o.cycleNo} · day 1`, tone: 'info' });
        }
        if (o?.response) {
          out.push({ text: RESPONSE_LABEL[o.response], tone: o.response === 'PROGRESSIVE_DISEASE' ? 'crit' : 'good' });
        }
        break;
      }
      default:
        break;
    }
    return out;
  });
}
