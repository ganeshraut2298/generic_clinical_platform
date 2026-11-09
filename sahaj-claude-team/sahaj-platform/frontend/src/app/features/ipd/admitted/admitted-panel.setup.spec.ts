import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CATALOGUE, resolvedStandard, standardSetup } from '../card/card-setup.fixtures';
import { resolveSetup } from '../card/card-setup.logic';
import { ResolvedCardSetup } from '../card/card-setup.model';
import { AdmissionList } from '../ipd.model';
import { AdmittedPanelComponent, OpenAdmissionRequest } from './admitted-panel.component';
import { AdmittedViewState } from './admitted-view.state';
import {
  ABILITIES_FRONT_DESK,
  ABILITIES_NURSE,
  ABILITIES_OWNER,
  listOf,
  withoutClinical,
} from './admitted.fixtures';

function published(
  partial: Partial<ReturnType<typeof standardSetup>> = {},
  version = 2,
): ResolvedCardSetup {
  return resolveSetup({ ...standardSetup(), ...partial }, CATALOGUE, {
    version,
    isStandard: false,
  });
}

describe('AdmittedPanelComponent with the published card setup (SAH-37)', () => {
  let fixture: ComponentFixture<AdmittedPanelComponent>;
  let state: AdmittedViewState;
  let opened: OpenAdmissionRequest[];

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [AdmittedViewState, provideRouter([])] });
    state = TestBed.inject(AdmittedViewState);
  });

  function mount(list: AdmissionList, isOwner = false): HTMLElement {
    fixture = TestBed.createComponent(AdmittedPanelComponent);
    fixture.componentRef.setInput('list', list);
    fixture.componentRef.setInput('isOwner', isOwner);
    opened = [];
    fixture.componentInstance.openAdmission.subscribe((r) => opened.push(r));
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const q = <T extends HTMLElement>(view: HTMLElement, sel: string) => view.querySelector<T>(sel)!;

  describe('layout and cards per row (AC54)', () => {
    it('draws every card in the published layout and the grid carries it', () => {
      const s = standardSetup();
      s.layout = 'compact';
      const view = mount(listOf({ cardSetup: published(s) }));
      expect(q(view, '[data-testid=admitted-cards]').classList.contains('lay-compact')).toBe(true);
      const cards = Array.from(view.querySelectorAll('[data-testid=patient-card]'));
      expect(cards.length).toBe(3);
      expect(cards.every((c) => c.getAttribute('data-layout') === 'compact')).toBe(true);
    });

    it("opens with the published Cards per row, and a person's own choice wins (AC54, SAH-36 AC1)", () => {
      const view = mount(listOf({ cardSetup: published({ cardsPerRow: 4 }) }));
      expect(q(view, '[data-testid=admitted-cards]').classList.contains('cols-4')).toBe(true);
      expect(q(view, '[data-cols="4"]').getAttribute('aria-pressed')).toBe('true');
      q<HTMLButtonElement>(view, '[data-cols="2"]').click();
      fixture.detectChanges();
      expect(q(view, '[data-testid=admitted-cards]').classList.contains('cols-2')).toBe(true);
      // A later reload with another published value does not take the person's choice back.
      fixture.componentRef.setInput(
        'list',
        listOf({ cardSetup: published({ cardsPerRow: 3 }, 3) }),
      );
      fixture.detectChanges();
      expect(q(view, '[data-testid=admitted-cards]').classList.contains('cols-2')).toBe(true);
      expect(state.cols()).toBe(2);
    });

    it('follows a newly published setup at the next reload, all cards at once (AC60)', () => {
      const view = mount(listOf({ cardSetup: published({}, 2) }));
      expect(q(view, '[data-testid=card-foot]').textContent).toContain('v2');
      const next = standardSetup();
      next.layout = 'expanded';
      fixture.componentRef.setInput('list', listOf({ cardSetup: published(next, 3) }));
      fixture.detectChanges();
      const cards = Array.from(view.querySelectorAll('[data-testid=patient-card]'));
      expect(cards.every((c) => c.getAttribute('data-layout') === 'expanded')).toBe(true);
      expect(q(view, '[data-testid=card-foot]').textContent).toContain('v3');
    });

    it('leaves the Table view unchanged by the setup (AC59)', () => {
      const s = standardSetup();
      s.layout = 'compact';
      const view = mount(listOf({ cardSetup: published(s) }));
      q<HTMLButtonElement>(view, '[data-view=table]').click();
      fixture.detectChanges();
      expect(
        Array.from(view.querySelectorAll('[role=columnheader]')).map((h) => h.textContent!.trim()),
      ).toEqual(['Patient', 'Bed', 'Doctor', 'Running bill', 'Deposit', 'Status', 'Open']);
    });
  });

  describe('fallback (AC61)', () => {
    it('shows the standard card and the notice when the server says fallback, and the list still loads', () => {
      const view = mount(listOf({ cardSetup: { ...resolvedStandard(), fallback: true } }));
      expect(q(view, '[data-testid=card-setup-notice]').textContent).toContain(
        'Could not load the card setup. Showing the standard card.',
      );
      expect(view.querySelectorAll('[data-testid=patient-card]').length).toBe(3);
    });

    it('shows the same when the setup is missing from the answer', () => {
      const list = listOf();
      delete (list as Partial<AdmissionList>).cardSetup;
      const view = mount(list);
      expect(q(view, '[data-testid=card-setup-notice]')).not.toBeNull();
      expect(view.querySelectorAll('[data-testid=patient-card]').length).toBe(3);
      expect(q(view, '[data-testid=card-name]').textContent!.trim()).toBe('Asha Patil');
    });

    it('shows no notice with a good setup', () => {
      expect(mount(listOf()).querySelector('[data-testid=card-setup-notice]')).toBeNull();
    });
  });

  describe('Customize card (AC62)', () => {
    it('is a link to Clinic setup → Patient card for the owner, after the view switch', () => {
      const view = mount(listOf({ card: ABILITIES_OWNER }), true);
      const link = q<HTMLAnchorElement>(view, '[data-testid=customize-card]');
      expect(link.textContent!.trim()).toBe('Customize card');
      expect(link.getAttribute('href')).toBe('/app/config/ipcard');
      const toolbar = q(view, '[data-testid=admitted-toolbar]');
      expect(toolbar.lastElementChild).toBe(link);
    });

    it('is not in the page at all for anyone else', () => {
      const view = mount(listOf({ card: ABILITIES_NURSE }), false);
      expect(view.querySelector('[data-testid=customize-card]')).toBeNull();
      expect(view.textContent).not.toContain('Customize card');
    });
  });

  describe('foot line', () => {
    it('names the layout, the version and, for staff, who sets it', () => {
      expect(q(mount(listOf(), true), '[data-testid=card-foot]').textContent!.trim()).toBe(
        'Standard card · Sahaj standard card · v1',
      );
      const s = standardSetup();
      s.layout = 'compact';
      expect(
        q(
          mount(listOf({ cardSetup: published(s, 4) })),
          '[data-testid=card-foot]',
        ).textContent!.trim(),
      ).toBe('Compact card · customized · v4 · set by the clinic owner');
    });
  });

  describe('Deposit action (AC40)', () => {
    it('opens the admission sheet at the Running bill and deposit part', () => {
      const s = standardSetup();
      s.actions.find((a) => a.key === 'deposit')!.on = true;
      const items = listOf().items.map(withoutClinical);
      const view = mount(listOf({ card: ABILITIES_FRONT_DESK, items, cardSetup: published(s) }));
      const buttons = Array.from(view.querySelectorAll<HTMLButtonElement>('[data-act=deposit]'));
      expect(buttons.length).toBe(3);
      buttons[1].click();
      expect(opened).toEqual([{ id: 'a2', start: 'deposit' }]);
    });
  });
});
