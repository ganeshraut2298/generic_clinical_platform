import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiError } from '../../core/http/problem-detail';
import { loadErrorMessage, saveErrorMessage } from './pharmacy-display.util';
import { PharmacyApi } from './pharmacy.api';
import { MyStore, NO_ACCESS, PharmacyContext } from './pharmacy.model';

export type ContextState = 'loading' | 'ready' | 'error' | 'no-access';

/**
 * The one capability answer of Pharmacy & Inventory (`GET /context`, ADR-028 D5), read when the
 * entry opens and after a working-store change. Every screen shows only what `capabilities` lists:
 * anything not listed is hidden, never greyed out. The server still checks every call.
 *
 * It lives only while the Pharmacy & Inventory page is open (`start()` on open, `reset()` on
 * leave), so nothing stays behind between two people who use the same browser.
 */
@Injectable({ providedIn: 'root' })
export class PharmacyContextStore {
  private readonly api = inject(PharmacyApi);

  private readonly contextSig = signal<PharmacyContext | null>(null);
  private readonly stateSig = signal<ContextState>('loading');
  private readonly messageSig = signal<string>('');
  private readonly pickingSig = signal(false);
  private readonly pickErrorSig = signal<string | null>(null);

  readonly context = this.contextSig.asReadonly();
  readonly state = this.stateSig.asReadonly();
  /** Why the page is not ready: the server's sentence for a 403, else "Could not load. Try again." */
  readonly message = this.messageSig.asReadonly();
  readonly picking = this.pickingSig.asReadonly();
  readonly pickError = this.pickErrorSig.asReadonly();

  readonly unitName = computed(() => this.contextSig()?.unit.name ?? '');
  /** "<name> · <role label>" shown beside the working-store select (mockup 16, OQ-78-5). */
  readonly callerLabel = computed(() => {
    const caller = this.contextSig()?.caller;
    return caller ? `${caller.name} · ${caller.roleLabel}` : '';
  });
  readonly integrationMode = computed(() => this.contextSig()?.unit.integrationMode ?? null);
  readonly stores = computed<MyStore[]>(() => this.contextSig()?.stores ?? []);
  readonly workingStoreId = computed(() => this.contextSig()?.workingStoreId ?? null);
  readonly workingStore = computed(
    () => this.stores().find((s) => s.storeId === this.workingStoreId()) ?? null,
  );
  readonly mustPickWorkingStore = computed(() => this.contextSig()?.mustPickWorkingStore ?? false);
  /** Not mapped to any store (an owner with no mapping): the select is replaced by a sentence. */
  readonly notMapped = computed(() => this.stores().length === 0);

  private readonly capabilities = computed(() => new Set(this.contextSig()?.capabilities ?? []));
  readonly canEditSetup = computed(
    () => this.contextSig()?.caller.setupAccess === 'EDIT' && this.capabilities().has('SETUP_EDIT'),
  );
  /** Upload an opening-stock file (78-7): the server lists the capability. */
  readonly canUploadOpeningStock = computed(() => this.capabilities().has('OPENING_STOCK_UPLOAD'));
  /** Approve or reject an opening-stock file (78-7). The server still refuses the uploader. */
  readonly canApproveOpeningStock = computed(() => this.capabilities().has('OPENING_STOCK_APPROVE'));
  /** The Inventory tab (SAH-79): the owner or any person mapped to a store; the server lists INVENTORY. */
  readonly canInventory = computed(() => this.capabilities().has('INVENTORY'));
  /** The Approvals inbox (79-2): the owner, or the HOD or current delegate of a store. */
  readonly canApprovalsInbox = computed(() => this.capabilities().has('APPROVALS_INBOX'));
  /** Purchase orders, receiving stock and vendor returns (SAH-79 work package E): the server lists PURCHASE. */
  readonly canPurchase = computed(() => this.capabilities().has('PURCHASE'));
  /** The Stock ledger needs the stock reports capability of the catalogue rules (79-18). */
  readonly canStockReports = computed(() => this.capabilities().has('STOCK_REPORTS'));

