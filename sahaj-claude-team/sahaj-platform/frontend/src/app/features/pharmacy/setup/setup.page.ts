import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, InjectionToken, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { PageHeadComponent } from '../../../shared/ui/page-head.component';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { storesSubtitle } from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import { SetupSummary } from '../pharmacy.model';
import { ApprovalRulesSection } from '../approval-rules/approval-rules.section';
import { DocumentsSection } from '../documents/documents.section';
import { OpeningStockSection } from '../opening-stock/opening-stock.section';
import { PRODUCTS_SUBTITLE } from '../products/product.util';
import { ProductsSection } from '../products/products.section';
import { VendorsSection } from '../vendors/vendors.section';
import { vendorsSubtitle } from '../vendors/vendor.util';
import { StoresStaffSection } from './stores-staff.section';

/** Reloads the page; a token so that a test can see the call (the real one cannot run in a unit test). */
export const RELOAD_PAGE = new InjectionToken<() => void>('RELOAD_PAGE', {
  providedIn: 'root',
  factory: () => {
    const document = inject(DOCUMENT);
    return () => document.defaultView?.location.reload();
  },
});

type SectionKey = 'stores' | 'products' | 'vendors' | 'rules' | 'documents' | 'opening';

interface SetupSection {
  key: SectionKey;
  label: string;
}

/**
 * Inventory setup (SAH-78-1; mockup 16 header "Setup", the line "Unit: ...", and the section tabs).
 * Only sections with a released screen are listed, in the order of story 78-1: "Stores & staff" (with the
 * unit settings card at its top), "Products", "Vendors", "Approval rules", "Numbering and documents" and, for
 * a person who can upload or approve opening stock, "Opening stock". Every section but Stores & staff loads on
 * demand with a defer block, so its code is a separate chunk.
 */
