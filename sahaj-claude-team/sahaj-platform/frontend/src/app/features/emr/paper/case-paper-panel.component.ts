import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { drName } from '../record/emr-save-status';
import { ageText } from '../../../shared/format/patient-age';
import { AdviceTextService } from '../components/advice-text.service';
import { EmrRecordStore } from '../record/emr-record.store';
import { PaperSize, PrintLanguage } from '../emr-phase3.model';
import {
  LANGUAGE_CHOICES,
  NEW_PATIENT,
  PRINT_BLOCKED_NOTE,
  REG_NOT_SET,
  SHARE_NOTE,
  footerLines,
  visibleRows,
} from './case-paper.logic';
import { printPaper } from './print-paper';

const SIZES: readonly PaperSize[] = ['A4', 'A5'];
const TOAST_MS = 3000;
const SEX_TEXT = { F: 'Female', M: 'Male', O: 'Other' } as const;

/**
 * The live case paper (SAH-55; mockup #CasePaper, the "Case paper · n of 10 filled · live" tab):
 * the paper fills as the doctor types, with the clinic and patient header and ten headings in
 * English, Marathi or Hindi; A4 or A5; "Hide empty"; browser print. Share on WhatsApp or ABHA is
 * shown switched off (DEC-040). The paper is a reading of the EMR values: nothing is typed on it,
 * stored or logged by this panel. Size and language chosen here are this visit's only.
 */
@Component({
  selector: 'app-case-paper-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './case-paper-panel.component.scss',
  templateUrl: './case-paper-panel.component.html',
})
export class CasePaperPanelComponent {
  protected readonly store = inject(EmrRecordStore);
  private readonly doc = inject(DOCUMENT);
  private readonly advice = inject(AdviceTextService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly sheet = viewChild<ElementRef<HTMLElement>>('sheet');

  protected readonly sizes = SIZES;
  protected readonly languages = LANGUAGE_CHOICES;
  protected readonly hideEmpty = signal(false);
  protected readonly toast = signal<string | null>(null);
  protected readonly printBlockedNote = PRINT_BLOCKED_NOTE;
  protected readonly shareNote = SHARE_NOTE;
  protected readonly regNotSet = REG_NOT_SET;
  protected readonly present = (v: string | null): boolean => !!v;

  protected readonly context = this.store.paperContext;
  protected readonly model = this.store.paperModel;
  protected readonly language = this.store.printLanguage;
  protected readonly size = this.store.paperSize;
  protected readonly rows = computed(() => {
    const model = this.model();
    return model ? visibleRows(model, this.hideEmpty()) : [];
  });
  protected readonly filled = computed(() => this.model()?.filledCount ?? 0);
  protected readonly percent = computed(() => Math.round((this.filled() / 10) * 100));
  protected readonly footer = computed(() => footerLines(this.context()?.clinicDefaults.capitals ?? false, false, this.context()?.signature.signed ?? false));
  protected readonly addenda = computed(() => this.context()?.addenda ?? []);
  protected readonly drName = drName;
  protected readonly addendumTime = formatDisplayDateTimeOfInstant;
  protected readonly patientMeta = computed(() => {
    const c = this.context();
    if (!c) {
      return '';
    }
    const weight = this.store.values()['vitals']?.['weight'];
    const kg = typeof weight === 'number' ? weight : typeof (weight as { value?: unknown } | null)?.value === 'number' ? (weight as { value: number }).value : null;
    const age =
      this.store.profile() === 'paeds' && c.patient.ageDetail
        ? ageText(c.patient.ageDetail, 'header')
        : c.patient.age !== null
          ? `${c.patient.age} y`
          : '';
    return [age, SEX_TEXT[c.patient.sex], kg !== null ? `${kg} kg` : '']
      .filter((p) => p)
      .join(' · ');
  });
  protected readonly uhid = computed(() => {
    const u = this.context()?.patient.uhid;
    return u ? `UHID ${u}` : NEW_PATIENT;
  });
  protected readonly when = formatDisplayDateTimeOfInstant(new Date().toISOString());
  protected readonly signedAt = computed(() => formatDisplayDateTimeOfInstant(this.context()?.signature.signedAt));
  protected readonly blocked = this.store.printBlocked;

  private toastTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => {
      if (this.toastTimer) {
        clearTimeout(this.toastTimer);
      }
    });
    // The paper prints Marathi or Hindi advice text when the master has it: learn it for chosen items.
    effect(() => {
      if (this.language() === 'EN') {
        return;
      }
      for (const section of Object.values(this.store.values())) {
        for (const value of Object.values(section)) {
          const items = (value as { component?: string; kind?: string; items?: { itemKey: string | null; text: string; newToList: boolean }[] } | null);
          if (items?.component === 'EMR_LIST' && items.kind === 'ADVICE') {
            for (const item of items.items ?? []) {
              this.advice.ensure(item, this.store.record()?.layout.starterKey ?? null);
            }
          }
        }
      }
    });
  }

  protected setSize(size: PaperSize): void {
    this.store.setPrint({ paperSize: size });
  }

  protected setLanguage(language: PrintLanguage): void {
    this.store.setPrint({ language });
  }

  protected toggleHideEmpty(): void {
    this.hideEmpty.update((v) => !v);
  }

  /** Prints the paper now (after the sign, the signed prescription). Returns false when it could not. */
  printNow(): boolean {
    const el = this.sheet()?.nativeElement;
    if (!el) {
      return false;
    }
    const ok = printPaper(this.doc, this.doc.defaultView ?? window, el, this.size());
    if (!ok) {
      this.say('We could not print. Try again.');
    }
    return ok;
  }

  protected print(): void {
    const el = this.sheet()?.nativeElement;
    if (!el || this.blocked()) {
      return;
    }
    const ok = printPaper(this.doc, this.doc.defaultView ?? window, el, this.size());
    if (!ok) {
      this.say('We could not print. Try again.');
    }
  }

  private say(message: string): void {
    this.toast.set(message);
    if (this.toastTimer) {
      clearTimeout(this.toastTimer);
    }
    this.toastTimer = setTimeout(() => this.toast.set(null), TOAST_MS);
  }
}
