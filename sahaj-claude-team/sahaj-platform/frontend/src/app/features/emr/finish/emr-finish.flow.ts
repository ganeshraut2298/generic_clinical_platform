import { Injectable, Injector, computed, inject, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable, Subject, catchError, map, of, switchMap } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { newClientKey } from '../../../shared/client-key';
import { EmrApi } from '../emr.api';
import { IPD_READ_ONLY_TEXT, PHYSIO_SLOT_FIELD, detailOr, isOffline, problemSlug } from '../emr-errors';
import {
  EmrDentalNextInterval,
  EmrErOutcome,
  EmrFinishBlocker,
  EmrFinishCheck,
  EmrFinishChoices,
  EmrFinishProfileEffects,
  EmrFreeSlot,
  EmrKeyLabel,
  EmrSignOutcome,
} from '../emr-finish.model';
import { EmrRecordView } from '../emr.model';
import { EmrRecordStore } from '../record/emr-record.store';
import { ErOutcomeDialogComponent } from './er-outcome-dialog.component';
import { FinishDialogComponent } from './finish-dialog.component';
import { OFFLINE_SIGN, SIGN_ERROR, blockerMessage, dentalAllDone, firstBlockedSection } from './finish.logic';

/** What the page needs to know when the flow stops or ends. */
export type FinishEvent =
  | { kind: 'blocked'; message: string; sectionKey: string | null }
  | { kind: 'signed'; outcome: EmrSignOutcome; choices: EmrFinishChoices; record: EmrRecordView; sessionNo: number | null; profile: EmrFinishProfileEffects | null }
  | { kind: 'reload' };

const BLOCKS_THAT_STILL_OPEN: ReadonlySet<string> = new Set(['IPD_ORDER_BLOCKED', 'IPD_ORDER_NEEDS_REASON']);

/**
 * The finish step of an open EMR (SAH-51-1, SAH-51-2, SAH-67-1, SAH-67-2): the checks, the ER outcome
 * question, "Check before completing" and the sign. One instance per open screen. The sign carries an
 * Idempotency-Key made when Confirm is first tapped and kept for a retry with the same choices, so a
 * double tap or a lost connection never signs twice. Nothing here is stored in the browser or logged.
 */
@Injectable()
export class EmrFinishFlow {
  private readonly store = inject(EmrRecordStore);
  private readonly api = inject(EmrApi);
  private readonly dialog = inject(MatDialog);
  private readonly injector = inject(Injector);

  readonly events = new Subject<FinishEvent>();

  readonly check = signal<EmrFinishCheck | null>(null);
  readonly starting = signal(false);
  readonly checking = signal(false);
  readonly signing = signal(false);
  readonly signError = signal<string | null>(null);

  readonly erOutcome = signal<EmrErOutcome | null>(null);
  readonly dentalInterval = signal<EmrDentalNextInterval | null>('D7');
  readonly physioSlot = signal<{ date: string; time: string | null } | null>(null);
  readonly freeSlots = signal<EmrFreeSlot[] | null>(null);
  /** The server's refusal of the chosen physiotherapy slot (400 on choices.physio.nextSlot), shown on the picker. */
  readonly slotError = signal<string | null>(null);
  readonly removeAllergy = signal(false);
  /** The reasons SAH-31 allows for a medicine it accepts only with a reason. */
  readonly overrideReasons = signal<EmrKeyLabel[]>([]);
  /** rowId to the reason key chosen for a medicine SAH-31 accepts only with a reason. */
  readonly orderReasons = signal<Record<string, string>>({});

  private idempotencyKey: string | null = null;
  private keyedBody: string | null = null;
  private readonly rechecks = new Subject<void>();

  /** Confirm is disabled while a medicine is refused or still needs its reason (SAH-67-1). */
  readonly canConfirm = computed(() => {
    const check = this.check();
    if (!check || this.signing()) {
      return false;
    }
    // A refused slot is replaced before the next try, unless no slot is free any more.
    if (this.slotError() && !this.physioSlot() && (this.freeSlots()?.length ?? 0) > 0) {
      return false;
    }
    const orders = check.effects.ipdOrders ?? [];
    if (orders.some((o) => o.outcome === 'BLOCKED')) {
      return false;
    }
    if (orders.some((o) => o.outcome === 'NEEDS_REASON' && !this.orderReasons()[o.rowId])) {
      return false;
    }
    return check.blockers.every((b) => b.key === 'IPD_ORDER_NEEDS_REASON' || b.key === 'IPD_ORDER_BLOCKED' ? this.orderBlockerCleared(b) : false);
  });

  constructor() {
    this.rechecks
      .pipe(
        switchMap(() => {
          this.checking.set(true);
          return this.finishCheck();
        }),
      )
      .subscribe((check) => {
        this.checking.set(false);
        if (check) {
          this.check.set(check);
        }
      });
  }