@Component({
  selector: 'app-setup',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    IconComponent,
    EmptyStateComponent,
    PageHeadComponent,
    StoresStaffSection,
    ProductsSection,
    VendorsSection,
    ApprovalRulesSection,
    DocumentsSection,
    OpeningStockSection,
  ],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .tabpanel {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .sectab {
      min-height: 44px;
    }
    .sectab small {
      font-weight: 500;
      color: var(--ink-3);
    }
  `,
  template: `
    @if (!store.canViewSetup()) {
      <app-page-head title="Setup" />
      <app-empty>You do not have access to Inventory setup. Ask your owner.</app-empty>
    } @else {
      <app-page-head title="Setup" [lead]="'Unit: ' + store.unitName()">
        @if (store.viewOnly()) {
          <span class="pill p-warn" data-testid="view-only">
            <app-icon name="lock" [size]="13" /> View only
          </span>
        }
      </app-page-head>

      <nav class="tabs" role="tablist" aria-label="Setup sections">
        @for (section of sections(); track section.key) {
          <button
            type="button"
            role="tab"
            class="sectab"
            [id]="'setup-tab-' + section.key"
            [attr.aria-controls]="'setup-panel-' + section.key"
            [attr.aria-selected]="active() === section.key"
            [attr.tabindex]="active() === section.key ? 0 : -1"
            [attr.data-testid]="'section-' + section.key"
            (click)="active.set(section.key)"
            (keydown)="onTabKeydown($event)"
          >
            {{ section.label }}
            @if (subtitleOf(section.key); as text) {
              <small [attr.data-testid]="subtitleTestId(section.key)">{{ text }}</small>
            }
          </button>
        }
      </nav>

      @for (section of sections(); track section.key) {
        @if (active() === section.key) {
          <div
            role="tabpanel"
            class="tabpanel"
            [id]="'setup-panel-' + section.key"
            [attr.aria-labelledby]="'setup-tab-' + section.key"
          >
            @switch (section.key) {
              @case ('stores') {
                <app-stores-staff (changed)="loadSummary()" />
              }
              @case ('products') {
                @defer (on immediate) {
                  <app-products />
                } @placeholder {
                  <p class="loading" role="status">Loading products</p>
                } @loading {
                  <p class="loading" role="status">Loading products</p>
                } @error {
                  <div class="notice err" role="alert" data-testid="section-load-error">
                    <app-icon name="alert" [size]="16" />
                    <span>Could not load. Try again.</span>
                    <button mat-stroked-button type="button" data-testid="section-load-retry" (click)="reload()">
                      Try again
                    </button>
                  </div>
                }
              }
              @case ('vendors') {
                @defer (on immediate) {
                  <app-vendors (changed)="loadSummary()" />
                } @placeholder {
                  <p class="loading" role="status">Loading vendors</p>
                } @loading {
                  <p class="loading" role="status">Loading vendors</p>
                } @error {
                  <div class="notice err" role="alert" data-testid="section-load-error">
                    <app-icon name="alert" [size]="16" />
                    <span>Could not load. Try again.</span>
                    <button mat-stroked-button type="button" data-testid="section-load-retry" (click)="reload()">
                      Try again
                    </button>
                  </div>
                }
              }
              @case ('rules') {
                @defer (on immediate) {
                  <app-approval-rules />
                } @placeholder {
                  <p class="loading" role="status">Loading approval rules</p>
                } @loading {
                  <p class="loading" role="status">Loading approval rules</p>
                } @error {
                  <div class="notice err" role="alert" data-testid="section-load-error">
                    <app-icon name="alert" [size]="16" />
                    <span>Could not load. Try again.</span>
                    <button mat-stroked-button type="button" data-testid="section-load-retry" (click)="reload()">
                      Try again
                    </button>
                  </div>
                }
              }
              @case ('documents') {
                @defer (on immediate) {
                  <app-documents />
                } @placeholder {
                  <p class="loading" role="status">Loading numbering and documents</p>
                } @loading {
                  <p class="loading" role="status">Loading numbering and documents</p>
                } @error {
                  <div class="notice err" role="alert" data-testid="section-load-error">
                    <app-icon name="alert" [size]="16" />
                    <span>Could not load. Try again.</span>
                    <button mat-stroked-button type="button" data-testid="section-load-retry" (click)="reload()">
                      Try again
                    </button>
                  </div>
                }
              }
              @case ('opening') {
                @defer (on immediate) {
                  <app-opening-stock />
                } @placeholder {
                  <p class="loading" role="status">Loading opening stock</p>
                } @loading {
                  <p class="loading" role="status">Loading opening stock</p>
                } @error {
                  <div class="notice err" role="alert" data-testid="section-load-error">
                    <app-icon name="alert" [size]="16" />
                    <span>Could not load. Try again.</span>
                    <button mat-stroked-button type="button" data-testid="section-load-retry" (click)="reload()">
                      Try again
                    </button>
                  </div>
                }
              }
            }
          </div>
        }
      }
    }
  `,
})
export class SetupPage implements OnInit {
  protected readonly store = inject(PharmacyContextStore);
  private readonly api = inject(PharmacyApi);
  private readonly reloadPage = inject(RELOAD_PAGE);

  /** Story 78-1 order. Opening stock shows only to a person who can upload or approve (capabilities). */
  protected readonly sections = computed<readonly SetupSection[]>(() => [
    { key: 'stores', label: 'Stores & staff' },
    { key: 'products', label: 'Products' },
    { key: 'vendors', label: 'Vendors' },
    { key: 'rules', label: 'Approval rules' },
    { key: 'documents', label: 'Numbering and documents' },
    ...(this.store.canUploadOpeningStock() || this.store.canApproveOpeningStock()
      ? [{ key: 'opening' as const, label: 'Opening stock' }]
      : []),
  ]);
  protected readonly active = signal<SectionKey>('stores');
  private readonly summary = signal<SetupSummary | null>(null);

  /** The line under each tab (story 78-1): live counts for stores, the product source for products. */
  protected subtitleOf(key: SectionKey): string {
    switch (key) {
      case 'stores':
        return storesSubtitle(this.summary());
      case 'products':
        return PRODUCTS_SUBTITLE;
      case 'vendors':
        return vendorsSubtitle(this.summary());
      case 'rules':
      case 'documents':
      case 'opening':
        return '';
    }
  }

  /** The stores subtitle keeps its original test id; the others carry their section key. */
  protected subtitleTestId(key: SectionKey): string {
    return key === 'stores' ? 'section-subtitle' : `section-subtitle-${key}`;
  }

  ngOnInit(): void {
    if (this.store.canViewSetup()) {
      this.loadSummary();
    }
  }

  /** WAI-ARIA tabs: arrow keys, Home and End move to a tab and open it; only the open tab is in the tab order. */
  protected onTabKeydown(event: KeyboardEvent): void {
    const sections = this.sections();
    const last = sections.length - 1;
    const at = sections.findIndex((s) => s.key === this.active());
    let next: number;
    switch (event.key) {
      case 'ArrowRight':
        next = at >= last ? 0 : at + 1;
        break;
      case 'ArrowLeft':
        next = at <= 0 ? last : at - 1;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = last;
        break;
      default:
        return;
    }
    event.preventDefault();
    const key = sections[next].key;
    this.active.set(key);
    document.getElementById('setup-tab-' + key)?.focus();
  }

  /**
   * The retry of a section whose code did not load. Angular keeps a failed defer block as failed for the life of
   * the page, so drawing the section again would show the same error: reloading the page is the retry.
   */
  protected reload(): void {
    this.reloadPage();
  }

  /** The live subtitles are counted by the server; a failed read just leaves them out. */
  protected loadSummary(): void {
    this.api.setupSummary().subscribe({
      next: (summary) => this.summary.set(summary),
      error: () => undefined,
    });
  }
}
