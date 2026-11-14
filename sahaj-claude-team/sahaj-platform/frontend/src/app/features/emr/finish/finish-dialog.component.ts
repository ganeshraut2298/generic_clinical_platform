import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { catchError, of } from 'rxjs';
import { formatDisplayDate } from '../../../shared/format/ist-date';
import { formatInrPaise } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { LIST_KINDS, choiceLabel } from '../components/emr-list.logic';
import { EmrApi } from '../emr.api';
import { EmrDentalNextInterval } from '../emr-finish.model';
import { LANGUAGE_NAME } from '../paper/case-paper.logic';
import { EmrRecordStore } from '../record/emr-record.store';
import { STAGE_LABELS } from '../../queue/queue.model';
import { EmrFinishFlow } from './emr-finish.flow';
import { ER_OUTCOME_LABEL, WHATSAPP_NOTE, billLineText, dentalAllDone, finishTitles, followUpEffectText, ordersLine, patientRecordText, summaryRows } from './finish.logic';
import { dentalNextSittingLines, emptyRecordFacts, printsLine, profileBlock, profileLines, recordFacts, visibleBillLines } from './profile-lines.logic';

const DENTAL_CHOICES: readonly { key: EmrDentalNextInterval; label: string }[] = [
  { key: 'D3', label: '3 days' },
  { key: 'D7', label: '7 days' },
  { key: 'D14', label: '14 days' },
  { key: 'M1', label: '1 month' },
];

/**
 * "Check before completing" (SAH-51-1; mockup #DoctorGeneral): the patient and department, the summary
 * rows read from the record, what the sign will do (read from the server's check), the choices the
 * department asks for, the WhatsApp tick shown switched off, "Back to edit" and the confirm. Titles and
 * buttons follow the encounter: the IPD round signs today's note, an Emergency case signs the
 * assessment. Nothing is typed here, and nothing in it is logged. A modal with a focus trap; Escape
 * closes it and nothing is saved or signed.
 */
@Component({
  selector: 'app-finish-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule, IconComponent],
  styleUrl: './finish-dialog.component.scss',
  templateUrl: './finish-dialog.component.html',
})
export class FinishDialogComponent implements OnInit {
  protected readonly flow = inject(EmrFinishFlow);
  protected readonly store = inject(EmrRecordStore);
  private readonly api = inject(EmrApi);
  private readonly ref = inject<MatDialogRef<FinishDialogComponent>>(MatDialogRef);

  protected readonly whatsappNote = WHATSAPP_NOTE;
  protected readonly dentalChoices = DENTAL_CHOICES;
  protected readonly stageLabels = STAGE_LABELS;
  protected readonly outcomeLabel = ER_OUTCOME_LABEL;
  protected readonly formatPaise = formatInrPaise;
  protected readonly billText = billLineText;

  protected readonly record = this.store.record;
  protected readonly check = this.flow.check;
  protected readonly effects = computed(() => this.check()?.effects ?? {});
  protected readonly encounter = computed(() => this.record()?.encounterType ?? 'OPD_VISIT');
  protected readonly profile = this.store.profile;
  protected readonly titles = computed(() => finishTitles(this.encounter(), this.profile(), dentalAllDone(this.check()), profileBlock(this.effects(), 'physio')));
  protected readonly subtitle = computed(() => {
    const r = this.record();
    return r ? `${r.header.patientName} · ${r.layout.departmentName}` : '';
  });

  protected readonly rows = computed(() => {
    const r = this.record();
    if (!r) {
      return [];
    }
    const rows = summaryRows({
      profile: this.profile(),
      starterKey: r.layout.starterKey,
      encounter: r.encounterType,
      paper: this.store.paperModel(),
      values: this.store.values(),
      layout: this.store.visibleLayout(),
      states: this.store.states(),
      attrLabel: (kind, key) => choiceLabel(LIST_KINDS[kind].attr1, key ?? ''),
    });
    const orders = ordersLine(this.store.values());
    return orders ? [...rows, { label: 'New medicines', text: orders }] : rows;
  });

