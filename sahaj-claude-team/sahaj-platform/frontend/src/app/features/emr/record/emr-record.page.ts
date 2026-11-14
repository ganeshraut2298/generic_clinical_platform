import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  untracked,
  viewChild,
  Injector,
  OnInit,
  Type,
  effect,
  afterNextRender,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NgComponentOutlet } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, map, of, switchMap } from 'rxjs';
import { ScrollTo } from '../../../core/scroll/scroll-to.service';
import { IconComponent } from '../../../shared/icon/icon.component';
import { AdmissionDialogComponent, AdmissionDialogData } from '../../ipd/admission/admission-dialog.component';
import { AdmitDialogComponent, AdmitDialogData } from '../../ipd/admit/admit-dialog.component';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { EmrFinishFlow, FinishEvent } from '../finish/emr-finish.flow';
import { afterSign } from '../finish/finish.logic';
import { PRINT_NAME } from '../finish/sign-print-sheets.logic';
import { SignPrintData, SignPrints } from '../finish/sign-prints.service';
import { LANGUAGE_NAME } from '../paper/case-paper.logic';
import { EmrAddendum, EmrFinishPrint } from '../emr-finish.model';
import { PART_HEAD } from '../../config/emr-config/emr-config.logic';
import { RIGHT_TAB_SUBTITLE, assessmentBar, cardNumber, gmRecap, badgeFull, badgeText, oncoCounts, surgeryRecap } from './frame.logic';
import { EmrNavigationState } from '../emr-launcher.service';
import { EMR_COMPONENT_CONTEXT } from '../emr-context';
import { CasePaperPanelComponent } from '../paper/case-paper-panel.component';
import { PastVisitsPanelComponent } from '../history/past-visits-panel.component';
import { EmrPart, EmrReadOnlyReason, PART_LABEL } from '../emr.model';
import { AdmitAdviceDialogComponent, AdmitAdviceResult } from './admit-advice-dialog.component';
import { EmrAdmitRequestDialogComponent, AdmitRequestDialogData, AdmitRequestDialogResult } from './emr-admit-request-dialog.component';
import { EmrAddendumDialogComponent } from './emr-addendum-dialog.component';
import { EmrAutosave } from './emr-autosave.service';
import { EmrConfirmData, EmrConfirmDialogComponent } from './emr-confirm-dialog.component';
import { EmrHeaderComponent } from './emr-header.component';
import { drName, saveStatusText } from './emr-save-status';
import { EmrLeaveChoice, EmrLeaveDialogComponent } from './emr-leave-dialog.component';
import { QuickToolsComponent } from '../specialty/quick-tools.component';
import { CoursePanelComponent } from '../course/course-panel.component';
import { SpecialtyContext } from '../specialty/specialty.context';
import { fitnessOf, specialtyOf } from '../specialty/specialty.logic';
import { dentalRecap, medicalAlerts } from '../specialty/dental/dental-medhistory.logic';
import { isComponentValue } from '../components/component-values.logic';
import { accentVars, chromeOf } from '../specialty/specialty-registry';
import { SpecialtyStripComponent } from '../specialty/specialty-strip.component';
import { EmrRecordStore, RightTab } from './emr-record.store';
import { SectionCardComponent } from './section-card.component';

const HIGHLIGHT_MS = 1600;

const READ_ONLY_TEXT: Record<EmrReadOnlyReason, string> = {
  SIGNED: 'This visit is signed, so it opens read-only.',
  ENCOUNTER_CLOSED: 'This encounter is closed, so it opens read-only.',
  HELD_BY_OTHER: 'Another doctor has this visit open for editing.',
  IPD_READ_ONLY: 'IPD is read-only for this clinic, so this EMR opens read-only.',
  HISTORY: 'This is an earlier signed visit. It opens read-only.',
};

const RIGHT_TABS: readonly { id: RightTab; label: string }[] = [
  { id: 'history', label: 'Past visits' },
  { id: 'paper', label: 'Case paper' },
];
const TOOLS_TAB: { id: RightTab; label: string } = { id: 'tools', label: 'Quick tools' };

