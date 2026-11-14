import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { EmrFormPlace, EmrFormPrintLanguage, EmrFormVisitType, EmrFormWho } from '../../emr/emr-phase3.model';
import { PLACE_CHOICES, PRINT_LANGUAGE_CHOICES, VISIT_TYPE_CHOICES, WHO_CHOICES } from './form-builder.logic';
import { FormBuilderStore } from './form-builder.store';

/**
 * The right-hand "Form settings" panel (SAH-59-4): where the form is used, its EMR part, visit
 * types, who fills it, print language, approval before publishing, the versions list, and the
 * approval steps (approve, send back) for the owner. Visit types and "Who can fill" are stored and
 * shown only; no patient screen and no call exist yet. Only the owner can change anything.
 */
@Component({
  selector: 'app-form-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './form-settings.component.scss',
  templateUrl: './form-settings.component.html',
})
export class FormSettingsComponent {
  protected readonly store = inject(FormBuilderStore);

  protected readonly places = PLACE_CHOICES;
  protected readonly visitTypes = VISIT_TYPE_CHOICES;
  protected readonly who = WHO_CHOICES;
  protected readonly languages = PRINT_LANGUAGE_CHOICES;
  protected readonly parts = [
    { key: 'ASSESSMENT', label: 'Assessment' },
    { key: 'TREATMENT', label: 'Treatment' },
  ] as const;

  protected readonly settings = this.store.settings;
  protected readonly editable = this.store.canEdit;
  protected readonly versions = computed(() => this.store.view()?.versions ?? []);
  protected readonly sentBack = computed(() => this.store.view()?.sentBackReason ?? null);
  protected readonly backReason = signal('');
  protected readonly backing = signal(false);

  protected stateText(state: string): string {
    switch (state) {
      case 'WAITING_APPROVAL':
        return 'Waiting approval';
      case 'SENT_BACK':
        return 'Sent back';
      case 'SUPERSEDED':
        return 'Replaced';
      case 'LIVE':
        return 'Live';
      default:
        return 'Draft';
    }
  }

  protected atText(iso: string): string {
    return formatDisplayDateTimeOfInstant(iso);
  }

  protected hasDept(key: string): boolean {
    return this.settings()?.useIn.departmentKeys.includes(key) ?? false;
  }

  protected hasPlace(key: EmrFormPlace): boolean {
    return this.settings()?.useIn.places.includes(key) ?? false;
  }

  private toggleIn<T>(list: readonly T[], item: T): T[] {
    return list.includes(item) ? list.filter((x) => x !== item) : [...list, item];
  }

  protected toggleDept(key: string): void {
    const s = this.settings();
    if (s) {
      this.store.patchSettings({ useIn: { ...s.useIn, departmentKeys: this.toggleIn(s.useIn.departmentKeys, key) } });
    }
  }

  protected togglePlace(key: EmrFormPlace): void {
    const s = this.settings();
    if (s) {
      this.store.patchSettings({ useIn: { ...s.useIn, places: this.toggleIn(s.useIn.places, key) } });
    }
  }

  protected setPart(part: 'ASSESSMENT' | 'TREATMENT'): void {
    this.store.patchSettings({ emrPart: part });
  }

  protected toggleVisit(key: EmrFormVisitType): void {
    const s = this.settings();
    if (s) {
      this.store.patchSettings({ visitTypes: this.toggleIn(s.visitTypes, key) });
    }
  }

  protected toggleWho(key: EmrFormWho): void {
    const s = this.settings();
    if (s) {
      this.store.patchSettings({ whoCanFill: this.toggleIn(s.whoCanFill, key) });
    }
  }

  protected setLanguage(key: EmrFormPrintLanguage): void {
    this.store.patchSettings({ printLanguage: key });
  }

  protected setApproval(on: boolean): void {
    this.store.patchSettings({ approvalRequired: on });
  }

  protected visitOn(key: EmrFormVisitType): boolean {
    return this.settings()?.visitTypes.includes(key) ?? false;
  }

  protected whoOn(key: EmrFormWho): boolean {
    return this.settings()?.whoCanFill.includes(key) ?? false;
  }

  protected onBackReason(event: Event): void {
    this.backReason.set((event.target as HTMLInputElement).value);
  }

  protected startBack(): void {
    this.backing.set(true);
  }

  protected sendBack(): void {
    this.store.sendBack(this.backReason());
    this.backing.set(false);
    this.backReason.set('');
  }
}