  protected readonly followUpLines = computed(() => {
    const f = this.effects().followUp;
    return f ? followUpEffectText(f) : [];
  });
  protected readonly patientRecord = computed(() => {
    const p = this.effects().patientRecord;
    return p ? { text: patientRecordText(p), removals: p.removals, conflict: p.conflict } : null;
  });
  protected readonly stageText = computed(() => {
    const s = this.effects().stage;
    if (!s) {
      return null;
    }
    return s.outcome === 'WILL_MOVE' && s.to ? `The visit moves to ${this.stageLabels[s.to]}.` : 'The visit has already moved on in the Queue.';
  });
  protected readonly printLine = computed(() => {
    const prints = this.effects().prints;
    if (prints ? !prints.includes('PRESCRIPTION') : this.encounter() !== 'OPD_VISIT') {
      return null;
    }
    return `Prescription prints in ${LANGUAGE_NAME[this.store.printLanguage()]} with English drug names.`;
  });
  protected readonly dentalDone = computed(() => dentalAllDone(this.check()));
  protected readonly dental = computed(() => profileBlock(this.effects(), 'dental'));
  protected readonly physio = computed(() => profileBlock(this.effects(), 'physio'));
  protected readonly dentalNext = computed(() => {
    const d = this.dental();
    return d ? dentalNextSittingLines(d) : [];
  });
  protected readonly billLines = computed(() => visibleBillLines(this.effects()));
  protected readonly facts = computed(() => {
    const r = this.record();
    return r ? recordFacts(r.layout.sections, this.store.values()) : emptyRecordFacts();
  });
  protected readonly lines = computed(() => profileLines(this.effects(), this.facts()));
  protected readonly extraPrintsLine = computed(() => printsLine(this.effects().prints));
  protected readonly triage = computed(() => this.effects().emergency ?? null);
  protected readonly reasons = this.flow.overrideReasons;

  ngOnInit(): void {
    if (this.profile() === 'physio' && this.encounter() === 'OPD_VISIT') {
      this.loadSlots();
    }
    if (this.encounter() === 'IPD_ROUND') {
      const id = this.record()?.id;
      if (id) {
        this.api
          .getIpdOrderOptions(id)
          .pipe(catchError(() => of(null)))
          .subscribe((o) => this.flow.overrideReasons.set(o?.overrideReasons ?? []));
      }
    }
  }

  private loadSlots(): void {
    const id = this.record()?.id;
    if (!id) {
      return;
    }
    this.api
      .listFreeSlots(id)
      .pipe(catchError(() => of([])))
      .subscribe((slots) => {
        this.flow.freeSlots.set(slots);
        if (slots.length > 0 && !this.flow.physioSlot()) {
          this.flow.physioSlot.set({ date: slots[0].date, time: slots[0].time });
          this.flow.recheck();
        }
      });
  }

  protected slotLabel(slot: { date: string; time: string }): string {
    return `${formatDisplayDate(slot.date)}, ${slot.time}`;
  }

  protected pickSlot(slot: { date: string; time: string }): void {
    this.flow.chooseSlot(slot);
  }

  protected pickDental(key: EmrDentalNextInterval): void {
    this.flow.dentalInterval.set(key);
    this.flow.recheck();
  }

  protected toggleRemoval(event: Event): void {
    this.flow.removeAllergy.set((event.target as HTMLInputElement).checked);
    this.flow.recheck();
  }

  protected pickReason(rowId: string, event: Event): void {
    const key = (event.target as HTMLSelectElement).value;
    this.flow.orderReasons.update((all) => ({ ...all, [rowId]: key }));
  }

  protected removeOrder(rowId: string): void {
    const section = this.store.layout().find((s) => s.fields.some((f) => f.component?.type === 'IPD_ORDERS'));
    const field = section?.fields.find((f) => f.component?.type === 'IPD_ORDERS');
    if (!section || !field) {
      return;
    }
    const current = this.store.values()[section.key]?.[field.key] as { rows?: { id: string }[] } | undefined;
    const rows = (current?.rows ?? []).filter((r) => r.id !== rowId);
    this.store.setValue(section.key, field.key, { component: 'IPD_ORDERS', schemaVersion: 1, rows });
    this.store.save('AUTO').subscribe(() => this.flow.recheck());
  }

  protected backToEdit(): void {
    this.ref.close();
  }

  protected confirm(): void {
    this.flow.sign(() => this.ref.close());
  }
}
