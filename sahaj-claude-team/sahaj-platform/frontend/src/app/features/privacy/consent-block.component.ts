import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { ShellStore } from '../../core/shell/shell.store';
import { ConsentInput, PrivacySettings, consentLine } from './privacy.model';

/** The consent boxes a patient form sends with `POST /patients` (SAH-17): RECORDS, and PROMOTIONAL when the clinic allows it. */
export function buildConsents(
  privacy: PrivacySettings | null,
  records: FormControl<boolean>,
  promo: FormControl<boolean>,
): ConsentInput[] {
  const consents: ConsentInput[] = [];
  if (privacy?.consentRequired === true) {
    consents.push({ kind: 'RECORDS', given: records.value });
    if (privacy.promoConsent === true) {
      consents.push({ kind: 'PROMOTIONAL', given: promo.value });
    }
  }
  return consents;
}

/**
 * The clinic's consent line and its checkboxes, shared by the patient form dialog and the front desk
 * console's quick new-patient form. Shows nothing while consent is off or the settings are unknown.
 */
@Component({
  selector: 'app-consent-block',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatCheckboxModule],
  styles: `
    .consent {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding: 10px 12px;
      border-radius: var(--r-md);
      background: var(--pri-soft);
    }
    blockquote {
      margin: 0 0 4px;
      color: var(--pri-text);
      font-size: 0.86rem;
    }
  `,
  template: `
    @if (showRecords()) {
      <div class="consent" data-testid="consent-block">
        <blockquote>"{{ line() }}"</blockquote>
        <mat-checkbox [formControl]="records()">The patient agreed to the consent line</mat-checkbox>
        @if (showPromo()) {
          <mat-checkbox [formControl]="promo()">The patient agreed to promotional messages</mat-checkbox>
        }
      </div>
    }
  `,
})
export class ConsentBlockComponent {
  private readonly store = inject(ShellStore);

  readonly privacy = input<PrivacySettings | null>(null);
  readonly records = input.required<FormControl<boolean>>();
  readonly promo = input.required<FormControl<boolean>>();

  protected readonly line = computed(() => consentLine(this.store.me()?.clinic.name ?? 'this clinic'));
  protected readonly showRecords = computed(() => this.privacy()?.consentRequired === true);
  protected readonly showPromo = computed(() => this.showRecords() && this.privacy()?.promoConsent === true);
}