  /** Store-bound capability of the working store (the server lists them per store in `access`). */
  readonly canReadStock = computed(() => this.workingStore()?.access.readStock === true);
  readonly canAdjustStock = computed(() => this.workingStore()?.access.adjustStock === true);
  readonly canHandleScheduleX = computed(() => this.workingStore()?.access.handleScheduleX === true);
  readonly canStartCount = computed(() => this.workingStore()?.access.startCount === true);
  /** Work package F: the capabilities of the working store, as the server lists them in `access`. */
  readonly canRaiseIndent = computed(() => this.workingStore()?.access.raiseIndent === true);
  readonly canReceive = computed(() => this.workingStore()?.access.receive === true);
  readonly canReceiveScheduleX = computed(() => this.workingStore()?.access.receiveScheduleX === true);
  readonly canDispatch = computed(
    () => this.workingStore()?.type === 'CENTRAL' && this.workingStore()?.access.purchase === true,
  );
  readonly canAcceptReturn = computed(() => this.workingStore()?.access.acceptReturn === true);
  /** Work packages G and H: capabilities of the working store and of the unit. */
  readonly canReturnDamage = computed(() => this.workingStore()?.access.returnDamage === true);
  readonly canDispatchTransfer = computed(() => this.workingStore()?.access.dispatchTransfer === true);
  readonly canConsume = computed(() => this.workingStore()?.access.consume === true);
  /** The Patient-wise tab: only when the context lists CONSUMPTION_PATIENT (Integrated, IPD reader connected, linked ward). */
  readonly canConsumePatient = computed(() => this.capabilities().has('CONSUMPTION_PATIENT'));
  readonly canCount = computed(() => this.workingStore()?.access.count === true);
  readonly canReviewCount = computed(() => this.workingStore()?.access.reviewCount === true);
  /** Reports: the catalogue decides what each person may run. */
  readonly canReports = computed(
    () => this.capabilities().has('STOCK_REPORTS') || this.capabilities().has('PURCHASE_REPORTS'),
  );
  /** SAH-80 work package I: the Pharmacy tab and POS billing; the sell capability of the working store. */
  readonly canPharmacyTab = computed(() => this.capabilities().has('PHARMACY'));
  readonly canPos = computed(() => this.capabilities().has('POS'));
  readonly canSalesReturn = computed(() => this.capabilities().has('SALES_RETURN') && this.workingStore()?.access.salesReturn === true);
  readonly canRegisters = computed(() => this.capabilities().has('REGISTERS') && this.workingStore()?.access.readRegisters === true);
  /** The licences card: the owner or a pharmacist (capability COMPLIANCE). */
  readonly canCompliance = computed(() => this.capabilities().has('COMPLIANCE'));
  /** The Command Center tiles (the owner, a pharmacist, an approver or a cashier; the server leaves out what a person may not see). */
  readonly canCommandCentre = computed(() => this.capabilities().has('COMMAND_CENTRE'));
  readonly canSell = computed(() => this.workingStore()?.access.sell === true);
  /** Patient pick at the counter: Integrated only, when the context lists the patient port as available. */
  readonly canPatientLink = computed(() => this.capabilities().has('PATIENT_LINK'));
  /** The OPD and IPD dispensing queues: listed only in Integrated mode (they say "Not connected" until TP-2 and TP-4 are). */
  readonly canOpdQueue = computed(() => this.capabilities().has('OPD_QUEUE'));
  readonly canIpdQueue = computed(() => this.capabilities().has('IPD_QUEUE'));
  /** Raises an indent / buys: only the links of the low stock list need them (79-3). */
  readonly isCentralStore = computed(() => this.workingStore()?.type === 'CENTRAL');

  /** "Post to stock" is shown for the import's own store when the server says the caller may post there (79-1). */
  canPostOpeningStockAt(storeId: string): boolean {
    return this.stores().find((s) => s.storeId === storeId)?.access.postOpeningStock === true;
  }

  /** The Inventory setup tab: shown only when the server lists a setup capability. */
  readonly canViewSetup = computed(() => {
    const access = this.contextSig()?.caller.setupAccess;
    return (
      access !== undefined &&
      access !== 'NONE' &&
      (this.capabilities().has('SETUP_VIEW') || this.capabilities().has('SETUP_EDIT'))
    );
  });
  readonly viewOnly = computed(() => this.canViewSetup() && !this.canEditSetup());

  /** Reads `GET /context`. The page is "loading" until the server has answered. */
  start(): void {
    this.stateSig.set('loading');
    this.messageSig.set('');
    this.contextSig.set(null);
    this.api.context().subscribe({
      next: (context) => {
        this.contextSig.set(context);
        this.stateSig.set('ready');
      },
      error: (error: unknown) => {
        this.contextSig.set(null);
        if (error instanceof ApiError && error.status === 403) {
          this.messageSig.set(error.problem.detail ?? NO_ACCESS);
          this.stateSig.set('no-access');
          return;
        }
        this.messageSig.set(loadErrorMessage(error));
        this.stateSig.set('error');
      },
    });
  }

  /**
   * Re-reads the context in place after a store or a mapping changed (the signed-in owner may now
   * be mapped to another store). A failure keeps what is shown; the next full read corrects it.
   */
  refresh(): void {
    this.api.context().subscribe({
      next: (context) => this.contextSig.set(context),
      error: () => undefined,
    });
  }

  /** Saves the working store (`PUT /context/working-store`); the answer is the new context. */
  pickWorkingStore(storeId: string): void {
    if (this.pickingSig() || storeId === this.workingStoreId()) {
      return;
    }
    this.pickingSig.set(true);
    this.pickErrorSig.set(null);
    this.api.setWorkingStore(storeId).subscribe({
      next: (context) => {
        this.contextSig.set(context);
        this.pickingSig.set(false);
      },
      error: (error: unknown) => {
        this.pickingSig.set(false);
        this.pickErrorSig.set(saveErrorMessage(error));
        // A 404 means the store was lost (unmapped or switched off): ask again from a fresh read.
        if (error instanceof ApiError && error.status === 404) {
          this.refresh();
        }
      },
    });
  }

  reset(): void {
    this.contextSig.set(null);
    this.stateSig.set('loading');
    this.messageSig.set('');
    this.pickErrorSig.set(null);
  }
}
