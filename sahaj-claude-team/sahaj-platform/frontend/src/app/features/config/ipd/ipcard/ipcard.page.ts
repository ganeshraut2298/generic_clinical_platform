import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { Observable, map } from 'rxjs';
import { IconComponent } from '../../../../shared/icon/icon.component';
import { draftPillText } from '../../../ipd/card/card-setup.logic';
import { LAYOUT_NAME } from '../../../ipd/card/card-setup.model';
import { formatWhen } from '../../config-format';
import { IpdGateComponent } from '../ipd-gate.component';
import { CardActionsTabComponent } from './actions-tab.component';
import { CardPreviewComponent } from './card-preview.component';
import { CardSetupStore } from './card-setup.store';
import { CardFieldsTabComponent } from './fields-tab.component';
import { LeaveAware } from './ipcard-leave.guard';
import { CardLayoutTabComponent } from './layout-tab.component';
import { CardLeaveDialogComponent } from './leave-dialog.component';

type Tab = 'layout' | 'fields' | 'actions';

const TABS: readonly { key: Tab; label: string }[] = [
  { key: 'layout', label: 'Layout and sections' },
  { key: 'fields', label: 'Fields' },
  { key: 'actions', label: 'Quick actions' },
];

/**
 * Clinic setup → Patient card (SAH-37), mockup `cfgIpCard`, key `ipcard`: the status bar (live version,
 * draft pill, Reset to default, Discard draft, Save draft, Publish), the tabs Layout and sections,
 * Fields and Quick actions, the live preview beside them, and Published versions. Only the owner edits;
 * everyone else reads the live setup with every control off. Edits stay in the browser until Save draft;
 * leaving with unsaved edits asks first. The backend still checks every write.
 */
@Component({
  selector: 'app-ip-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    IconComponent,
    IpdGateComponent,
    CardLayoutTabComponent,
    CardFieldsTabComponent,
    CardActionsTabComponent,
    CardPreviewComponent,
  ],
  providers: [CardSetupStore],
  styleUrl: './ipcard.page.scss',
  templateUrl: './ipcard.page.html',
})
export class IpCardPage implements LeaveAware {
  protected readonly store = inject(CardSetupStore);
  private readonly dialog = inject(MatDialog);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private readonly bar = viewChild<ElementRef<HTMLElement>>('bar');

  protected readonly tabs = TABS;
  protected readonly tab = signal<Tab>('layout');
  protected readonly formatWhen = formatWhen;

  protected readonly pillText = computed(() =>
    draftPillText(this.store.parts().length, this.store.savedAt()),
  );
  protected readonly footText = computed(() => {
    const live = this.store.live();
    const draft = this.store.editor();
    if (!live) {
      return '';
    }
    const layout = LAYOUT_NAME[(draft ?? live.setup).layout];
    const who = this.store.isOwner() ? '' : ' · set by the clinic owner';
    return `${layout} card · ${live.isStandard ? 'Sahaj standard card' : 'customized'} · v${live.version}${who}`;
  });
  protected readonly readOnlyText = computed(() => {
    switch (this.store.readOnlyReason()) {
      case 'IPD_READONLY':
        return 'IPD is read-only. Nothing can be changed.';
      case 'ROLE':
        return 'Only the owner can change the patient card. You can view it here.';
      default:
        return null;
    }
  });

  constructor() {
    // The status bar is one row or two; the sticky preview starts just below it (D-8).
    effect((onCleanup) => {
      const bar = this.bar()?.nativeElement;
      if (!bar || typeof ResizeObserver === 'undefined') {
        return;
      }
      const apply = () =>
        this.host.nativeElement.style.setProperty('--pcbar-h', `${bar.offsetHeight}px`);
      const observer = new ResizeObserver(apply);
      observer.observe(bar);
      apply();
      onCleanup(() => observer.disconnect());
    });
    // The first label box in error gets the focus after a refused Save draft or Publish (G-1, G-2); the
    // Fields tab opens first when the owner is on another tab.
    effect(() => {
      const request = this.store.labelFocus();
      if (request === null) {
        return;
      }
      this.tab.set('fields');
      afterNextRender(
        () =>
          this.host.nativeElement
            .querySelector<HTMLInputElement>(`[data-label="${request.key}"]`)
            ?.focus(),
        { injector: this.injector },
      );
    });
  }

  protected selectTab(tab: Tab): void {
    this.tab.set(tab);
  }

  protected onTabKeydown(event: KeyboardEvent): void {
    const at = TABS.findIndex((t) => t.key === this.tab());
    let next: number;
    switch (event.key) {
      case 'ArrowRight':
        next = (at + 1) % TABS.length;
        break;
      case 'ArrowLeft':
        next = (at - 1 + TABS.length) % TABS.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = TABS.length - 1;
        break;
      default:
        return;
    }
    event.preventDefault();
    this.tab.set(TABS[next].key);
    document.getElementById('pc-tab-' + TABS[next].key)?.focus();
  }

  protected versionWhen(at: string | null): string {
    return at ? `${formatWhen(at)} IST` : 'When the clinic was created';
  }

  /** Asked by the router before the owner leaves the page (AC4). */
  confirmLeave(): Observable<boolean> | boolean {
    if (!this.store.unsaved()) {
      return true;
    }
    return this.dialog
      .open<CardLeaveDialogComponent, void, boolean>(CardLeaveDialogComponent, {
        panelClass: 'sheet-panel',
        width: '420px',
        maxWidth: '100vw',
      })
      .afterClosed()
      .pipe(map((leave) => leave === true));
  }

  /** Closing or reloading the browser tab with unsaved edits. */
  @HostListener('window:beforeunload', ['$event'])
  protected onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.store.unsaved()) {
      event.preventDefault();
    }
  }
}
