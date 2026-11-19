import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { API_BASE_URL } from '../../core/config/api-config';
import {
  ApprovalRules,
  ApprovalRulesInput,
  DocType,
  DocumentSeries,
  DocumentSeriesInput,
  DuplicateCandidate,
  ImportCreate,
  ImportPage,
  ImportStatus,
  ListKey,
  LinesInput,
  LinesResult,
  LocalSettingsInput,
  OpeningStockImport,
  OpeningStockLinePage,
  OwnProductInput,
  OwnProductUpdate,
  PharmacyContext,
  PresetInput,
  PrintDocType,
  PrintTemplate,
  PrintTemplateInput,
  ProductDetail,
  ProductOptions,
  ProductPage,
  ProductSource,
  ReasonInput,
  ReasonLists,
  RejectInput,
  SetupSummary,
  StaffCandidate,
  StaffMapping,
  StaffMappingInput,
  StaffMappingUpdate,
  Store,
  StoreCreate,
  StoreList,
  StoreRole,
  StoreUpdate,
  UnitSettings,
  UnitSettingsInput,
  Vendor,
  VendorInput,
  VendorPage,
  VendorStatusFilter,
  VendorUpdate,
} from './pharmacy.model';
import {
  ActiveReasons,
  ApprovalDecisionInput,
  ApprovalInboxPage,
  ApprovalRequest,
  InboxFilter,
  LowStockPage,
  OpeningStockPostingPreview,
  ProductStock,
  ProductStockByStore,
  ReasonListKey,
  SecondSignerCandidate,
  StockAdjustment,
  StockAdjustmentCreate,
  StockAdjustmentPage,
  AdjustmentStatus,
  StockBatchPage,
  StockLedgerPage,
  StockLedgerQuery,
  StockOverview,
  StockView,
} from './pharmacy-inventory.model';
import {
  ClientKeyInput,
  CreditNoteInput,
  DebitNote,
  DebitNoteCreate,
  DebitNotePage,
  DebitNoteStatus,
  DebitNoteUpdate,
  GoodsReceipt,
  GoodsReceiptCreate,
  GoodsReceiptPage,
  GoodsReceiptStatus,
  GoodsReceiptUpdate,
  HandOverInput,
  MarkSentInput,
  PostWithSignerInput,
  PurchaseOrder,
  PurchaseOrderCreate,
  PurchaseOrderPage,
  PurchaseOrderQuery,
  PurchaseOrderUpdate,
  PurchaseOrdersFromSuggestionsInput,
  ReorderSuggestionPage,
  VendorReturnEligible,
  VersionClientKeyInput,
  VersionInput,
  VersionReasonInput,
} from './pharmacy-buying.model';
import {
  AcceptReturnInput,
  Dispatch,
  DispatchPage,
  DispatchReceiptInput,
  FefoPick,
  Indent,
  IndentCreate,
  IndentDispatchInput,
  IndentPage,
  IndentStatus,
  IndentUpdate,
  IndentView,
  NoteInput,
  ReplacementOption,
  ReturnItem,
  ReturnItemStatus,
  ReturnSide,
  SettleShortageInput,
  Shortage,
  ShortagePage,
  ShortageStatus,
  WaitingForActionPage,
} from './pharmacy-indent.model';

/**
 * The only place that calls the Pharmacy & Inventory API (`docs/api/pharmacy.yaml` 0.2.0, the SAH-78 operations):
 * context, working store, setup summary, unit settings, stores, staff mapping, products, opening stock (work
 * packages A and B), vendors, approval rules, numbering, reasons and print templates (work package C). The
 * clinic always comes from the session; no call carries a tenant or unit id.
 */