  /** A blocker about an IPD order row is cleared by choosing a reason or removing the row; the rest by the server. */
  private orderBlockerCleared(blocker: EmrFinishBlocker): boolean {
    const orders = this.check()?.effects.ipdOrders ?? [];
    if (blocker.key === 'IPD_ORDER_BLOCKED') {
      return !orders.some((o) => o.outcome === 'BLOCKED');
    }
    return !orders.some((o) => o.outcome === 'NEEDS_REASON' && !this.orderReasons()[o.rowId]);
  }

  /** The choices the server needs; fields that do not apply to the encounter or profile are left out. */
  choices(): EmrFinishChoices {
    const record = this.store.record();
    const out: EmrFinishChoices = {};
    if (!record) {
      return out;
    }
    if (record.encounterType === 'ER_ASSESSMENT') {
      out.erOutcome = this.erOutcome();
    }
    if (record.encounterType === 'IPD_ROUND') {
      const reasons = this.orderReasons();
      const rows = (this.check()?.effects.ipdOrders ?? []).filter((o) => o.outcome === 'NEEDS_REASON' && reasons[o.rowId] && o.checkToken);
      if (rows.length > 0) {
        out.ipdOrderReasons = rows.map((o) => ({ rowId: o.rowId, overrideReasonKey: reasons[o.rowId], checkToken: o.checkToken as string }));
      }
    }
    if (record.encounterType === 'OPD_VISIT') {
      if (this.store.profile() === 'dental') {
        out.dental = { nextInterval: dentalAllDone(this.check()) ? null : this.dentalInterval() };
      }
      if (this.store.profile() === 'physio') {
        const slot = this.physioSlot();
        out.physio = { nextSlot: slot ? { date: slot.date, time: slot.time } : null };
      }
    }
    if (this.removeAllergy()) {
      out.confirmAllergyRemoval = true;
    }
    return out;
  }

  private finishCheck(): Observable<EmrFinishCheck | null> {
    const record = this.store.record();
    if (!record) {
      return of(null);
    }
    return this.api.finishCheck(record.id, this.choices()).pipe(
      catchError((error: unknown) => {
        this.signError.set(detailOr(error, 'We could not check the visit. Try again.'));
        return of(null);
      }),
    );
  }

  /** A choice in the dialog changed: the effects are worked out again (the latest answer wins). */
  recheck(): void {
    this.rechecks.next();
  }

  // ---- start: local check, ER outcome, save, server check, dialog ----

  begin(): void {
    const record = this.store.record();
    if (!record || this.starting() || record.readOnly || this.store.readOnly()) {
      return;
    }
    if (!this.store.ready()) {
      this.events.next({ kind: 'blocked', message: this.store.footer(), sectionKey: this.store.firstMissing() });
      return;
    }
    this.starting.set(true);
    this.signError.set(null);
    const proceed = (): void => this.saveThenCheck();
    if (record.encounterType === 'ER_ASSESSMENT' && !this.erOutcome()) {
      this.askOutcome().subscribe((outcome) => {
        if (outcome) {
          this.erOutcome.set(outcome);
          proceed();
        } else {
          this.starting.set(false);
        }
      });
      return;
    }
    proceed();
  }

  private askOutcome(): Observable<EmrErOutcome | null> {
    return this.dialog
      .open<ErOutcomeDialogComponent, { current: EmrErOutcome | null; admissionAdvised: boolean }, EmrErOutcome | null>(ErOutcomeDialogComponent, {
        panelClass: 'sheet-panel',
        width: '560px',
        maxWidth: '100vw',
        injector: this.injector,
        ariaLabelledBy: 'er-outcome-title',
        data: { current: this.erOutcome(), admissionAdvised: this.store.record()?.admissionAdvised ?? false },
      })
      .afterClosed()
      .pipe(map((r) => r ?? null));
  }

  private saveThenCheck(): void {
    const saved$ = this.store.dirty() ? this.store.save('AUTO') : of(true);
    saved$
      .pipe(
        switchMap((ok) => (ok ? this.finishCheck() : of(null))),
      )
      .subscribe((check) => {
        this.starting.set(false);
        if (!check) {
          if (this.signError()) {
            this.events.next({ kind: 'blocked', message: this.signError() as string, sectionKey: null });
          }
          return;
        }
        this.check.set(check);
        this.afterCheck(check);
      });
  }

  private afterCheck(check: EmrFinishCheck): void {
    const stops = check.blockers.filter((b) => !BLOCKS_THAT_STILL_OPEN.has(b.key));
    if (stops.length === 0) {
      this.openDialog();
      return;
    }
    if (stops.some((b) => b.key === 'ER_OUTCOME_REQUIRED' || b.key === 'PATIENT_REQUIRED')) {
      this.erOutcome.set(null);
    }
    this.events.next({ kind: 'blocked', message: blockerMessage(stops), sectionKey: firstBlockedSection(stops) });
  }

  private openDialog(): void {
    this.signError.set(null);
    this.slotError.set(null);
    this.dialog
      .open(FinishDialogComponent, {
        panelClass: 'sheet-panel',
        width: '620px',
        maxWidth: '100vw',
        injector: this.injector,
        ariaLabelledBy: 'finish-title',
        autoFocus: 'dialog',
        disableClose: false,
      })
      .afterClosed()
      .subscribe(() => {
        this.check.set(null);
      });
  }

