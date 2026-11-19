import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { IconComponent } from '../../shared/icon/icon.component';
import { PageHeadComponent } from '../../shared/ui/page-head.component';
import { PharmacyContextStore } from './pharmacy-context.store';
import { STORE_TYPE_LABELS } from './pharmacy.model';

/**
 * Pharmacy & Inventory entry (SAH-78-1, SAH-78-4). Reads `GET /context` once, then shows only the
 * tabs the capabilities list (in this release only Inventory setup) and the working-store select
 * (story 78-4; mockup 16 draws it at the foot of the left panel, here it sits beside the tabs because
 * the Sahaj shell is not changed, BRD section 7). Anything the server does not list is hidden.
 */
@Component({
  selector: 'app-pharmacy',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatSelectModule,
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
    IconComponent,
    PageHeadComponent,
  ],
  styleUrl: './pharmacy.page.scss',
  templateUrl: './pharmacy.page.html',
})
export class PharmacyPage implements OnInit, OnDestroy {
  protected readonly store = inject(PharmacyContextStore);
  protected readonly typeLabels = STORE_TYPE_LABELS;

  ngOnInit(): void {
    this.store.start();
  }

  ngOnDestroy(): void {
    this.store.reset();
  }

  protected retry(): void {
    this.store.start();
  }

  protected pick(storeId: string): void {
    this.store.pickWorkingStore(storeId);
  }
}
