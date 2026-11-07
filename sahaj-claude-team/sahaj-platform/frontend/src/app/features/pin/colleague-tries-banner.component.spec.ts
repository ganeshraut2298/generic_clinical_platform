import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Subject } from 'rxjs';
import { ColleagueTriesBannerComponent } from './colleague-tries-banner.component';
import { ColleagueTriesNoticeStore } from './colleague-tries-notice.store';
import { ColleagueTriesNotice } from './pin.model';
import { statusOf } from './pin.fixtures';

const OFF: ColleagueTriesNotice = {
  unseenCount: 1,
  latestAt: '2026-11-07T05:35:12.123456Z',
  state: 'NOT_SET',
  lockedUntil: null,
  notSetReason: 'TOO_MANY_LOCKS',
};

describe('ColleagueTriesBannerComponent (SAH-38, ADR-014 D8)', () => {
  let fixture: ComponentFixture<ColleagueTriesBannerComponent>;
  let store: ColleagueTriesNoticeStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ColleagueTriesBannerComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(ColleagueTriesNoticeStore);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ColleagueTriesBannerComponent);
    fixture.detectChanges();
  });
  afterEach(() => http.verify());

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (n: Element | null | undefined) => (n?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const show = (notice: ColleagueTriesNotice): void => {
    store.load();
    http.expectOne('/api/v1/platform/me/pin/colleague-tries').flush(notice);
    fixture.detectChanges();
  };

  it('keeps an empty polite status region in the page while there is nothing to say', () => {
    const region = el().querySelector('[role=status]')!;
    expect(region.getAttribute('aria-live')).toBe('polite');
    expect(region.getAttribute('aria-label')).toBe('Wrong PIN tries by colleagues');
    expect(region.children.length).toBe(0);
    expect(el().classList).toContain('ct-empty');
  });

  it('"Got it" that fails to save brings the banner back so it can be tapped again (R38-4)', () => {
    show(OFF);
    el().querySelector<HTMLButtonElement>('[data-act=ct-got-it]')!.click();
    fixture.detectChanges();
    expect(el().querySelector('[data-testid=colleague-tries-banner]')).toBeNull();
    http
      .expectOne('/api/v1/platform/me/pin/colleague-tries/seen')
      .flush({}, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid=colleague-tries-banner]')).not.toBeNull();
    expect(el().querySelector('[role=status]')!.getAttribute('aria-live')).toBe('polite');
  });

  it('says one try in the singular and never names anyone', () => {
    show({ ...OFF, state: 'SET', notSetReason: null });
    expect(squash(el().querySelector('[data-testid=ct-count]'))).toBe(
      '1 wrong try of your PIN by a colleague since your last use.',
    );
    expect(el().classList).not.toContain('ct-empty');
    expect(el().querySelector('[data-act=ct-set-pin]')).toBeNull();
  });

  it('after Set my PIN saves a PIN, the switched-off line and the button go and "Got it" has the focus', () => {
    vi.useFakeTimers({ toFake: ['setTimeout'] });
    try {
      show(OFF);
      const closed = new Subject<unknown>();
      vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => closed } as never);
      el().querySelector<HTMLButtonElement>('[data-act=ct-set-pin]')!.click();
      closed.next(statusOf());
      closed.complete();
      fixture.detectChanges();
      vi.advanceTimersByTime(1);
      expect(el().querySelector('[data-testid=ct-state]')).toBeNull();
      expect(el().querySelector('[data-act=ct-set-pin]')).toBeNull();
      expect(squash(el().querySelector('[data-testid=ct-count]'))).toContain('1 wrong try');
      expect(document.activeElement).toBe(el().querySelector('[data-act=ct-got-it]'));
    } finally {
      vi.useRealTimers();
    }
  });

  it('when the Set PIN sheet is cancelled, the focus returns to "Set my PIN"', () => {
    vi.useFakeTimers({ toFake: ['setTimeout'] });
    try {
      show(OFF);
      const closed = new Subject<unknown>();
      vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => closed } as never);
      el().querySelector<HTMLButtonElement>('[data-act=ct-set-pin]')!.click();
      closed.next(undefined);
      closed.complete();
      vi.advanceTimersByTime(1);
      expect(document.activeElement).toBe(el().querySelector('[data-act=ct-set-pin]'));
    } finally {
      vi.useRealTimers();
    }
  });
});