@Injectable({ providedIn: 'root' })
export class PharmacyApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/pharmacy`;

  // ---- 78-1 / 78-4 context

  context(): Observable<PharmacyContext> {
    return this.http.get<PharmacyContext>(`${this.baseUrl}/context`);
  }

  setWorkingStore(storeId: string): Observable<PharmacyContext> {
    return this.http.put<PharmacyContext>(`${this.baseUrl}/context/working-store`, { storeId });
  }

  setupSummary(): Observable<SetupSummary> {
    return this.http.get<SetupSummary>(`${this.baseUrl}/setup-summary`);
  }

  // ---- 78-2 unit settings

  unitSettings(): Observable<UnitSettings> {
    return this.http.get<UnitSettings>(`${this.baseUrl}/unit-settings`);
  }

  saveUnitSettings(input: UnitSettingsInput): Observable<UnitSettings> {
    return this.http.put<UnitSettings>(`${this.baseUrl}/unit-settings`, input);
  }

  // ---- 78-3 stores

  stores(includeInactive = false): Observable<StoreList> {
    const params = includeInactive ? new HttpParams().set('includeInactive', 'true') : undefined;
    return this.http.get<StoreList>(`${this.baseUrl}/stores`, { params });
  }

  store(storeId: string): Observable<Store> {
    return this.http.get<Store>(`${this.baseUrl}/stores/${storeId}`);
  }

  createStore(input: StoreCreate): Observable<Store> {
    return this.http.post<Store>(`${this.baseUrl}/stores`, input);
  }

  updateStore(storeId: string, input: StoreUpdate): Observable<Store> {
    return this.http.put<Store>(`${this.baseUrl}/stores/${storeId}`, input);
  }

  switchOffStore(storeId: string): Observable<Store> {
    return this.http.post<Store>(`${this.baseUrl}/stores/${storeId}/switch-off`, null);
  }

  // ---- 78-4 staff mapping

  /**
   * Team members who can be mapped. Without a store (storeId null, pharmacy.yaml 0.1.1) the server
   * filters by team role only and alreadyMappedHere is always false: the store form uses it before the
   * store exists. The query parameter is left out, never sent empty.
   */
  staffCandidates(storeId: string | null, storeRole?: StoreRole): Observable<StaffCandidate[]> {
    let params = new HttpParams();
    if (storeId) {
      params = params.set('storeId', storeId);
    }
    if (storeRole) {
      params = params.set('storeRole', storeRole);
    }
    return this.http
      .get<{ items: StaffCandidate[] }>(`${this.baseUrl}/staff-candidates`, { params })
      .pipe(map((answer) => answer.items));
  }

  storeStaff(storeId: string): Observable<StaffMapping[]> {
    return this.http
      .get<{ items: StaffMapping[] }>(`${this.baseUrl}/stores/${storeId}/staff`)
      .pipe(map((answer) => answer.items));
  }

  mapStaff(storeId: string, input: StaffMappingInput): Observable<StaffMapping> {
    return this.http.post<StaffMapping>(`${this.baseUrl}/stores/${storeId}/staff`, input);
  }

  changeStaffMapping(
    storeId: string,
    mappingId: string,
    input: StaffMappingUpdate,
  ): Observable<StaffMapping> {
    return this.http.put<StaffMapping>(
      `${this.baseUrl}/stores/${storeId}/staff/${mappingId}`,
      input,
    );
  }

  removeStaffMapping(storeId: string, mappingId: string): Observable<StaffMapping> {
    return this.http.post<StaffMapping>(
      `${this.baseUrl}/stores/${storeId}/staff/${mappingId}/remove`,
      null,
    );
  }

  // ---- 78-5 / 78-6 products

  /** Global and own products together. Under 2 characters the server answers the first page unfiltered. */
  searchProducts(
    query: { q?: string; source?: ProductSource; page?: number; size?: number } = {},
  ): Observable<ProductPage> {
    let params = new HttpParams();
    if (query.q) {
      params = params.set('q', query.q);
    }
    if (query.source) {
      params = params.set('source', query.source);
    }
    if (query.page !== undefined) {
      params = params.set('page', query.page);
    }
    if (query.size !== undefined) {
      params = params.set('size', query.size);
    }
    return this.http.get<ProductPage>(`${this.baseUrl}/products`, { params });
  }

  product(productId: string): Observable<ProductDetail> {
    return this.http.get<ProductDetail>(`${this.baseUrl}/products/${productId}`);
  }

  createOwnProduct(input: OwnProductInput): Observable<ProductDetail> {
    return this.http.post<ProductDetail>(`${this.baseUrl}/products`, input);
  }

  updateOwnProduct(productId: string, input: OwnProductUpdate): Observable<ProductDetail> {
    return this.http.put<ProductDetail>(`${this.baseUrl}/products/${productId}`, input);
  }

  /** The duplicate check without saving; run when the owner leaves salt, strength or form. */
  findDuplicates(
    salt: string,
    strength: string,
    form: string,
    excludeProductId?: string,
  ): Observable<DuplicateCandidate[]> {
    let params = new HttpParams().set('salt', salt).set('strength', strength).set('form', form);
    if (excludeProductId) {
      params = params.set('excludeProductId', excludeProductId);
    }
    return this.http
      .get<{ candidates: DuplicateCandidate[] }>(`${this.baseUrl}/products/duplicates`, { params })
      .pipe(map((answer) => answer.candidates));
  }

  productOptions(): Observable<ProductOptions> {
    return this.http.get<ProductOptions>(`${this.baseUrl}/product-options`);
  }

  /** Adopt a global product for this unit; idempotent (200 when already adopted, 201 when created). */
  adoptProduct(catalogueProductId: string): Observable<ProductDetail> {
    return this.http.post<ProductDetail>(
      `${this.baseUrl}/catalogue-products/${catalogueProductId}/adopt`,
      null,
    );
  }

  saveLocalSettings(productId: string, input: LocalSettingsInput): Observable<ProductDetail> {
    return this.http.put<ProductDetail>(`${this.baseUrl}/products/${productId}/local-settings`, input);
  }

  // ---- 78-7 opening stock

  /** The CSV template as text. It is fixed content: no store, product or patient data. */
  openingStockTemplate(): Observable<string> {
    return this.http.get(`${this.baseUrl}/opening-stock/template`, { responseType: 'text' });
  }

  /** The owner's approval list: imports of every store, newest first. */
  allOpeningStockImports(
    status?: ImportStatus,
    page = 0,
    size = 20,
  ): Observable<ImportPage> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (status) {
      params = params.set('status', status);
    }
    return this.http.get<ImportPage>(`${this.baseUrl}/opening-stock-imports`, { params });
  }

  openingStockImports(storeId: string, page = 0, size = 20): Observable<ImportPage> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ImportPage>(`${this.baseUrl}/stores/${storeId}/opening-stock-imports`, {
      params,
    });
  }

  startOpeningStockImport(storeId: string, input: ImportCreate): Observable<OpeningStockImport> {
    return this.http.post<OpeningStockImport>(
      `${this.baseUrl}/stores/${storeId}/opening-stock-imports`,
      input,
    );
  }

  openingStockImport(storeId: string, importId: string): Observable<OpeningStockImport> {
    return this.http.get<OpeningStockImport>(
      `${this.baseUrl}/stores/${storeId}/opening-stock-imports/${importId}`,
    );
  }

  openingStockLines(
    storeId: string,
    importId: string,
    options: { withIssues?: boolean; page?: number; size?: number } = {},
  ): Observable<OpeningStockLinePage> {
    let params = new HttpParams();
    if (options.withIssues) {
      params = params.set('withIssues', 'true');
    }
    if (options.page !== undefined) {
      params = params.set('page', options.page);
    }
    if (options.size !== undefined) {
      params = params.set('size', options.size);
    }
    return this.http.get<OpeningStockLinePage>(
      `${this.baseUrl}/stores/${storeId}/opening-stock-imports/${importId}/lines`,
      { params },
    );
  }

  /** Up to 200 rows as text; the server parses and checks them. */
  addOpeningStockLines(
    storeId: string,
    importId: string,
    input: LinesInput,
  ): Observable<LinesResult> {
    return this.http.post<LinesResult>(
      `${this.baseUrl}/stores/${storeId}/opening-stock-imports/${importId}/lines`,
      input,
    );
  }

  submitOpeningStockImport(storeId: string, importId: string): Observable<OpeningStockImport> {
    return this.http.post<OpeningStockImport>(
      `${this.baseUrl}/stores/${storeId}/opening-stock-imports/${importId}/submit`,
      null,
    );
  }

  approveOpeningStockImport(storeId: string, importId: string): Observable<OpeningStockImport> {
    return this.http.post<OpeningStockImport>(
      `${this.baseUrl}/stores/${storeId}/opening-stock-imports/${importId}/approve`,
      null,
    );
  }

  rejectOpeningStockImport(
    storeId: string,
    importId: string,
    input: RejectInput,
  ): Observable<OpeningStockImport> {
    return this.http.post<OpeningStockImport>(
      `${this.baseUrl}/stores/${storeId}/opening-stock-imports/${importId}/reject`,
      input,
    );
  }

  // ---- 78-8 vendors

  vendors(
    query: { q?: string; status?: VendorStatusFilter; suppliesStoreId?: string; page?: number; size?: number } = {},
  ): Observable<VendorPage> {
    let params = new HttpParams();
    if (query.q) {
      params = params.set('q', query.q);
    }
    if (query.status) {
      params = params.set('status', query.status);
    }
    if (query.suppliesStoreId) {
      params = params.set('suppliesStoreId', query.suppliesStoreId);
    }
    if (query.page !== undefined) {
      params = params.set('page', query.page);
    }
    if (query.size !== undefined) {
      params = params.set('size', query.size);
    }
    return this.http.get<VendorPage>(`${this.baseUrl}/vendors`, { params });
  }

  createVendor(input: VendorInput): Observable<Vendor> {
    return this.http.post<Vendor>(`${this.baseUrl}/vendors`, input);
  }

  updateVendor(vendorId: string, input: VendorUpdate): Observable<Vendor> {
    return this.http.put<Vendor>(`${this.baseUrl}/vendors/${vendorId}`, input);
  }

  switchOffVendor(vendorId: string): Observable<Vendor> {
    return this.http.post<Vendor>(`${this.baseUrl}/vendors/${vendorId}/switch-off`, null);
  }

  /** 0.2.1: an INACTIVE vendor becomes ACTIVE (or BLOCKED when its licence has expired). No body; idempotent. */
  switchOnVendor(vendorId: string): Observable<Vendor> {
    return this.http.post<Vendor>(`${this.baseUrl}/vendors/${vendorId}/switch-on`, null);
  }

  // ---- 78-9 approval rules

  approvalRules(): Observable<ApprovalRules> {
    return this.http.get<ApprovalRules>(`${this.baseUrl}/approval-rules`);
  }

  saveApprovalRules(input: ApprovalRulesInput): Observable<ApprovalRules> {
    return this.http.put<ApprovalRules>(`${this.baseUrl}/approval-rules`, input);
  }

  applyApprovalPreset(input: PresetInput): Observable<ApprovalRules> {
    return this.http.post<ApprovalRules>(`${this.baseUrl}/approval-rules/preset`, input);
  }

  // ---- 78-10 numbering, reasons, print templates

  documentSeries(): Observable<DocumentSeries[]> {
    return this.http
      .get<{ items: DocumentSeries[] }>(`${this.baseUrl}/document-series`)
      .pipe(map((answer) => answer.items));
  }

  saveDocumentSeries(docType: DocType, input: DocumentSeriesInput): Observable<DocumentSeries> {
    return this.http.put<DocumentSeries>(`${this.baseUrl}/document-series/${docType}`, input);
  }

  reasonLists(): Observable<ReasonLists> {
    return this.http.get<ReasonLists>(`${this.baseUrl}/reason-lists`);
  }

  addReason(listKey: ListKey, input: ReasonInput): Observable<ReasonLists> {
    return this.http.post<ReasonLists>(`${this.baseUrl}/reason-lists/${listKey}/reasons`, input);
  }

  switchOffReason(listKey: ListKey, reasonKey: string): Observable<ReasonLists> {
    return this.http.post<ReasonLists>(
      `${this.baseUrl}/reason-lists/${listKey}/reasons/${reasonKey}/switch-off`,
      null,
    );
  }

  switchOnReason(listKey: ListKey, reasonKey: string): Observable<ReasonLists> {
    return this.http.post<ReasonLists>(
      `${this.baseUrl}/reason-lists/${listKey}/reasons/${reasonKey}/switch-on`,
      null,
    );
  }

  printTemplates(): Observable<PrintTemplate[]> {
    return this.http
      .get<{ items: PrintTemplate[] }>(`${this.baseUrl}/print-templates`)
      .pipe(map((answer) => answer.items));
  }

  savePrintTemplate(docType: PrintDocType, input: PrintTemplateInput): Observable<PrintTemplate> {
    return this.http.put<PrintTemplate>(`${this.baseUrl}/print-templates/${docType}`, input);
  }

  // ---- SAH-79 work package D (pharmacy.yaml 0.3.0)

  /** 79-1: what "Post to stock" will post. Writes nothing. */
  previewOpeningStockPosting(storeId: string, importId: string): Observable<OpeningStockPostingPreview> {
    return this.http.get<OpeningStockPostingPreview>(
      `${this.baseUrl}/stores/${storeId}/opening-stock-imports/${importId}/posting-preview`,
    );
  }

  /** 79-1: posts an approved import once, all or nothing. The same clientKey replays the same answer. */
  postOpeningStock(storeId: string, importId: string, clientKey: string): Observable<OpeningStockImport> {
    return this.http.post<OpeningStockImport>(
      `${this.baseUrl}/stores/${storeId}/opening-stock-imports/${importId}/post`,
      { clientKey },
    );
  }

  /** 79-2: everything waiting for the caller's decision (not store-bound; the server works out who may see it). */
  approvalInbox(
    query: { type?: InboxFilter; page?: number; size?: number } = {},
  ): Observable<ApprovalInboxPage> {
    let params = new HttpParams();
    if (query.type && query.type !== 'ALL') {
      params = params.set('type', query.type);
    }
    if (query.page !== undefined) {
      params = params.set('page', query.page);
    }
    if (query.size !== undefined) {
      params = params.set('size', query.size);
    }
    return this.http.get<ApprovalInboxPage>(`${this.baseUrl}/approvals/inbox`, { params });
  }

  approvalRequest(requestId: string): Observable<ApprovalRequest> {
    return this.http.get<ApprovalRequest>(`${this.baseUrl}/approvals/${requestId}`);
  }

  decideApproval(requestId: string, input: ApprovalDecisionInput): Observable<ApprovalRequest> {
    return this.http.post<ApprovalRequest>(`${this.baseUrl}/approvals/${requestId}/decision`, input);
  }

  // ---- 79-3 / 79-4 stock

  stockOverview(storeId: string): Observable<StockOverview> {
    return this.http.get<StockOverview>(`${this.baseUrl}/stores/${storeId}/stock/overview`);
  }

  stockBatches(
    storeId: string,
    query: { view?: StockView; withinDays?: 30 | 60 | 90; q?: string; page?: number; size?: number } = {},
  ): Observable<StockBatchPage> {
    let params = new HttpParams();
    if (query.view && query.view !== 'ALL') {
      params = params.set('view', query.view);
    }
    if (query.view === 'NEAR_EXPIRY' && query.withinDays) {
      params = params.set('withinDays', query.withinDays);
    }
    if (query.q) {
      params = params.set('q', query.q);
    }
    if (query.page !== undefined) {
      params = params.set('page', query.page);
    }
    if (query.size !== undefined) {
      params = params.set('size', query.size);
    }
    return this.http.get<StockBatchPage>(`${this.baseUrl}/stores/${storeId}/stock/batches`, { params });
  }

  productStock(storeId: string, productId: string): Observable<ProductStock> {
    return this.http.get<ProductStock>(`${this.baseUrl}/stores/${storeId}/stock/products/${productId}`);
  }

  lowStock(storeId: string, query: { page?: number; size?: number } = {}): Observable<LowStockPage> {
    let params = new HttpParams();
    if (query.page !== undefined) {
      params = params.set('page', query.page);
    }
    if (query.size !== undefined) {
      params = params.set('size', query.size);
    }
    return this.http.get<LowStockPage>(`${this.baseUrl}/stores/${storeId}/stock/low-stock`, { params });
  }

  /** 79-4 "All stores": one row per store the caller can reach, and the unit total. */
  productStockAllStores(productId: string): Observable<ProductStockByStore> {
    return this.http.get<ProductStockByStore>(`${this.baseUrl}/stock/products/${productId}/stores`);
  }

  /** 79-4 "My store": the same row for one store. */
  productStockMyStore(storeId: string, productId: string): Observable<ProductStockByStore> {
    return this.http.get<ProductStockByStore>(
      `${this.baseUrl}/stores/${storeId}/stock/products/${productId}/states`,
    );
  }

  // ---- 79-5 stock adjustment

  stockAdjustments(
    storeId: string,
    query: { status?: AdjustmentStatus; page?: number; size?: number } = {},
  ): Observable<StockAdjustmentPage> {
    let params = new HttpParams();
    if (query.status) {
      params = params.set('status', query.status);
    }
    if (query.page !== undefined) {
      params = params.set('page', query.page);
    }
    if (query.size !== undefined) {
      params = params.set('size', query.size);
    }
    return this.http.get<StockAdjustmentPage>(`${this.baseUrl}/stores/${storeId}/stock-adjustments`, {
      params,
    });
  }

  stockAdjustment(storeId: string, adjustmentId: string): Observable<StockAdjustment> {
    return this.http.get<StockAdjustment>(
      `${this.baseUrl}/stores/${storeId}/stock-adjustments/${adjustmentId}`,
    );
  }

  createStockAdjustment(storeId: string, input: StockAdjustmentCreate): Observable<StockAdjustment> {
    return this.http.post<StockAdjustment>(`${this.baseUrl}/stores/${storeId}/stock-adjustments`, input);
  }

  /** The people who may sign a Schedule X handling in this store as the second signer, never the caller. */
  secondSigners(storeId: string): Observable<SecondSignerCandidate[]> {
    return this.http
      .get<{ items: SecondSignerCandidate[] }>(`${this.baseUrl}/stores/${storeId}/second-signers`)
      .pipe(map((answer) => answer.items));
  }

  /** The active reasons of the named lists (the chips of the Phase 2 screens). */
  activeReasons(lists: ReasonListKey[]): Observable<ActiveReasons> {
    const params = new HttpParams().set('lists', lists.join(','));
    return this.http.get<ActiveReasons>(`${this.baseUrl}/reasons`, { params });
  }

  /** The stock ledger of one product (operation tagged SAH-79-18, work package H): one line per movement. */
  stockLedger(query: StockLedgerQuery): Observable<StockLedgerPage> {
    let params = new HttpParams().set('productId', query.productId).set('from', query.from).set('to', query.to);
    if (query.storeId) {
      params = params.set('storeId', query.storeId);
    }
    if (query.page !== undefined) {
      params = params.set('page', query.page);
    }
    if (query.size !== undefined) {
      params = params.set('size', query.size);
    }
    return this.http.get<StockLedgerPage>(`${this.baseUrl}/reports/stock-ledger`, { params });
  }

  // ---- SAH-79 work package E: purchase orders (79-6), goods receipts (79-7), debit notes (79-8)

  reorderSuggestions(storeId: string, page = 0, size = 50): Observable<ReorderSuggestionPage> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ReorderSuggestionPage>(`${this.baseUrl}/stores/${storeId}/reorder-suggestions`, {
      params,
    });
  }

  createPurchaseOrdersFromSuggestions(
    storeId: string,
    input: PurchaseOrdersFromSuggestionsInput,
  ): Observable<PurchaseOrder[]> {
    return this.http
      .post<{ items: PurchaseOrder[] }>(`${this.baseUrl}/stores/${storeId}/purchase-orders/from-suggestions`, input)
      .pipe(map((answer) => answer.items));
  }

  purchaseOrders(storeId: string, query: PurchaseOrderQuery = {}): Observable<PurchaseOrderPage> {
    let params = new HttpParams();
    if (query.status && query.status.length > 0) {
      params = params.set('status', query.status.join(','));
    }
    if (query.overdue !== undefined) {
      params = params.set('overdue', query.overdue);
    }
    if (query.page !== undefined) {
      params = params.set('page', query.page);
    }
    if (query.size !== undefined) {
      params = params.set('size', query.size);
    }
    return this.http.get<PurchaseOrderPage>(`${this.baseUrl}/stores/${storeId}/purchase-orders`, { params });
  }

  createPurchaseOrder(storeId: string, input: PurchaseOrderCreate): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(`${this.baseUrl}/stores/${storeId}/purchase-orders`, input);
  }

  purchaseOrder(storeId: string, poId: string): Observable<PurchaseOrder> {
    return this.http.get<PurchaseOrder>(`${this.baseUrl}/stores/${storeId}/purchase-orders/${poId}`);
  }

  updatePurchaseOrder(storeId: string, poId: string, input: PurchaseOrderUpdate): Observable<PurchaseOrder> {
    return this.http.put<PurchaseOrder>(`${this.baseUrl}/stores/${storeId}/purchase-orders/${poId}`, input);
  }

  discardPurchaseOrder(storeId: string, poId: string, input: VersionInput): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(`${this.baseUrl}/stores/${storeId}/purchase-orders/${poId}/discard`, input);
  }

  submitPurchaseOrder(storeId: string, poId: string, input: VersionClientKeyInput): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(`${this.baseUrl}/stores/${storeId}/purchase-orders/${poId}/submit`, input);
  }

  markPurchaseOrderSent(storeId: string, poId: string, input: MarkSentInput): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(`${this.baseUrl}/stores/${storeId}/purchase-orders/${poId}/mark-sent`, input);
  }

  cancelPurchaseOrder(storeId: string, poId: string, input: VersionReasonInput): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(`${this.baseUrl}/stores/${storeId}/purchase-orders/${poId}/cancel`, input);
  }

  closePurchaseOrderShort(storeId: string, poId: string, input: VersionReasonInput): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(
      `${this.baseUrl}/stores/${storeId}/purchase-orders/${poId}/close-short`,
      input,
    );
  }

  copyPurchaseOrder(storeId: string, poId: string, input: ClientKeyInput): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(`${this.baseUrl}/stores/${storeId}/purchase-orders/${poId}/copy`, input);
  }

  goodsReceipts(storeId: string, status?: GoodsReceiptStatus, page = 0, size = 20): Observable<GoodsReceiptPage> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (status) {
      params = params.set('status', status);
    }
    return this.http.get<GoodsReceiptPage>(`${this.baseUrl}/stores/${storeId}/goods-receipts`, { params });
  }

  createGoodsReceipt(storeId: string, input: GoodsReceiptCreate): Observable<GoodsReceipt> {
    return this.http.post<GoodsReceipt>(`${this.baseUrl}/stores/${storeId}/goods-receipts`, input);
  }

  goodsReceipt(storeId: string, grnId: string): Observable<GoodsReceipt> {
    return this.http.get<GoodsReceipt>(`${this.baseUrl}/stores/${storeId}/goods-receipts/${grnId}`);
  }

  updateGoodsReceipt(storeId: string, grnId: string, input: GoodsReceiptUpdate): Observable<GoodsReceipt> {
    return this.http.put<GoodsReceipt>(`${this.baseUrl}/stores/${storeId}/goods-receipts/${grnId}`, input);
  }

  discardGoodsReceipt(storeId: string, grnId: string, input: VersionInput): Observable<GoodsReceipt> {
    return this.http.post<GoodsReceipt>(`${this.baseUrl}/stores/${storeId}/goods-receipts/${grnId}/discard`, input);
  }

  reopenGoodsReceipt(storeId: string, grnId: string, input: VersionInput): Observable<GoodsReceipt> {
    return this.http.post<GoodsReceipt>(`${this.baseUrl}/stores/${storeId}/goods-receipts/${grnId}/reopen`, input);
  }

  postGoodsReceipt(storeId: string, grnId: string, input: PostWithSignerInput): Observable<GoodsReceipt> {
    return this.http.post<GoodsReceipt>(`${this.baseUrl}/stores/${storeId}/goods-receipts/${grnId}/post`, input);
  }

  vendorReturnEligible(storeId: string, vendorId: string): Observable<VendorReturnEligible> {
    const params = new HttpParams().set('vendorId', vendorId);
    return this.http.get<VendorReturnEligible>(`${this.baseUrl}/stores/${storeId}/vendor-returns/eligible`, {
      params,
    });
  }

  debitNotes(
    storeId: string,
    query: { status?: DebitNoteStatus; vendorId?: string; page?: number; size?: number } = {},
  ): Observable<DebitNotePage> {
    let params = new HttpParams();
    if (query.status) {
      params = params.set('status', query.status);
    }
    if (query.vendorId) {
      params = params.set('vendorId', query.vendorId);
    }
    params = params.set('page', query.page ?? 0).set('size', query.size ?? 20);
    return this.http.get<DebitNotePage>(`${this.baseUrl}/stores/${storeId}/debit-notes`, { params });
  }

  createDebitNote(storeId: string, input: DebitNoteCreate): Observable<DebitNote> {
    return this.http.post<DebitNote>(`${this.baseUrl}/stores/${storeId}/debit-notes`, input);
  }

  debitNote(storeId: string, dnId: string): Observable<DebitNote> {
    return this.http.get<DebitNote>(`${this.baseUrl}/stores/${storeId}/debit-notes/${dnId}`);
  }

  updateDebitNote(storeId: string, dnId: string, input: DebitNoteUpdate): Observable<DebitNote> {
    return this.http.put<DebitNote>(`${this.baseUrl}/stores/${storeId}/debit-notes/${dnId}`, input);
  }

  discardDebitNote(storeId: string, dnId: string, input: VersionInput): Observable<DebitNote> {
    return this.http.post<DebitNote>(`${this.baseUrl}/stores/${storeId}/debit-notes/${dnId}/discard`, input);
  }

  submitDebitNote(storeId: string, dnId: string, input: VersionClientKeyInput): Observable<DebitNote> {
    return this.http.post<DebitNote>(`${this.baseUrl}/stores/${storeId}/debit-notes/${dnId}/submit`, input);
  }

  markDebitNotePicked(storeId: string, dnId: string, input: VersionInput): Observable<DebitNote> {
    return this.http.post<DebitNote>(`${this.baseUrl}/stores/${storeId}/debit-notes/${dnId}/picked`, input);
  }

  handOverDebitNote(storeId: string, dnId: string, input: HandOverInput): Observable<DebitNote> {
    return this.http.post<DebitNote>(`${this.baseUrl}/stores/${storeId}/debit-notes/${dnId}/hand-over`, input);
  }

  recordCreditNote(storeId: string, dnId: string, input: CreditNoteInput): Observable<DebitNote> {
    return this.http.post<DebitNote>(`${this.baseUrl}/stores/${storeId}/debit-notes/${dnId}/credit-note`, input);
  }

  cancelDebitNote(storeId: string, dnId: string, input: VersionReasonInput): Observable<DebitNote> {
    return this.http.post<DebitNote>(`${this.baseUrl}/stores/${storeId}/debit-notes/${dnId}/cancel`, input);
  }

  // ---- SAH-79 work package F: indents (79-9, 79-10), dispatch (79-11), receipt (79-12), returns and shortages (79-13)

  indents(
    storeId: string,
    query: { view?: IndentView; status?: IndentStatus | ''; page?: number; size?: number } = {},
  ): Observable<IndentPage> {
    let params = new HttpParams();
    if (query.view) {
      params = params.set('view', query.view);
    }
    if (query.status) {
      params = params.set('status', query.status);
    }
    if (query.page !== undefined) {
      params = params.set('page', query.page);
    }
    if (query.size !== undefined) {
      params = params.set('size', query.size);
    }
    return this.http.get<IndentPage>(`${this.baseUrl}/stores/${storeId}/indents`, { params });
  }

  createIndent(storeId: string, input: IndentCreate): Observable<Indent> {
    return this.http.post<Indent>(`${this.baseUrl}/stores/${storeId}/indents`, input);
  }

  indent(storeId: string, indentId: string): Observable<Indent> {
    return this.http.get<Indent>(`${this.baseUrl}/stores/${storeId}/indents/${indentId}`);
  }

  updateIndent(storeId: string, indentId: string, input: IndentUpdate): Observable<Indent> {
    return this.http.put<Indent>(`${this.baseUrl}/stores/${storeId}/indents/${indentId}`, input);
  }

  discardIndent(storeId: string, indentId: string, input: VersionInput): Observable<Indent> {
    return this.http.post<Indent>(`${this.baseUrl}/stores/${storeId}/indents/${indentId}/discard`, input);
  }

  submitIndent(storeId: string, indentId: string, input: VersionClientKeyInput): Observable<Indent> {
    return this.http.post<Indent>(`${this.baseUrl}/stores/${storeId}/indents/${indentId}/submit`, input);
  }

  cancelIndent(storeId: string, indentId: string, input: VersionReasonInput): Observable<Indent> {
    return this.http.post<Indent>(`${this.baseUrl}/stores/${storeId}/indents/${indentId}/cancel`, input);
  }

  /** The batches FEFO would pick now for a product and quantity (the "Batch (FEFO)" column); a preview only. */
  fefoPick(storeId: string, productId: string, quantity: number): Observable<FefoPick> {
    const params = new HttpParams().set('productId', productId).set('quantity', quantity);
    return this.http.get<FefoPick>(`${this.baseUrl}/stores/${storeId}/stock/fefo-pick`, { params });
  }

  indentReplacementOptions(storeId: string, indentId: string, lineId: string): Observable<ReplacementOption[]> {
    return this.http
      .get<{ items: ReplacementOption[] }>(
        `${this.baseUrl}/stores/${storeId}/indents/${indentId}/lines/${lineId}/replacement-options`,
      )
      .pipe(map((answer) => answer.items));
  }

  dispatchIndent(storeId: string, indentId: string, input: IndentDispatchInput): Observable<Dispatch> {
    return this.http.post<Dispatch>(`${this.baseUrl}/stores/${storeId}/indents/${indentId}/dispatches`, input);
  }

  incomingDispatches(
    storeId: string,
    query: { status?: 'IN_TRANSIT' | 'RECEIVED'; page?: number; size?: number } = {},
  ): Observable<DispatchPage> {
    let params = new HttpParams();
    if (query.status) {
      params = params.set('status', query.status);
    }
    if (query.page !== undefined) {
      params = params.set('page', query.page);
    }
    if (query.size !== undefined) {
      params = params.set('size', query.size);
    }
    return this.http.get<DispatchPage>(`${this.baseUrl}/stores/${storeId}/dispatches`, { params });
  }

  dispatch(storeId: string, dispatchId: string): Observable<Dispatch> {
    return this.http.get<Dispatch>(`${this.baseUrl}/stores/${storeId}/dispatches/${dispatchId}`);
  }

  receiveDispatch(storeId: string, dispatchId: string, input: DispatchReceiptInput): Observable<Dispatch> {
    return this.http.post<Dispatch>(`${this.baseUrl}/stores/${storeId}/dispatches/${dispatchId}/receipt`, input);
  }

  waitingForAction(
    storeId: string,
    query: { side: ReturnSide; status?: ReturnItemStatus; page?: number; size?: number },
  ): Observable<WaitingForActionPage> {
    let params = new HttpParams().set('side', query.side);
    if (query.status) {
      params = params.set('status', query.status);
    }
    if (query.page !== undefined) {
      params = params.set('page', query.page);
    }
    if (query.size !== undefined) {
      params = params.set('size', query.size);
    }
    return this.http.get<WaitingForActionPage>(`${this.baseUrl}/stores/${storeId}/waiting-for-action`, { params });
  }

  returnItem(storeId: string, itemId: string): Observable<ReturnItem> {
    return this.http.get<ReturnItem>(`${this.baseUrl}/stores/${storeId}/return-items/${itemId}`);
  }

  acceptReturnItem(storeId: string, itemId: string, input: AcceptReturnInput): Observable<ReturnItem> {
    return this.http.post<ReturnItem>(`${this.baseUrl}/stores/${storeId}/return-items/${itemId}/accept`, input);
  }

  disputeReturnItem(storeId: string, itemId: string, input: VersionReasonInput): Observable<ReturnItem> {
    return this.http.post<ReturnItem>(`${this.baseUrl}/stores/${storeId}/return-items/${itemId}/dispute`, input);
  }

  replyReturnItem(storeId: string, itemId: string, input: NoteInput): Observable<ReturnItem> {
    return this.http.post<ReturnItem>(`${this.baseUrl}/stores/${storeId}/return-items/${itemId}/notes`, input);
  }

  shortages(
    storeId: string,
    query: { status?: ShortageStatus; page?: number; size?: number } = {},
  ): Observable<ShortagePage> {
    let params = new HttpParams();
    if (query.status) {
      params = params.set('status', query.status);
    }
    if (query.page !== undefined) {
      params = params.set('page', query.page);
    }
    if (query.size !== undefined) {
      params = params.set('size', query.size);
    }
    return this.http.get<ShortagePage>(`${this.baseUrl}/stores/${storeId}/shortages`, { params });
  }

  settleShortage(storeId: string, shortageId: string, input: SettleShortageInput): Observable<Shortage> {
    return this.http.post<Shortage>(`${this.baseUrl}/stores/${storeId}/shortages/${shortageId}/settle`, input);
  }
}