/**
 * The EMR screen (SAH-46, SAH-47, SAH-52; mockup #DoctorGeneral): the header, two parts with done
 * counts, the sections of the layout the visit opened with, a sticky footer with "Still needed"
 * and Save draft, and the right-hand panel. It sits inside the normal Sahaj shell, so the mockup's
 * own top bar is not drawn (SO-45-16). Complete is shown but does not sign (SAH-51).
 */
@Component({
  selector: 'app-emr-record',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    IconComponent,
    EmrHeaderComponent,
    SectionCardComponent,
    PastVisitsPanelComponent,
    CasePaperPanelComponent,
    NgComponentOutlet,
    QuickToolsComponent,
    CoursePanelComponent,
    SpecialtyStripComponent,
  ],
  providers: [EmrRecordStore, EmrFinishFlow, EmrAutosave, SpecialtyContext, SignPrints, { provide: EMR_COMPONENT_CONTEXT, useExisting: EmrRecordStore }],
  styleUrl: './emr-record.page.scss',
  host: { '[style]': 'accent()' },
  templateUrl: './emr-record.page.html',
})
export class EmrRecordPage implements OnInit {
  protected readonly store = inject(EmrRecordStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly scroll = inject(ScrollTo);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly flow = inject(EmrFinishFlow);
  private readonly autosave = inject(EmrAutosave);
  private readonly paperPanel = viewChild(CasePaperPanelComponent);
  private readonly specialtyCtx = inject(SpecialtyContext);
  private readonly pendingFooterPrint = signal<'handler' | 'paper' | null>(null);

  protected readonly stageNote = signal<string | null>(null);
  private readonly hadPrevious: boolean;
  private highlightTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly partLabel = PART_LABEL;
  /** Quick tools (visit templates, trend) are drawn on the General Medicine screen (ADR-023 D1). */
  protected readonly chrome = computed(() => chromeOf(this.store.profile()));
  protected readonly rightTabs = computed(() => (this.chrome().quickTools ? [...RIGHT_TABS, TOOLS_TAB] : RIGHT_TABS));
  protected readonly finishLabel = computed(() => this.chrome().finishLabel);
  /** Department accent (BR75-10) as the colour variables the EMR page and its panels use; none for other departments. */
  protected readonly accent = computed(() => accentVars(this.chrome().accent));
  protected readonly printLabel = computed(() => this.chrome().printLabel?.(this.store.visitMode()) ?? null);
  /** A card after the last Assessment section (the oncology decision); loaded when the profile has one. */
  protected readonly assessmentExtra = signal<Type<unknown> | null>(null);
  /** A bar above the Assessment cards (the oncology "Assessment steps", SAH-75-11 O-4). */
  protected readonly assessmentTop = signal<Type<unknown> | null>(null);
  protected readonly parts: readonly EmrPart[] = ['ASSESSMENT', 'TREATMENT'];

  protected readonly record = this.store.record;
  /** Where the doctor goes after a successful sign, once the signed prescription has printed. */
  private readonly pendingPrint = signal<{ target: 'QUEUE' | 'IPD' | 'EMERGENCY'; prints: EmrFinishPrint[] } | null>(null);
  private readonly printData = signal<SignPrintData | null>(null);
  private readonly signPrints = inject(SignPrints);
  protected readonly saveText = computed(() => saveStatusText(this.store.saveState()));
  /** The edit hold of another doctor (SAH-51-4): the banner "Open for editing by Dr. <name>" and its buttons. */
  protected readonly hold = computed(() => {
    const lost = this.store.lostEditing();
    const record = this.store.record();
    if (lost?.reason === 'HELD') {
      return { name: lost.holderName, canTakeOver: record?.editHold?.canTakeOver ?? false, expired: false };
    }
    if (record?.readOnlyReason === 'HELD_BY_OTHER') {
      const h = record.editHold;
      return {
        name: h?.holderName ?? null,
        canTakeOver: h?.canTakeOver ?? false,
        expired: !!h && new Date(h.expiresAt).getTime() <= Date.now(),
      };
    }
    return null;
  });
  protected readonly holdText = computed(() => {
    const h = this.hold();
    return h ? `Open for editing by ${h.name ? drName(h.name) : 'another doctor'}` : null;
  });
  protected readonly signed = computed(() => this.store.record()?.status === 'SIGNED' || this.store.lostEditing()?.reason === 'SIGNED');
  protected readonly signedLine = computed(() => {
    const r = this.store.record();
    if (!r || r.status !== 'SIGNED') {
      return null;
    }
    const who = r.signedByName ? ` by ${drName(r.signedByName)}` : '';
    const when = r.signedAt ? ` · ${formatDisplayDateTimeOfInstant(r.signedAt)} IST` : '';
    return `Signed${who}${when}`;
  });
  protected readonly addenda = computed(() => this.store.record()?.addenda ?? []);
  protected readonly readOnlyText = computed(() => {
    if (this.hold()) {
      return null;
    }
    const reason = this.store.record()?.readOnlyReason;
    return reason ? READ_ONLY_TEXT[reason] : null;
  });
  /** Sections whose cards sit in the Quick tools tab instead of the left column (P5, P-5). */
  private readonly toolKeys = computed(() => new Set(this.chrome().quickTools?.sectionKeys ?? []));
  /** With part tabs: the open part. Without (Gynae, Paediatrics, Physiotherapy): every section in published order. */
  protected readonly currentSections = computed(() => {
    const moved = this.toolKeys();
    const list = this.chrome().partTabs ? this.store.sectionsOf(this.store.part()) : this.store.visibleLayout();
    return moved.size === 0 ? list : list.filter((s) => !moved.has(s.key));
  });
  /** Obstetrics: the ANC and Gynae editors draw only their main part while the Quick tools tab holds the rest. */
  /** Paediatrics: Growth and Milestones sit side by side when both are in this part (SAH-75-11 P4). */
  protected isHalf(section: { key: string }): boolean {
    if (this.store.profile() !== 'paeds' || (section.key !== 'growth' && section.key !== 'milestones')) {
      return false;
    }
    const keys = this.currentSections().map((s) => s.key);
    return keys.includes('growth') && keys.includes('milestones');
  }

  protected areaOf(section: { key: string }): 'main' | null {
    return this.chrome().quickTools?.kind === 'og' && (section.key === 'anc' || section.key === 'gyn_exam') ? 'main' : null;
  }
  /**
   * The course panel (SAH-75-3): the section it sits above or below; at the end of the Treatment part when the
   * layout does not show its "before" section. Null when the department has none or the mode hides it.
   */
  private readonly panelPlace = computed<{ before: string | null; after: string | null; atEnd: boolean } | null>(() => {
    const spec = this.chrome().coursePanel;
    if (!spec || (spec.mode && this.store.visitMode() !== spec.mode)) {
      return null;
    }
    const keys = new Set(this.currentSections().map((s) => s.key));
    if (spec.after) {
      return keys.has(spec.after) ? { before: null, after: spec.after, atEnd: false } : null;
    }
    if (spec.before && keys.has(spec.before)) {
      return { before: spec.before, after: null, atEnd: false };
    }
    const atEnd = !this.chrome().partTabs || this.store.part() === 'TREATMENT';
    return atEnd ? { before: null, after: null, atEnd: true } : null;
  });
  protected readonly panelKind = computed(() => this.chrome().coursePanel?.kind ?? null);
  protected panelBefore(key: string): boolean {
    return this.panelPlace()?.before === key;
  }
  protected panelAfter(key: string): boolean {
    return this.panelPlace()?.after === key;
  }
  protected readonly panelAtEnd = computed(() => this.panelPlace()?.atEnd === true);

  protected titleOf(section: { key: string; name: string }): string {
    const title = this.chrome().cardTitles?.[section.key] ?? section.name;
    const number = cardNumber(this.chrome().numberedCards, this.currentSections().map((s) => s.key), section.key);
    return number === null ? title : `${number} · ${title}`;
  }
  protected readonly specialty = computed(() => this.store.record()?.layout.starterKey ?? null);
  protected readonly footerTone = computed(() => (this.store.ready() ? 'good' : 'crit'));
  protected readonly paperSubtitle = this.store.paperSubtitle;

  /** "From assessment:" in Treatment: General Medicine has its own sentence (G10); others the complaints, then the sections done. */
  protected readonly recap = computed(() => {
    const complaints = this.store.values()['complaints'];
    const texts: string[] = [];
    if (complaints) {
      for (const value of Object.values(complaints)) {
        if (Array.isArray(value)) {
          for (const entry of value) {
            if (typeof entry === 'object' && entry !== null && 'text' in entry) {
              const e = entry as { text: unknown; attr1?: unknown };
              const duration = typeof e.attr1 === 'string' && e.attr1.trim() !== '' ? ` (${e.attr1.trim()})` : '';
              texts.push(`${String(e.text)}${duration}`);
            }
          }
        }
      }
    }
    if (this.store.profile() === 'gm') {
      const vitals = specialtyOf(this.store.values()['vitals']);
      const last = vitals?.component === 'VITALS_LOG' ? (vitals.entries[vitals.entries.length - 1] ?? null) : null;
      const map = this.store.values()['local_exam']?.['body_map'];
      const pins = isComponentValue(map) && map.component === 'BODY_MAP' ? map.pins : [];
      return gmRecap({
        complaints: texts,
        bp: last?.bpSystolic != null && last.bpDiastolic != null ? `${last.bpSystolic}/${last.bpDiastolic}` : null,
        pulse: last?.pulse ?? null,
        pins,
      });
    }
    if (this.store.profile() === 'surgery') {
      const keyOf = (component: string): string | undefined => this.store.layout().find((x) => x.specialtyEditor?.component === component)?.key;
      const exam = specialtyOf(this.store.values()[keyOf('SURGICAL_EXAM') ?? '']);
      const fit = specialtyOf(this.store.values()[keyOf('SURGERY_FITNESS') ?? '']);
      const fitness = fit?.component === 'SURGERY_FITNESS' ? fit : null;
      return surgeryRecap({
        complaints: texts,
        murphy: exam?.component === 'SURGICAL_EXAM' ? (exam.murphySign ?? null) : null,
        asa: fitness?.asa ?? null,
        fitness: fitnessOf(fitness).text,
      });
    }
    if (this.store.profile() === 'dental') {
      const med = specialtyOf(this.store.values()['dental_medhx']);
      const habits = this.store.values()['dental_hx']?.['habits'];
      const score = this.store.values()['dental_pain']?.['score'];
      return dentalRecap(
        texts,
        typeof score === 'string' && /^o\d+$/.test(score) ? score.slice(1) : null,
        medicalAlerts(med?.component === 'DENTAL_MED_HISTORY' ? med : null, Array.isArray(habits) ? (habits as string[]) : []),
      );
    }
    const done = this.store
      .sectionsOf('ASSESSMENT')
      .filter((s) => s.key !== 'complaints' && this.store.stateOf(s.key)?.done)
      .map((s) => s.name.toLowerCase());
    const parts = [...(texts.length > 0 ? [texts.join(', ')] : []), ...(done.length > 0 ? [`${done.join(', ')} done`] : [])];
    return parts.length > 0 ? parts.join(' · ') : 'nothing recorded yet';
  });

  constructor() {
    const nav = this.router.currentNavigation();
    this.hadPrevious = !!nav?.previousNavigation;
    const state = nav?.extras.state as Partial<EmrNavigationState> | undefined;
    if (state?.stageOutcome === 'ALREADY_UPDATED') {
      this.stageNote.set('The queue status was already updated.');
    }
    this.destroyRef.onDestroy(() => {
      this.clearHighlightTimer();
      this.store.releaseHold();
    });
    this.flow.events.pipe(takeUntilDestroyed()).subscribe((event) => this.onFinishEvent(event));
    // After a sign the documents of outcome.prints print in order once the signature is in (the prescription from the Case paper), then the doctor goes back.
    effect(() => {
      const pending = this.pendingPrint();
      const context = this.store.paperContext();
      const panel = this.paperPanel();
      const data = this.printData();
      const failed = this.store.paperError();
      if (!pending) {
        return;
      }
      const needsPaper = pending.prints.includes('PRESCRIPTION');
      if (failed && needsPaper) {
        untracked(() => {
          this.pendingPrint.set(null);
          this.snackBar.open('We could not print. Open the Case paper tab and print from there.', 'Close', { duration: 7000 });
        });
        return;
      }
      if (context?.signature.signed && data && (panel || !needsPaper)) {
        untracked(() => {
          this.pendingPrint.set(null);
          afterNextRender(() => this.runPrints(pending.target, pending.prints, data, panel), { injector: this.injector });
        });
      }
    });
    effect(() => {
      const pending = this.pendingFooterPrint();
      const handler = this.specialtyCtx.footerPrintHandler();
      const panel = this.paperPanel();
      if (pending === 'handler' && handler) {
        untracked(() => {
          this.pendingFooterPrint.set(null);
          handler();
        });
      } else if (pending === 'paper' && panel) {
        untracked(() => {
          this.pendingFooterPrint.set(null);
          panel.printNow();
        });
      }
    });
    effect(() => {
      const loader = this.chrome().assessmentExtra;
      if (loader) {
        void loader().then((type) => this.assessmentExtra.set(type));
      }
      const top = this.chrome().assessmentTop;
      if (top) {
        void top().then((type) => this.assessmentTop.set(type));
      }
    });
  }

  ngOnInit(): void {
    this.autosave.start();
    const id = this.route.snapshot.paramMap.get('recordId');
    if (id) {
      this.store.load(id, this.route.snapshot.paramMap.get('pastRecordId'));
    }
  }

  protected tabSubtitleOf(tab: { id: RightTab }): string {
    return tab.id === 'paper' ? this.paperSubtitle() : RIGHT_TAB_SUBTITLE[tab.id];
  }

  protected tabSubtitle(part: EmrPart): string {
    return this.chrome().partSubtitles?.[part] ?? PART_HEAD[part].sub;
  }

  /** "n of m required" (or "n of m done" in oncology) and whether the part is complete (a tick). */
  protected badgeOf(part: EmrPart): { text: string; full: boolean } {
    const chrome = this.chrome();
    const progress = this.progressOf(part);
    const counts =
      chrome.badgeWord === 'done'
        ? oncoCounts(part, this.store.visibleLayout(), this.store.footerItems())
        : { done: progress?.requiredDone ?? 0, total: progress?.requiredTotal ?? 0 };
    return { text: badgeText(chrome.badgeWord, counts), full: badgeFull(counts) };
  }

  /** The nav group count "n/m": the required sections of the part that are done. */
  protected navCountsOf(part: EmrPart): { done: number; total: number } {
    const progress = this.progressOf(part);
    return { done: progress?.requiredDone ?? 0, total: progress?.requiredTotal ?? 0 };
  }

  private readonly requiredTotals = computed(() => {
    const all = this.store.progress();
    return { done: all.reduce((n, p) => n + p.requiredDone, 0), total: all.reduce((n, p) => n + p.requiredTotal, 0) };
  });
  protected readonly requiredLine = computed(() => `${this.requiredTotals().done} of ${this.requiredTotals().total} required done`);
  protected readonly requiredAllDone = computed(() => this.requiredTotals().done >= this.requiredTotals().total);
  protected readonly requiredPct = computed(() => (this.requiredTotals().total === 0 ? 100 : (100 * this.requiredTotals().done) / this.requiredTotals().total));

  /** The dark bar under Assessment: what is still needed, or what comes next (mockup aFooter). */
  protected readonly assessmentLine = computed(() => {
    const needed = new Set(this.store.needed());
    const missing = this.store
      .sectionsOf('ASSESSMENT')
      .filter((s) => needed.has(s.key))
      .map((s) => (this.chrome().neededWords?.[s.key] ?? this.titleOf(s)).toLowerCase());
    const complaints = this.store.stateOf('complaints');
    return assessmentBar({
      profile: this.store.profile(),
      missing,
      done: this.store.progress()[0].complete,
      hasComplaints: complaints ? complaints.done : true,
      view: this.store.specialtyView(),
    });
  });

  protected progressOf(part: EmrPart) {
    return this.store.progress().find((p) => p.part === part);
  }

  protected navStates(part: EmrPart) {
    return this.store.sectionsOf(part).map((section) => ({ section, state: this.store.stateOf(section.key) }));
  }

  /**
   * The footer print button (Print estimate, Print ANC card, Print vaccination card, Progress report, Print Rx):
   * runs the print of the card that owns it; when that card is not on screen yet its part or tab opens first.
   */
  protected footerPrint(): void {
    const handler = this.specialtyCtx.footerPrintHandler();
    if (handler) {
      handler();
      return;
    }
    const profile = this.store.profile();
    if (profile === 'og' && this.store.visitMode() === 'GYNAE') {
      this.store.rightTab.set('paper');
      this.pendingFooterPrint.set('paper');
      return;
    }
    if (profile === 'dental') {
      this.store.setPart('TREATMENT');
    } else if (profile === 'physio') {
      this.store.rightTab.set('tools');
    }
    this.pendingFooterPrint.set('handler');
  }

  protected setRightTab(tab: RightTab): void {
    this.store.rightTab.set(tab);
  }

  // ---- movement inside the screen (never blocked by missing sections) ----

  protected setPart(part: EmrPart): void {
    this.store.setPart(part);
    this.store.autosaveNow();
  }

  protected jump(key: string): void {
    this.store.goToSection(key);
    this.scrollToSection(key);
  }

  private scrollToSection(key: string): void {
    afterNextRender(
      () => {
        this.scroll.section(`sec-${key}`);
        this.clearHighlightTimer();
        this.highlightTimer = setTimeout(() => this.store.clearHighlight(), HIGHLIGHT_MS);
      },
      { injector: this.injector },
    );
  }

  private clearHighlightTimer(): void {
    if (this.highlightTimer) {
      clearTimeout(this.highlightTimer);
      this.highlightTimer = null;
    }
  }

  // ---- footer ----

  protected saveDraft(): void {
    this.store.save().subscribe((ok) => {
      this.autosave.restart();
      if (ok) {
        this.snackBar.open('Draft saved', 'Close', { duration: 3500 });
      }
    });
  }

  /**
   * Complete (SAH-51-1): with something missing the dialog does not open, a message lists what is missing
   * and the first missing section is opened and marked; otherwise the checks run and "Check before
   * completing" opens (an Emergency case first asks for the ER outcome).
   */
  protected complete(): void {
    this.flow.begin();
  }

  private onFinishEvent(event: FinishEvent): void {
    switch (event.kind) {
      case 'blocked':
        if (event.sectionKey) {
          this.jump(event.sectionKey);
        }
        this.snackBar.open(event.message, 'Close', { duration: 8000 });
        return;
      case 'reload':
        this.store.reload();
        return;
      case 'signed': {
        const next = afterSign(event.record.encounterType, this.store.profile(), event.outcome, event.choices, event.sessionNo, LANGUAGE_NAME[this.store.printLanguage()]);
        this.snackBar.open(next.message, 'Close', { duration: 6000 });
        if (next.prints.length === 0) {
          this.leave(next.target);
          return;
        }
        if (next.prints.includes('PRESCRIPTION')) {
          this.store.rightTab.set('paper');
        }
        this.printData.set(null);
        this.pendingPrint.set({ target: next.target, prints: next.prints });
        this.signPrints.prepare(next.prints, event.profile).subscribe((data) => this.printData.set(data));
        return;
      }
    }
  }

  /** One browser print each, in the server's order. A document that could not print is offered again before the doctor leaves. */
  private runPrints(target: 'QUEUE' | 'IPD' | 'EMERGENCY', prints: readonly EmrFinishPrint[], data: SignPrintData, panel: CasePaperPanelComponent | undefined): void {
    const failed: EmrFinishPrint[] = [];
    for (const print of prints) {
      const ok = print === 'PRESCRIPTION' ? (panel?.printNow() ?? false) : this.signPrints.print(print, data);
      if (!ok && print !== 'PRESCRIPTION') {
        failed.push(print);
      }
    }
    if (failed.length === 0) {
      this.leave(target);
      return;
    }
    this.snackBar
      .open(`We could not print ${failed.map((f) => PRINT_NAME[f]).join(' and ')}.`, 'Try again', { duration: 15000 })
      .afterDismissed()
      .subscribe((result) => {
        if (result.dismissedByAction) {
          this.runPrints(target, failed, data, panel);
        } else {
          this.leave(target);
        }
      });
  }

  private leave(target: 'QUEUE' | 'IPD' | 'EMERGENCY'): void {
    void this.router.navigateByUrl(target === 'QUEUE' ? '/app/queue' : target === 'EMERGENCY' ? '/app/emergency' : '/app/ipd');
  }

  // ---- one editor at a time, addendum (SAH-51-4, SAH-51-5) ----

  protected editThisVisit(): void {
    this.store.takeEditHold(false);
  }

  protected takeOver(): void {
    const name = this.hold()?.name;
    this.dialog
      .open<EmrConfirmDialogComponent, EmrConfirmData, boolean>(EmrConfirmDialogComponent, {
        panelClass: 'sheet-panel',
        width: '440px',
        maxWidth: '100vw',
        ariaLabelledBy: 'emr-confirm-title',
        data: {
          title: 'Take over this visit?',
          message: `${name ? drName(name) : 'Another doctor'} is editing. Take over?`,
          confirm: 'Take over',
          cancel: 'Cancel',
        },
      })
      .afterClosed()
      .subscribe((yes) => {
        if (yes) {
          this.store.takeEditHold(true);
        }
      });
  }

  protected addAddendum(): void {
    this.dialog
      .open<EmrAddendumDialogComponent, void, EmrAddendum | null>(EmrAddendumDialogComponent, {
        panelClass: 'sheet-panel',
        width: '560px',
        maxWidth: '100vw',
        injector: this.injector,
        ariaLabelledBy: 'addendum-title',
      })
      .afterClosed()
      .subscribe((addendum) => {
        if (addendum) {
          this.store.noteAddendum(addendum);
          this.snackBar.open('Addendum saved', 'Close', { duration: 3500 });
        }
      });
  }

  protected whenOf(instant: string): string {
    return formatDisplayDateTimeOfInstant(instant);
  }

  protected drOf(name: string): string {
    return drName(name);
  }

  // ---- header actions ----

  protected goBack(): void {
    this.navigateBack();
  }

  private navigateBack(): void {
    if (this.hadPrevious) {
      history.back();
      return;
    }
    const type = this.store.record()?.encounterType;
    const target = type === 'OPD_VISIT' ? '/app/queue' : type === 'ER_ASSESSMENT' ? '/app/emergency' : '/app/ipd';
    void this.router.navigateByUrl(target);
  }

  /**
   * Admit to IPD (SAH-52-4, SAH-67-3). With IPD on the facts are read and the admission request sheet opens;
   * the owner goes on to the existing admission sheet (pre-filled), others send the request. An admitted
   * patient's button opens that admission. With IPD off the OPD-only sheet offers "Advise admission + referral".
   */
  protected admit(): void {
    const record = this.store.record();
    if (!record) {
      return;
    }
    const adm = record.admission;
    if ((adm?.state === 'ADMITTED' || adm?.state === 'ALREADY_ADMITTED') && adm.admissionId) {
      this.dialog.open<AdmissionDialogComponent, AdmissionDialogData>(AdmissionDialogComponent, {
        panelClass: 'sheet-panel',
        width: '720px',
        maxWidth: '100vw',
        data: { id: adm.admissionId },
      });
      return;
    }
    if (record.readOnly) {
      return;
    }
    const state = record.ipd.state;
    if (state === 'ON' || state === 'TRIAL') {
      this.store.admissionPrefill().subscribe((prefill) => {
        if (!prefill) {
          return;
        }
        this.dialog
          .open<EmrAdmitRequestDialogComponent, AdmitRequestDialogData, AdmitRequestDialogResult | null>(EmrAdmitRequestDialogComponent, {
            panelClass: 'sheet-panel',
            width: '560px',
            maxWidth: '100vw',
            injector: this.injector,
            ariaLabelledBy: 'admit-request-title',
            data: { prefill },
          })
          .afterClosed()
          .subscribe((result) => {
            if (!result) {
              return;
            }
            if (result.openSheet) {
              this.openAdmitSheet(result);
            } else {
              this.snackBar.open('Admission request sent', 'Close', { duration: 4000 });
            }
          });
      });
      return;
    }
    this.dialog
      .open<AdmitAdviceDialogComponent, { canSwitchOn: boolean }, AdmitAdviceResult | null>(
        AdmitAdviceDialogComponent,
        {
          panelClass: 'sheet-panel',
          width: '480px',
          maxWidth: '100vw',
          ariaLabelledBy: 'advice-title',
          data: { canSwitchOn: record.ipd.canSwitchOn },
        },
      )
      .afterClosed()
      .subscribe((result) => {
        if (result) {
          this.store.adviseAdmission(result.referralLine).subscribe();
        }
      });
  }

  private openAdmitSheet(result: AdmitRequestDialogResult): void {
    const data: AdmitDialogData = {
      admissionRequestId: result.request.id,
      admissionType: result.request.admissionType,
      reason: result.prefill.diagnoses.join(', '),
      ...(result.prefill.patient.patientId ? { patientId: result.prefill.patient.patientId } : {}),
      ...(result.prefill.emergencyCaseId ? { emergencyCaseId: result.prefill.emergencyCaseId } : {}),
    };
    this.dialog
      .open<AdmitDialogComponent, AdmitDialogData, { ipNumber?: string } | null>(AdmitDialogComponent, {
        panelClass: 'sheet-panel',
        width: '640px',
        maxWidth: '100vw',
        data,
      })
      .afterClosed()
      .subscribe((admission) => {
        if (admission) {
          this.store.refreshRecord();
          this.snackBar.open(admission.ipNumber ? `Admitted · ${admission.ipNumber}` : 'Admitted', 'Close', { duration: 5000 });
        }
      });
  }

  // ---- leaving with unsaved entries (SAH-47-5) ----

  /** Asked by the router before the doctor leaves the screen. */
  confirmLeave(): Observable<boolean> | boolean {
    if (!this.store.dirty()) {
      return true;
    }
    return this.dialog
      .open<EmrLeaveDialogComponent, void, EmrLeaveChoice>(EmrLeaveDialogComponent, {
        panelClass: 'sheet-panel',
        width: '440px',
        maxWidth: '100vw',
      })
      .afterClosed()
      .pipe(
        switchMap((choice) => {
          if (choice === 'leave') {
            return of(true);
          }
          if (choice === 'save') {
            return this.store.save().pipe(map((ok) => ok));
          }
          return of(false);
        }),
      );
  }

  @HostListener('window:beforeunload', ['$event'])
  protected onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.store.dirty()) {
      event.preventDefault();
    }
  }
}
