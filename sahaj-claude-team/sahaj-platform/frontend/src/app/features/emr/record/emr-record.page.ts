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
import { GmToolsComponent } from '../specialty/gm/gm-tools.component';
import { SpecialtyContext } from '../specialty/specialty.context';
import { PROFILE_CHROME } from '../specialty/specialty-registry';
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

/** Part tab words: General Medicine has its own, every other department uses the SAH-45 part lines (SAH-47-2). */
const GM_SUBTITLE: Record<EmrPart, string> = {
  ASSESSMENT: 'Complaints and examination',
  TREATMENT: 'Diagnosis, medicines, tests and services',
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
    GmToolsComponent,
    SpecialtyStripComponent,
  ],
  providers: [EmrRecordStore, EmrFinishFlow, EmrAutosave, SpecialtyContext, SignPrints, { provide: EMR_COMPONENT_CONTEXT, useExisting: EmrRecordStore }],
  styleUrl: './emr-record.page.scss',
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

  protected readonly stageNote = signal<string | null>(null);
  private readonly hadPrevious: boolean;
  private highlightTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly partLabel = PART_LABEL;
  /** Quick tools (visit templates, trend) are drawn on the General Medicine screen (ADR-023 D1). */
  protected readonly rightTabs = computed(() => (this.store.profile() && PROFILE_CHROME[this.store.profile()!].quickTools ? [...RIGHT_TABS, TOOLS_TAB] : RIGHT_TABS));
  protected readonly chrome = computed(() => (this.store.profile() ? PROFILE_CHROME[this.store.profile()!] : null));
  protected readonly finishLabel = computed(() => this.chrome()?.finishLabel ?? 'Complete visit');
  /** A card after the last Assessment section (the oncology decision); loaded when the profile has one. */
  protected readonly assessmentExtra = signal<Type<unknown> | null>(null);
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
  protected readonly isGeneralMedicine = computed(() => this.store.record()?.layout.starterKey === 'gm');
  protected readonly currentSections = computed(() => this.store.sectionsOf(this.store.part()));
  protected readonly specialty = computed(() => this.store.record()?.layout.starterKey ?? null);
  protected readonly footerTone = computed(() => (this.store.ready() ? 'good' : 'crit'));
  protected readonly paperSubtitle = this.store.paperSubtitle;

  /** "From assessment:" in Treatment: the complaints, then the other assessment sections that are done. */
  protected readonly recap = computed(() => {
    const complaints = this.store.values()['complaints'];
    const texts: string[] = [];
    if (complaints) {
      for (const value of Object.values(complaints)) {
        if (Array.isArray(value)) {
          for (const entry of value) {
            if (typeof entry === 'object' && entry !== null && 'text' in entry) {
              texts.push(String((entry as { text: unknown }).text));
            }
          }
        }
      }
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
      const loader = this.chrome()?.assessmentExtra;
      if (loader) {
        void loader().then((type) => this.assessmentExtra.set(type));
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
    return tab.id === 'history' ? 'OPD + IPD' : tab.id === 'paper' ? this.paperSubtitle() : 'Helpers for this visit';
  }

  protected tabSubtitle(part: EmrPart): string {
    return this.isGeneralMedicine() ? GM_SUBTITLE[part] : PART_HEAD[part].sub;
  }

  protected progressOf(part: EmrPart) {
    return this.store.progress().find((p) => p.part === part);
  }

  protected navStates(part: EmrPart) {
    return this.store.sectionsOf(part).map((section) => ({ section, state: this.store.stateOf(section.key) }));
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
