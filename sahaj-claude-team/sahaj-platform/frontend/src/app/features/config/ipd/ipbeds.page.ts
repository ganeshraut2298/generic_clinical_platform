import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { IconComponent } from '../../../shared/icon/icon.component';
import { IpdApi } from './ipd.api';
import { IpdGateComponent } from './ipd-gate.component';
import { IpdSetupStore } from './ipd-setup.store';
import { CATEGORY_OPTIONS, CategoryKey, IpdBed, IpdWard } from './ipd.model';

/**
 * Wards, rooms and beds (SAH-22), mockup `cfgIpBeds`. Every change saves at once and the API
 * answers with the whole layout. Only the owner can change anything, and not while IPD is read-only.
 */
@Component({
  selector: 'app-ip-beds',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgTemplateOutlet, MatButtonModule, IconComponent, IpdGateComponent],
  providers: [IpdSetupStore],
  styleUrls: ['../config-common.scss', './ipbeds.page.scss'],
  templateUrl: './ipbeds.page.html',
})
export class IpBedsPage {
  private readonly api = inject(IpdApi);
  protected readonly store = inject(IpdSetupStore);

  protected readonly categories = CATEGORY_OPTIONS;
  protected readonly wardError = signal<string | null>(null);

  protected readonly atLimit = computed(() => {
    const l = this.store.layout();
    return !!l && l.bedCount >= l.bedLimit;
  });

  /** Beds of a ward that are not in any of its rooms (shown under the rooms when rooms are on). */
  protected looseBeds(ward: IpdWard): IpdBed[] {
    const inRooms = new Set(ward.rooms.flatMap((r) => r.beds.map((b) => b.id)));
    return ward.beds.filter((b) => !inRooms.has(b.id));
  }

  protected bedCountLabel(ward: IpdWard): string {
    return `${ward.bedCount} ${ward.bedCount === 1 ? 'bed' : 'beds'}`;
  }

  protected setRoomLevel(): void {
    const layout = this.store.layout();
    if (!layout) {
      return;
    }
    this.store.run(this.api.setRoomLevel(!layout.roomLevel), 'We could not save that change. Try again.');
  }

  protected addWard(input: HTMLInputElement): void {
    const name = input.value.trim();
    if (!name) {
      this.wardError.set('Type a ward name first');
      this.store.toast('Type a ward name first');
      return;
    }
    this.wardError.set(null);
    this.store.run(this.api.addWard(name), 'We could not add that ward. Try again.', {
      done: () => {
        input.value = '';
        this.store.toast(`${name} added. Now add its beds.`);
      },
    });
  }

  protected addRoom(ward: IpdWard): void {
    this.store.run(this.api.addRoom(ward.id), 'We could not add that room. Try again.');
  }

  protected addBed(ward: IpdWard, roomId?: string): void {
    this.store.run(this.api.addBed(ward.id, roomId), 'We could not add that bed. Try again.');
  }

  protected setCategory(bed: IpdBed, select: HTMLSelectElement): void {
    const key = select.value as CategoryKey;
    if (key === bed.categoryKey) {
      return;
    }
    this.store.run(this.api.setBedCategory(bed.id, key), 'We could not save that category. Try again.', {
      failed: () => (select.value = bed.categoryKey),
    });
  }

  protected removeBed(bed: IpdBed): void {
    if (bed.status === 'occ') {
      return;
    }
    this.store.run(this.api.removeBed(bed.id), 'We could not remove that bed. Try again.');
  }
}
