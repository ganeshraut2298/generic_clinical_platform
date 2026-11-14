import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { ALERT_TEXT, RESPONSE_LABEL } from './onco.logic';
import { SpecialtyContext } from './specialty.context';
import { PROFILE_CHROME } from './specialty-registry';
import { EmrScreenMode } from './specialty.model';

export const DOB_NOT_RECORDED = "Date of birth not recorded. Add it in Patients to see due vaccines and the child's age.";

/**
 * The summary strip of a specialty screen under the patient header (mockup header tags and the visit
 * mode switch): Dental "Estimate signed", Paediatrics "n vaccine(s) pending", Physiotherapy "Session n
 * of m", Surgery the payer chip, Oncology the cycle, the response and "Patient alerts (n)"; and the
 * mode switch of Obstetrics ("This visit is") and Physiotherapy. Text as well as colour.
 */
@Component({
  selector: 'app-specialty-strip',
  changeDetection: ChangeDetectionStrategy.OnPush,
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
        break;
      case 'paeds': {
        const pending = (v?.paeds?.dueTodayCount ?? 0) + (v?.paeds?.overdueCount ?? 0);
        if (v?.paeds) {
          out.push(pending > 0 ? { text: `${pending} vaccine(s) pending`, tone: 'crit' } : { text: 'Vaccines up to date', tone: 'good' });
        }
        break;
      }
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
      case 'surgery':
        if (v?.surgery?.payerChip) {
          out.push({ text: v.surgery.payerChip, tone: v.surgery.payerChip.includes('pending') ? 'warn' : 'good' });
        }
        break;
      case 'onco': {
        const o = this.ctx.onco();
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
