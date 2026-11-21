import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { PageHeadComponent } from '../../shared/ui/page-head.component';
import { PharmacyContextStore } from './pharmacy-context.store';
import { STORE_ROLE_LABELS, STORE_TYPE_LABELS } from './pharmacy.model';

/**
 * `/app/pharmacy` itself. A person who may see Inventory setup is sent to it; else one who may see Inventory
 * (SAH-79) is sent there. Everyone else has no tab, so the working store is the only item: this page says which store they work in and in which role (story 78-1).
 */
@Component({
  selector: 'app-module-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeadComponent],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
  `,
  template: `
    @if (!store.canViewSetup() && !store.canInventory()) {
      <app-page-head title="Pharmacy & Inventory" [lead]="'Unit: ' + store.unitName()" />
      <section class="panel surf" data-testid="module-home">
        <h2>Working store</h2>
        @if (store.workingStore(); as working) {
          <p data-testid="home-working-store">
            <b>{{ working.name }}</b>
            · {{ typeLabels[working.type] }} · {{ roleLabels[working.effectiveStoreRole] }}
          </p>
        } @else if (store.notMapped()) {
          <p class="note">You are not mapped to a store. Ask your owner.</p>
        } @else {
          <p class="note">Pick your working store above.</p>
        }
      </section>
    }
  `,
})
export class ModuleHomePage implements OnInit {
  protected readonly store = inject(PharmacyContextStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly typeLabels = STORE_TYPE_LABELS;
  protected readonly roleLabels = STORE_ROLE_LABELS;

  ngOnInit(): void {
    if (this.store.canViewSetup()) {
      void this.router.navigate(['setup'], { relativeTo: this.route, replaceUrl: true });
    } else if (this.store.canInventory()) {
      void this.router.navigate(['inventory'], { relativeTo: this.route, replaceUrl: true });
    } else if (this.store.canPharmacyTab()) {
      void this.router.navigate(['pharmacy'], { relativeTo: this.route, replaceUrl: true });
    }
  }
}
