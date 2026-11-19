import { ChangeDetectionStrategy, Component, DestroyRef, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { concatMap, from, of, tap, toArray } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { formatDisplayDateOfInstant } from '../../../shared/format/ist-date';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { sheetToast } from '../../../shared/ui/sheet-toast';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage } from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import {
  ImportStatus,
  LineRowInput,
  OpeningStockImport,
  TEMPLATE_HELP,
  UPLOAD_FAILED,
} from '../pharmacy.model';
import { ImportReviewComponent } from './import-review.component';
import { inBatches, readOpeningStockCsv } from './opening-stock-csv.util';
import { importChip, rupees } from './opening-stock.util';

interface UploadStore {
  id: string;
  name: string;
}

const LIST_SIZE = 20;
const MAX_FILE_BYTES = 2 * 1024 * 1024;

/**
 * "Opening stock" section of Inventory setup (story 78-7; NEW screen, no mockup: DEC-062 Q1 A, built from
 * existing Sahaj components). Download the CSV template, choose a store and a file, and send it: the
 * browser reads the file as UTF-8 text, checks the header and the row limit, and sends the rows in
 * batches of 200 for the server to check; every problem comes back with its row number and field.
 * A file without problems waits for approval. The owner reviews it and approves or rejects (story
 * 78-7); the owner who uploaded it cannot approve it. File contents are kept in memory only: never in
 * the address, local storage or the log, and dropped when the file is sent or the page is left.
 */
