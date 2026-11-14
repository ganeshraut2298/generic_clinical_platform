import { ChangeDetectionStrategy, Component, computed } from '@angular/core';
import { SURGERY_CONSENT_ITEMS } from '../surgery.logic';
import { SpEditor, textOrNull } from './sp-editor';
import { SpConsentChecklist, SpConsentItem, SpDentalPlan } from '../specialty.model';

/**
 * CONSENT_CHECKLIST (SAH-69-2 dental, SAH-71-2 surgery; tagged L-3). Surgery has the five fixed items,
 * none ticked at the start; dental has one item per plan row the doctor adds, with a free form name.
 * The tick is the record (the signature image is Later, OQ-45-28). Done: at least one item and every
 * item ticked.
 */
@Component({
  selector: 'app-sp-consent-checklist',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: '../specialty.shared.scss',
  template: `
    <div class="sp">
      @if (surgery()) {
        <p class="sp-sub" data-testid="consent-count">{{ ticked() }} of {{ items().length }} ticked</p>
      }
      <ul class="plain">
        @for (i of items(); track i.key) {
          <li class="row">
            <label class="chk">
              <input type="checkbox" [checked]="i.confirmed" [disabled]="disabled()" [attr.data-key]="i.key" (change)="tick(i, $event)" />
              <span>{{ label(i) }}</span>
            </label>
            @if (!surgery()) {
              <input class="input" type="text" maxlength="80" [value]="i.formLabel ?? ''" [disabled]="disabled()" [attr.aria-label]="'Consent form for ' + label(i)" placeholder="Consent form name" (change)="setForm(i, $event)" />
              @if (!disabled()) {
                <button type="button" class="link" [attr.aria-label]="'Remove consent for ' + label(i)" (click)="remove(i)">×</button>
              }
            }
            @if (i.confirmedStamp; as s) {
              <span class="sp-sub">Ticked{{ s.byName ? ' by ' + s.byName : '' }}</span>
            }
          </li>
        } @empty {
          <li class="sp-sub">No consent item yet. Add one for a planned treatment.</li>
        }
      </ul>
      @if (!surgery() && !disabled() && addable().length > 0) {
        <div class="sp-row">
          @for (r of addable(); track r.id) {
            <button type="button" class="chip sq" data-act="add-consent" (click)="add(r.id)">+ {{ r.tooth }} · {{ r.procedureText }}</button>
          }
        </div>
      }
      <p class="sp-sub">The signature image is Later. The tick is the record.</p>
    </div>
  `,
  styles: [
    `
      .plain {
        display: flex;
        flex-direction: column;
        gap: 6px;
        margin: 0;
        padding: 0;
        list-style: none;
      }
      .row {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 8px;
      }
      .chk {
        display: inline-flex;
        align-items: center;
        gap: 8px;
        min-height: 44px;
        font-weight: 600;
      }
      .chk input {
        width: 20px;
        height: 20px;
      }
    `,
  ],
})
export class ConsentChecklistComponent extends SpEditor<SpConsentChecklist> {
  protected readonly component = 'CONSENT_CHECKLIST' as const;
  protected blank(): SpConsentChecklist {
    return { component: 'CONSENT_CHECKLIST', schemaVersion: 1, items: [] };
  }

  protected readonly surgery = computed(() => this.ctx.profile() === 'surgery');
  /** Surgery always shows the five fixed items; ticks are stored with the first change. */
  protected readonly items = computed<SpConsentItem[]>(() => {
    const stored = this.val().items;
    if (!this.surgery()) {
      return stored;
    }
    return SURGERY_CONSENT_ITEMS.map((f) => stored.find((s) => s.key === f.key) ?? { key: f.key, confirmed: false });
  });
  protected readonly ticked = computed(() => this.items().filter((i) => i.confirmed).length);
  private readonly plan = computed(() => {
    const key = this.ctx.sectionKeyOf('DENTAL_PLAN');
    return key ? this.ctx.value<SpDentalPlan>(key, 'DENTAL_PLAN') : null;
  });
  protected readonly addable = computed(() => (this.plan()?.rows ?? []).filter((r) => !this.val().items.some((i) => i.key === r.id)));

  protected label(i: SpConsentItem): string {
    if (this.surgery()) {
      return SURGERY_CONSENT_ITEMS.find((f) => f.key === i.key)?.label ?? i.key;
    }
    const row = this.plan()?.rows.find((r) => r.id === i.key);
    return row ? `${row.tooth} · ${row.procedureText}` : 'Treatment removed from the plan';
  }

  protected tick(i: SpConsentItem, e: Event): void {
    const confirmed = (e.target as HTMLInputElement).checked;
    const next = this.items().map((x) => (x.key === i.key ? { ...x, confirmed } : x));
    this.patch({ items: next.map(({ key, formLabel, confirmed: c }) => ({ key, formLabel: formLabel ?? null, confirmed: c })) });
  }

  protected setForm(i: SpConsentItem, e: Event): void {
    this.patch({ items: this.val().items.map((x) => (x.key === i.key ? { ...x, formLabel: textOrNull(e) } : x)) });
  }

  protected add(rowId: string): void {
    this.patch({ items: [...this.val().items, { key: rowId, formLabel: null, confirmed: false }] });
  }

  protected remove(i: SpConsentItem): void {
    this.patch({ items: this.val().items.filter((x) => x.key !== i.key) });
  }
}