  /** The ER outcome question again from inside the dialog ("Change"). */
  changeOutcome(): void {
    this.askOutcome().subscribe((outcome) => {
      if (outcome) {
        this.erOutcome.set(outcome);
        this.recheck();
      }
    });
  }

  // ---- sign ----

  /**
   * Confirm: one action. The key is made on the first tap and kept for a retry of the same choices; when
   * the choices change a new key is made (the server would refuse the same key with another body).
   */
  sign(onClosed: () => void): void {
    const record = this.store.record();
    if (!record || !this.canConfirm()) {
      return;
    }
    const choices = this.choices();
    const body = { revision: record.revision, choices };
    const hash = JSON.stringify(body);
    if (!this.idempotencyKey || this.keyedBody !== hash) {
      this.idempotencyKey = newClientKey();
      this.keyedBody = hash;
    }
    this.signing.set(true);
    this.store.signInFlight.set(true);
    this.signError.set(null);
    this.api.sign(record.id, body, this.idempotencyKey).subscribe({
      next: (result) => {
        this.signing.set(false);
        this.store.signInFlight.set(false);
        this.idempotencyKey = null;
        this.keyedBody = null;
        const block = this.check()?.effects.profile;
        const sessionNo = block?.profile === 'physio' ? block.sessionNo : null;
        this.store.adoptSigned(result.record);
        onClosed();
        this.events.next({ kind: 'signed', outcome: result.outcome, choices, record: result.record, sessionNo, profile: block ?? null });
      },
      error: (error: unknown) => {
        this.signing.set(false);
        this.store.signInFlight.set(false);
        this.signFailed(error, onClosed);
      },
    });
  }

  private signFailed(error: unknown, onClosed: () => void): void {
    const slug = problemSlug(error);
    if (isOffline(error)) {
      this.signError.set(OFFLINE_SIGN);
      return;
    }
    switch (slug) {
      case 'emr-not-ready': {
        const blockers = (error instanceof ApiError ? (error.problem.blockers as EmrFinishBlocker[] | undefined) : undefined) ?? [];
        onClosed();
        this.events.next({
          kind: 'blocked',
          message: blockers.length > 0 ? blockerMessage(blockers) : detailOr(error, 'Something is still needed before this visit can be signed.'),
          sectionKey: firstBlockedSection(blockers),
        });
        return;
      }
      case 'ipd-order-not-placed':
        this.signError.set(`${detailOr(error, 'A medicine could not be ordered.')} Check again.`);
        this.recheck();
        return;
      case 'emr-record-changed':
        this.signError.set('This EMR was changed elsewhere. Reload to see the latest.');
        this.store.conflict.set(true);
        return;
      case 'emr-record-held':
        onClosed();
        this.store.lostEditing.set({ reason: 'HELD', holderName: error instanceof ApiError ? (error.problem.holderName ?? null) : null });
        return;
      case 'emr-record-signed':
        onClosed();
        this.store.lostEditing.set({ reason: 'SIGNED', holderName: null });
        this.store.saveError.set(detailOr(error, 'This visit is already signed.'));
        this.events.next({ kind: 'reload' });
        return;
      case 'ipd-read-only':
        // IPD went Read-only: nothing can be signed. The entries stay on screen, now read-only.
        onClosed();
        this.signError.set(null);
        this.store.markIpdReadOnly();
        this.events.next({ kind: 'blocked', message: detailOr(error, IPD_READ_ONLY_TEXT), sectionKey: null });
        return;
      default: {
        const slotError = error instanceof ApiError ? error.fieldErrors.find((f) => f.field === PHYSIO_SLOT_FIELD) : undefined;
        if (slotError) {
          // The chosen slot is not a real free slot: shown on the picker, the free slots are read again,
          // every other choice stays as the doctor set it.
          this.slotError.set(slotError.message);
          this.refreshSlots();
          return;
        }
        this.signError.set(`${detailOr(error, SIGN_ERROR)}`);
      }
    }
  }

  /** The free slots again after the server refused the chosen one; a chosen slot that is gone is dropped. */
  refreshSlots(): void {
    const id = this.store.record()?.id;
    if (!id) {
      return;
    }
    this.api
      .listFreeSlots(id)
      .pipe(catchError(() => of(null)))
      .subscribe((slots) => {
        if (!slots) {
          return;
        }
        this.freeSlots.set(slots);
        const chosen = this.physioSlot();
        if (chosen && !slots.some((s) => s.date === chosen.date && s.time === chosen.time)) {
          this.physioSlot.set(null);
        }
        this.recheck();
      });
  }

  /** The doctor picked a slot: the refusal on the picker is cleared. */
  chooseSlot(slot: { date: string; time: string | null }): void {
    this.slotError.set(null);
    this.physioSlot.set({ date: slot.date, time: slot.time });
    this.recheck();
  }
}
