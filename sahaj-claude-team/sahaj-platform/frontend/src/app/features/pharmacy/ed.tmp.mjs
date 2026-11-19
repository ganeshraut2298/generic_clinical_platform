import fs from 'fs';
const rep=(p,a,b,all=false)=>{let s=fs.readFileSync(p,'utf8');if(!s.includes(a)){console.error('MISSING in',p,a.slice(0,60));process.exit(1);}s=all?s.split(a).join(b):s.replace(a,()=>b);fs.writeFileSync(p,s);};
let p='inventory/batch-table.component.ts';
rep(p,`            @if (canAdjust()) {
              <th scope="col"><span class="sr-only">Action</span></th>`,`            @if (hasActions()) {
              <th scope="col"><span class="sr-only">Action</span></th>`);
rep(p,`              @if (canAdjust()) {
                <td>
                  @if (row.state === 'AVAILABLE' && row.daysToExpiry >= 0) {`,`              @if (hasActions()) {
                <td>
                  @if (canAdjust() && row.state === 'AVAILABLE' && row.daysToExpiry >= 0) {`);
rep(p,`                      Adjust
                    </button>
                  }
`,`                      Adjust
                    </button>
                  }
                  @if (row.canVendorReturn) {
                    <button
                      mat-stroked-button
                      type="button"
                      [attr.data-testid]="'return-' + row.batch.batchNo + '-' + row.state"
                      [attr.aria-label]="'Return ' + row.product.name + ' batch ' + row.batch.batchNo + ' to the vendor'"
                      (click)="vendorReturn.emit(row)"
                    >
                      Return
                    </button>
                  }
`);
rep(p,`  readonly adjust = output<StockBatchRow>();
`,`  readonly adjust = output<StockBatchRow>();
  /** The "Return" action of a quarantined or near-expiry batch at the Central Store (79-3, mockup 06). */
  readonly vendorReturn = output<StockBatchRow>();

  protected readonly hasActions = computed(() => this.canAdjust() || this.rows().some((r) => r.canVendorReturn));
`);
rep(p,"import { ChangeDetectionStrategy, Component, input, output }","import { ChangeDetectionStrategy, Component, computed, input, output }");
p='inventory/batch-inventory.section.ts';
rep(p,'(adjust)="openAdjustment($event)" />','(adjust)="openAdjustment($event)" (vendorReturn)="openVendorReturn($event)" />');
rep(p,`              (adjust)="openAdjustment($event)"
            />`,`              (adjust)="openAdjustment($event)"
              (vendorReturn)="openVendorReturn($event)"
            />`);
rep(p,`  protected openAdjustment(preset`,`  /** "Return" on a quarantined or near-expiry batch: the vendor-return sheet with the batch and its vendor chosen. */
  protected openVendorReturn(row: StockBatchRow): void {
    const id = this.storeId();
    if (!id) {
      return;
    }
    const data: VendorReturnData = {
      storeId: id,
      vendorId: row.supplier?.vendorId ?? null,
      batchId: row.batch.batchId,
    };
    this.dialog
      .open(VendorReturnDialog, { ...VENDOR_RETURN_CONFIG, data })
      .afterClosed()
      .subscribe((done: DebitNote | null | undefined) => {
        if (!done) {
          return;
        }
        this.adjustedNote.set(\`\${done.number ?? 'The debit note'} is created. The units are held for the vendor.\`);
        const open = this.detail();
        this.loadOverview();
        if (open) {
          this.openProduct(open.product.productId);
        } else {
          this.choosePill(this.active());
        }
      });
  }

  protected openAdjustment(preset`);
rep(p,"import { PharmacyApi } from '../pharmacy.api';","import { PharmacyApi } from '../pharmacy.api';\nimport { DebitNote } from '../pharmacy-buying.model';\nimport { VENDOR_RETURN_CONFIG, VendorReturnData, VendorReturnDialog } from '../purchase/vendor-return.dialog';");
p='purchase/vendor-return.dialog.ts';
rep(p,`export interface VendorReturnData {
  storeId: string;
}`,`export interface VendorReturnData {
  storeId: string;
  /** From a batch row's "Return" (story 79-3): the vendor and the batch are chosen when the sheet opens. */
  vendorId?: string | null;
  batchId?: string | null;
}`);
rep(p,`      next: (page) =>
        this.vendors.set(
          page.items.filter((v) => !v.blocked && v.suppliesTo.some((s) => s.storeId === this.data.storeId)),
        ),`,`      next: (page) => {
        this.vendors.set(
          page.items.filter((v) => !v.blocked && v.suppliesTo.some((s) => s.storeId === this.data.storeId)),
        );
        const preset = this.data.vendorId;
        if (preset && this.vendors().some((v) => v.id === preset)) {
          this.chooseVendor(preset);
        }
      },`);
rep(p,`              selected: false,
              qty:`,`              selected: this.data.batchId != null && item.batch?.batchId === this.data.batchId,
              qty:`);
