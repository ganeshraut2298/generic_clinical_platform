import { ChangeDetectionStrategy, Component, ElementRef, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { IconComponent } from '../../shared/icon/icon.component';
import { ColleagueTriesNoticeStore } from './colleague-tries-notice.store';
import {
  GOT_IT,
  NOTICE_LABEL,
  PinStatus,
  colleagueTriesText,
  noticeStateLine,
} from './pin.model';
import { SetPinSheetComponent } from './set-pin-sheet.component';

/**
 * The notice to the PIN's owner (SAH-38, ADR-014 D8). NEW: the mockup shows no notice. A banner at
 * the top of the main content area of the app shell, after sign-in:
 *
 * - T2 "N wrong tries of your PIN by colleagues since your last use." (or the singular), counts
 *   only, never a name; T3 under it when the PIN is locked or switched off; "Set my PIN" when it
 *   was switched off; the button "Got it".
 * - The always-present wrapper is the polite live region (`role="status"`); the banner appears
 *   inside it, so it is announced when it shows. It never takes the focus.
 * - "Got it" closes the banner at once and gives the focus to the main content area, because the
 *   button that had it is gone.
 * - The buttons are 44 px high; the layout wraps on a 360 px screen.
 */
@Component({
  selector: 'app-colleague-tries-banner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  host: { '[class.ct-empty]': '!store.visible()' },
  styles: `
    /* The host makes no box of its own, so the work area's flex gap never counts it as an item. */
    :host {
      display: contents;
    }
    /* Empty, the live region stays in the page (so a notice is announced when it shows) but is taken
       out of the flow, as a visually hidden box: it is no flex item and leaves no gap. */
    :host(.ct-empty) .live {
      position: absolute;
      width: 1px;
      height: 1px;
      margin: -1px;
      padding: 0;
      overflow: hidden;
      clip-path: inset(50%);
      white-space: nowrap;
      border: 0;
    }
    .ct {
      align-items: center;
      flex-wrap: wrap;
    }
    .ct .text {
      flex: 1 1 240px;
      min-width: 0;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .ct .acts {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .ct button {
      min-height: 44px;
    }
  `,
  template: `
    <div class="live" role="status" aria-live="polite" [attr.aria-label]="label">
      @if (store.visible()) {
        <div class="notice warn ct" data-testid="colleague-tries-banner">
          <app-icon name="alert" [size]="16" />
          <div class="text">
            <span data-testid="ct-count">{{ countText() }}</span>
            @if (stateLine(); as line) {
              <span data-testid="ct-state">{{ line }}</span>
            }
          </div>
          <div class="acts">
            @if (switchedOff()) {
              <button
                type="button"
                id="ct-setpin"
                mat-stroked-button
                data-act="ct-set-pin"
                (click)="setPin()"
              >
                Set my PIN
              </button>
            }
            <button type="button" id="ct-gotit" mat-flat-button data-act="ct-got-it" (click)="gotIt()">
              {{ gotItText }}
            </button>
          </div>
        </div>
      }
    </div>
  `,
})
export class ColleagueTriesBannerComponent {
  protected readonly store = inject(ColleagueTriesNoticeStore);
  private readonly dialog = inject(MatDialog);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly label = NOTICE_LABEL;
  protected readonly gotItText = GOT_IT;

  protected readonly countText = computed(() => colleagueTriesText(this.store.notice()?.unseenCount ?? 0));
  protected readonly stateLine = computed(() => {
    const notice = this.store.notice();
    return notice ? noticeStateLine(notice) : null;
  });
  protected readonly switchedOff = computed(() => {
    const notice = this.store.notice();
    return notice?.state === 'NOT_SET' && notice.notSetReason === 'TOO_MANY_LOCKS';
  });

  protected gotIt(): void {
    this.store.dismiss();
    // The button that had the focus is gone: the main area takes it (the shell sets tabindex -1).
    this.host.nativeElement.closest<HTMLElement>('main')?.focus();
  }

  /** The existing Set PIN sheet. The focus goes to a button that still exists when it closes. */
  protected setPin(): void {
    this.dialog
      .open<SetPinSheetComponent, undefined, PinStatus>(SetPinSheetComponent, {
        panelClass: 'sheet-panel',
        width: '520px',
        maxWidth: '100vw',
        ariaLabelledBy: 'sp-title',
        restoreFocus: false,
      })
      .afterClosed()
      .subscribe((status) => {
        if (status) {
          this.store.applyStatus(status);
        }
        setTimeout(() => {
          const root = this.host.nativeElement;
          (root.querySelector<HTMLElement>('#ct-setpin') ?? root.querySelector<HTMLElement>('#ct-gotit'))?.focus();
        }, 0);
      });
  }
}