@Component({
  selector: 'app-opening-stock',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatProgressBarModule,
    MatSelectModule,
    IconComponent,
    EmptyStateComponent,
    ImportReviewComponent,
  ],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .row {
      display: flex;
      flex-wrap: wrap;
      gap: 10px 12px;
      align-items: center;
    }
    .store {
      width: 260px;
      max-width: 100%;
    }
    .file-name {
      font-weight: 600;
      overflow-wrap: anywhere;
    }
    .sr-only {
      position: absolute;
      left: -9999px;
    }
    .hidden-input {
      position: absolute;
      left: -9999px;
    }
    table {
      min-width: 760px;
    }
    .sub {
      font-size: 0.76rem;
      color: var(--ink-3);
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
    .pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
      margin-top: 10px;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="os-h" data-testid="opening-stock">
      <div class="panel-h">
        <h2 id="os-h">Opening stock</h2>
        <button mat-stroked-button type="button" data-testid="download-template" (click)="downloadTemplate()">
          Download template
        </button>
      </div>
      <p class="note">
        Load your starting stock from a CSV file. An owner checks and approves it. Approved stock cannot be used
        until it is posted to stock.
      </p>

      @if (templateError(); as text) {
        <div class="notice err" role="alert" data-testid="template-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }

      @if (canUpload()) {
        @if (storesLoading()) {
          <p class="loading" role="status">Loading stores</p>
        } @else if (storesError(); as text) {
          <div class="notice err" role="alert" data-testid="os-stores-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
            <button mat-stroked-button type="button" (click)="loadStores()">Try again</button>
          </div>
        } @else if (stores().length === 0) {
          <app-empty data-testid="os-no-stores">
            @if (isOwner()) {
              No stores yet. Create your Central Store first.
            } @else {
              You are not mapped to a store you can upload for. Ask your owner.
            }
          </app-empty>
        } @else {
          <div class="row">
            <mat-form-field appearance="outline" subscriptSizing="dynamic" class="store">
              <mat-label>Store</mat-label>
              <mat-select
                [value]="storeId()"
                placeholder="Choose an option"
                [disabled]="uploading()"
                (selectionChange)="chooseStore($event.value)"
                data-testid="os-store"
              >
                @for (s of stores(); track s.id) {
                  <mat-option [value]="s.id">{{ s.name }}</mat-option>
                }
              </mat-select>
            </mat-form-field>

            <input
              #picker
              class="hidden-input"
              type="file"
              accept=".csv,text/csv"
              tabindex="-1"
              aria-hidden="true"
              data-testid="os-file"
              (change)="onFile($event)"
            />
            <button
              mat-stroked-button
              type="button"
              data-testid="choose-file"
              [disabled]="uploading()"
              (click)="picker.click()"
            >
              Choose CSV file
            </button>
            @if (fileName(); as name) {
              <span class="file-name" data-testid="file-name">{{ name }} · {{ rowCount() }} rows</span>
            }
            <button
              mat-flat-button
              type="button"
              data-testid="upload"
              [disabled]="uploading() || !canSend()"
              (click)="upload()"
            >
              Upload and check
            </button>
          </div>

          @if (fileError(); as text) {
            <div class="notice err" role="alert" data-testid="file-error">
              <app-icon name="alert" [size]="16" />
              <span>{{ text }}</span>
            </div>
          }
          @if (uploading()) {
            <div role="status" data-testid="upload-progress">
              <p class="note">Sending rows: {{ sent() }} of {{ rowCount() }}</p>
              <mat-progress-bar
                mode="determinate"
                [value]="progress()"
                aria-label="Upload progress"
              ></mat-progress-bar>
            </div>
          }
          @if (uploadError(); as text) {
            <div class="notice err" role="alert" data-testid="upload-error">
              <app-icon name="alert" [size]="16" />
              <span>{{ text }}</span>
            </div>
          }
        }
      }

      <h3>{{ isOwner() ? 'Files from every store' : 'Files of this store' }}</h3>
      @if (isOwner()) {
        <div class="row">
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="store">
            <mat-label>Show</mat-label>
            <mat-select
              [value]="filter()"
              placeholder="Choose an option"
              (selectionChange)="setFilter($event.value)"
              data-testid="os-filter"
            >
              <mat-option value="">All files</mat-option>
              <mat-option value="WAITING_APPROVAL">Waiting for approval</mat-option>
            </mat-select>
          </mat-form-field>
        </div>
      }

      @if (listLoading()) {
        <p class="loading" role="status" data-testid="imports-loading">Loading files</p>
      } @else if (listError(); as text) {
        <div class="notice err" role="alert" data-testid="imports-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="loadList(page())">Try again</button>
        </div>
      } @else if (imports().length === 0) {
        <app-empty data-testid="imports-empty">
          @if (canListStore() || isOwner()) {
            No files yet. Download the template, fill it and upload it.
          } @else {
            Choose a store to see its files.
          }
        </app-empty>
      } @else {
        <div class="tbl-wrap" data-testid="imports-table">
          <table>
            <thead>
              <tr>
                <th scope="col">Store</th>
                <th scope="col">Status</th>
                <th scope="col">Uploaded by</th>
                <th scope="col">Uploaded on</th>
                <th scope="col" class="num">Lines</th>
                <th scope="col" class="num">Total value</th>
                <th scope="col"><span class="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              @for (item of imports(); track item.id) {
                <tr [attr.data-testid]="'import-' + item.id">
                  <td>{{ item.storeName }}</td>
                  <td>
                    <span class="pill" [class]="chip(item).css">{{ chip(item).text }}</span>
                  </td>
                  <td>{{ item.uploadedBy.name }}</td>
                  <td>{{ day(item.createdAt) }}</td>
                  <td class="num">{{ item.lineCount }}</td>
                  <td class="num">{{ money(item.totalValuePaise) }}</td>
                  <td>
                    <button
                      mat-stroked-button
                      type="button"
                      class="btn-sm"
                      [attr.data-testid]="'review-' + item.id"
                      [attr.aria-label]="'Review the file of ' + item.storeName + ' uploaded on ' + day(item.createdAt)"
                      (click)="review(item)"
                    >
                      {{ item.status === 'WAITING_APPROVAL' && isOwner() ? 'Review' : 'Open' }}
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <div class="pager" data-testid="imports-pager">
          <span role="status">{{ rangeText() }}</span>
          <button
            mat-stroked-button
            type="button"
            data-testid="imports-prev"
            [disabled]="page() === 0"
            (click)="loadList(page() - 1)"
          >
            Previous
          </button>
          <button
            mat-stroked-button
            type="button"
            data-testid="imports-next"
            [disabled]="!hasNext()"
            (click)="loadList(page() + 1)"
          >
            Next
          </button>
        </div>
      }
    </section>

    @if (reviewing(); as target) {
      @for (key of [target.importId]; track key) {
        <app-import-review
          [storeId]="target.storeId"
          [importId]="target.importId"
          (decided)="onDecided()"
          (closed)="reviewing.set(null)"
        />
      }
    }
  `,
})
export class OpeningStockSection implements OnInit, OnDestroy {
  private readonly api = inject(PharmacyApi);
  private readonly snackBar = inject(MatSnackBar);
  private readonly context = inject(PharmacyContextStore);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly isOwner = this.context.canEditSetup;
  protected readonly canUpload = this.context.canUploadOpeningStock;

  protected readonly stores = signal<UploadStore[]>([]);
  protected readonly storesLoading = signal(false);
  protected readonly storesError = signal<string | null>(null);
  protected readonly storeId = signal<string | null>(null);

  protected readonly fileName = signal<string | null>(null);
  protected readonly rowCount = signal(0);
  protected readonly fileError = signal<string | null>(null);
  protected readonly uploading = signal(false);
  protected readonly sent = signal(0);
  protected readonly uploadError = signal<string | null>(null);
  protected readonly templateError = signal<string | null>(null);
  protected readonly canSend = computed(() => this.storeId() !== null && this.rowCount() > 0 && this.fileName() !== null);
  protected readonly progress = computed(() => (this.rowCount() > 0 ? Math.round((this.sent() / this.rowCount()) * 100) : 0));

  protected readonly imports = signal<OpeningStockImport[]>([]);
  protected readonly listLoading = signal(false);
  protected readonly listError = signal<string | null>(null);
  protected readonly page = signal(0);
  private readonly total = signal(0);
  protected readonly filter = signal<ImportStatus | ''>('');
  protected readonly reviewing = signal<{ storeId: string; importId: string } | null>(null);

  protected readonly hasNext = computed(() => (this.page() + 1) * LIST_SIZE < this.total());
  protected readonly rangeText = computed(() => {
    const from = this.page() * LIST_SIZE + 1;
    const to = Math.min(this.total(), (this.page() + 1) * LIST_SIZE);
    return `Showing ${from}–${to} of ${this.total()}`;
  });
  protected readonly canListStore = computed(() => this.storeId() !== null);

  protected readonly chip = importChip;
  protected readonly money = rupees;
  protected readonly day = (instant: string) => formatDisplayDateOfInstant(instant);

  /** The rows of the chosen file, in memory only. */
  private rows: LineRowInput[] = [];
  private columns: string[] = [];
  private startKey: string | null = null;
  private importId: string | null = null;

  ngOnInit(): void {
    if (this.canUpload()) {
      this.loadStores();
    }
    if (this.isOwner()) {
      this.loadList(0);
    }
  }

  ngOnDestroy(): void {
    this.forgetFile();
  }

  protected loadStores(): void {
    if (this.isOwner()) {
      this.storesLoading.set(true);
      this.storesError.set(null);
      this.api.stores().subscribe({
        next: (list) => {
          this.setStores(
            list.items.filter((s) => s.status === 'ACTIVE').map((s) => ({ id: s.id, name: s.name })),
          );
          this.storesLoading.set(false);
        },
        error: (error: unknown) => {
          this.storesLoading.set(false);
          this.storesError.set(loadErrorMessage(error));
        },
      });
      return;
    }
    this.setStores(
      this.context
        .stores()
        .filter((s) => s.access.uploadOpeningStock)
        .map((s) => ({ id: s.storeId, name: s.name })),
    );
  }

  private setStores(stores: UploadStore[]): void {
    this.stores.set(stores);
    if (stores.length === 1 && this.storeId() === null) {
      this.chooseStore(stores[0].id);
    }
  }

  protected chooseStore(id: string): void {
    this.storeId.set(id);
    this.uploadError.set(null);
    this.startKey = this.rows.length > 0 ? newClientKey() : null;
    if (!this.isOwner()) {
      this.loadList(0);
    }
  }

  protected setFilter(value: ImportStatus | ''): void {
    this.filter.set(value);
    this.loadList(0);
  }

  protected loadList(page: number): void {
    const store = this.storeId();
    if (!this.isOwner() && !store) {
      this.imports.set([]);
      this.total.set(0);
      return;
    }
    this.listLoading.set(true);
    this.listError.set(null);
    const request = this.isOwner()
      ? this.api.allOpeningStockImports(this.filter() || undefined, page, LIST_SIZE)
      : this.api.openingStockImports(store as string, page, LIST_SIZE);
    request.subscribe({
      next: (answer) => {
        this.imports.set(answer.items);
        this.total.set(answer.total);
        this.page.set(answer.page);
        this.listLoading.set(false);
      },
      error: (error: unknown) => {
        this.listLoading.set(false);
        this.listError.set(loadErrorMessage(error));
      },
    });
  }

  protected review(item: OpeningStockImport): void {
    this.reviewing.set({ storeId: item.storeId, importId: item.id });
  }

  protected onDecided(): void {
    this.loadList(this.page());
  }

  // ---- template

  /** The template is fixed text from the server (no data). It is saved by the browser as a file. */
  protected downloadTemplate(): void {
    this.templateError.set(null);
    this.api.openingStockTemplate().subscribe({
      next: (text) => {
        const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
        const link = document.createElement('a');
        link.href = url;
        link.download = 'sahaj-opening-stock-template.csv';
        link.click();
        URL.revokeObjectURL(url);
      },
      error: () => this.templateError.set('Could not download the template. Try again.'),
    });
  }

  // ---- file

  protected onFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    this.forgetFile();
    this.fileError.set(null);
    this.uploadError.set(null);
    if (!file) {
      return;
    }
    if (file.size > MAX_FILE_BYTES) {
      this.fileError.set(TEMPLATE_HELP);
      return;
    }
    file
      .text()
      .then((text) => this.accept(file.name, text))
      .catch(() => this.fileError.set(TEMPLATE_HELP));
  }

  /** Checks the file in the browser (story 78-7) and keeps its rows in memory until it is sent. */
  protected accept(name: string, text: string): void {
    const read = readOpeningStockCsv(name, text);
    if (!read.ok) {
      this.fileError.set(read.message);
      return;
    }
    this.rows = read.rows;
    this.columns = read.columns;
    this.rowCount.set(read.rows.length);
    this.fileName.set(name);
    this.startKey = newClientKey();
  }

  private forgetFile(): void {
    this.rows = [];
    this.columns = [];
    this.rowCount.set(0);
    this.fileName.set(null);
    this.sent.set(0);
    this.startKey = null;
    this.importId = null;
  }

  // ---- upload: start, send the rows in batches of 200, then submit for the server's checks

  protected upload(): void {
    const store = this.storeId();
    if (this.uploading() || !store || this.rows.length === 0 || !this.startKey) {
      return;
    }
    this.uploading.set(true);
    this.uploadError.set(null);
    this.sent.set(0);
    const clientKey = this.startKey;
    this.api
      .startOpeningStockImport(store, { clientKey, columns: this.columns, rowCount: this.rows.length })
      .pipe(
        tap((started) => (this.importId = started.id)),
        concatMap((started) =>
          // A start that answers with a file that is no longer a draft is a retry after a lost submit
          // response: the rows and the submit are done already, so open that file (no rows are sent).
          started.status !== 'DRAFT'
            ? of(started)
            : from(inBatches(this.rows)).pipe(
                concatMap((rows) =>
                  this.api.addOpeningStockLines(store, started.id, { rows }).pipe(
                    tap(() => this.sent.update((n) => n + rows.length)),
                  ),
                ),
                toArray(),
                concatMap(() => this.api.submitOpeningStockImport(store, started.id)),
              ),
        ),
        // The upload belongs to this section: leaving the tab stops it (the file stays a draft on the server).
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (done) => {
          this.uploading.set(false);
          this.forgetFile();
          if (done.status === 'WAITING_APPROVAL') {
            this.snackBar.open('File sent for approval', undefined, sheetToast(3000));
          }
          this.loadList(0);
          this.reviewing.set({ storeId: done.storeId, importId: done.id });
        },
        error: (error: unknown) => this.onUploadError(store, error),
      });
  }

  private onUploadError(store: string, error: unknown): void {
    this.uploading.set(false);
    if (!(error instanceof ApiError) || error.status === 0 || error.status >= 500) {
      // Unknown outcome: the same key and rows are safe to send again (replay and rowNo are idempotent).
      this.uploadError.set(UPLOAD_FAILED);
      return;
    }
    const importId = this.importId;
    if (error.status === 422 && importId) {
      // The server checked every row: the file is "Not accepted". Show its problems with their rows.
      this.forgetFile();
      this.uploadError.set(null);
      this.loadList(0);
      this.reviewing.set({ storeId: store, importId });
      return;
    }
    // The server answered with a sentence ("Opening stock was already approved for this store." and so on).
    this.startKey = newClientKey();
    this.uploadError.set(error.problem.detail ?? UPLOAD_FAILED);
  }
}
