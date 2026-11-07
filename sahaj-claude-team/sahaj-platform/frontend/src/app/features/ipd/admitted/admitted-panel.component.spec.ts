import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AdmissionList } from '../ipd.model';
import { AdmittedPanelComponent, OpenAdmissionRequest } from './admitted-panel.component';
import { AdmittedViewState } from './admitted-view.state';
import {
  ABILITIES_FRONT_DESK,
  ABILITIES_NURSE,
  ABILITIES_OWNER,
  WARD_A,
  WARD_B,
  WARD_EMPTY,
  listOf,
  manyRows,
  withoutClinical,
} from './admitted.fixtures';

describe('AdmittedPanelComponent', () => {
  let fixture: ComponentFixture<AdmittedPanelComponent>;
  let state: AdmittedViewState;
  let opened: OpenAdmissionRequest[];
  let nursing: string[];
  let emr: string[];

  const mounted: HTMLElement[] = [];

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [AdmittedViewState] });
    state = TestBed.inject(AdmittedViewState);
  });

  function render(list: AdmissionList = listOf(), staleText: string | null = null): HTMLElement {
    return mount(list, staleText);
  }

  function mount(list: AdmissionList, staleText: string | null = null): HTMLElement {
    fixture = TestBed.createComponent(AdmittedPanelComponent);
    fixture.componentRef.setInput('list', list);
    fixture.componentRef.setInput('staleText', staleText);
    opened = [];
    nursing = [];
    emr = [];
    fixture.componentInstance.openAdmission.subscribe((r) => opened.push(r));
    fixture.componentInstance.openNursing.subscribe((id) => nursing.push(id));
    fixture.componentInstance.openEmr.subscribe((id) => emr.push(id));
    mounted.push(fixture.nativeElement as HTMLElement);
    document.body.appendChild(fixture.nativeElement as HTMLElement);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => {
    mounted.splice(0).forEach((el) => el.remove());
  });

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const names = (view: HTMLElement) =>
    Array.from(view.querySelectorAll('[data-testid=card-name]')).map((n) => n.textContent!.trim());
  const q = <T extends HTMLElement>(view: HTMLElement, sel: string) => view.querySelector<T>(sel)!;

  async function type(view: HTMLElement, value: string): Promise<void> {
    const input = q<HTMLInputElement>(view, '[data-testid=admitted-search]');
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
  }

  async function pickWard(view: HTMLElement, id: string): Promise<void> {
    const select = q<HTMLSelectElement>(view, '[data-testid=admitted-ward]');
    select.value = id;
    select.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
  }

  describe('toolbar and defaults', () => {
    it('opens as Cards, 3 per row, with the pressed state announced', () => {
      const view = render();
      const buttons = Array.from(view.querySelectorAll<HTMLButtonElement>('[data-testid=admitted-view] button'));
      expect(buttons.map((b) => b.textContent!.trim())).toEqual(['Cards', 'Table']);
      expect(buttons.map((b) => b.getAttribute('aria-pressed'))).toEqual(['true', 'false']);
      expect(q(view, '[data-testid=admitted-view]').getAttribute('aria-label')).toBe('View');
      const cols = Array.from(view.querySelectorAll<HTMLButtonElement>('[data-testid=admitted-cols] button'));
      expect(cols.map((b) => b.textContent!.trim())).toEqual(['2', '3', '4']);
      expect(cols.map((b) => b.getAttribute('aria-pressed'))).toEqual(['false', 'true', 'false']);
      expect(q(view, '[data-testid=admitted-cols]').getAttribute('aria-label')).toBe('Cards per row');
      expect(q(view, '[data-testid=admitted-cards]').classList.contains('cols-3')).toBe(true);
      expect(q(view, '[data-testid=admitted-cards]').classList.contains('uni')).toBe(true);
      expect(view.querySelector('.iptbl')).toBeNull();
    });

    it('has the search box with its accessible name and placeholder, and the Ward select with all wards', () => {
      const view = render();
      const search = q<HTMLInputElement>(view, '[data-testid=admitted-search]');
      expect(search.getAttribute('aria-label')).toBe('Search admitted patients');
      expect(search.getAttribute('placeholder')).toBe('Name, UHID or bed');
      expect(search.getAttribute('autocomplete')).toBe('off');
      const ward = q<HTMLSelectElement>(view, '[data-testid=admitted-ward]');
      expect(view.querySelector('label[for=admitted-ward]')!.textContent).toBe('Ward');
      expect(Array.from(ward.options).map((o) => o.textContent!.trim())).toEqual([
        'All wards',
        'General ward',
        'Surgical ward',
        'Maternity ward',
      ]);
    });

    it('lists every ward of a big hospital in the order sent', () => {
      const wards = Array.from({ length: 26 }, (_, i) => ({ id: `w${i}`, name: `Ward ${i + 1}` }));
      const view = render(listOf({ wards }));
      const ward = q<HTMLSelectElement>(view, '[data-testid=admitted-ward]');
      expect(ward.options.length).toBe(27);
      expect(ward.options[26].textContent!.trim()).toBe('Ward 26');
    });

    it('switches cards per row and keeps the choice', async () => {
      const view = render();
      q<HTMLButtonElement>(view, '[data-cols="4"]').click();
      await settle();
      expect(q(view, '[data-testid=admitted-cards]').classList.contains('cols-4')).toBe(true);
      expect(q(view, '[data-cols="4"]').getAttribute('aria-pressed')).toBe('true');
      expect(q(view, '[data-cols="3"]').getAttribute('aria-pressed')).toBe('false');
      expect(state.cols()).toBe(4);
      q<HTMLButtonElement>(view, '[data-cols="2"]').click();
      await settle();
      expect(q(view, '[data-testid=admitted-cards]').classList.contains('cols-2')).toBe(true);
    });

    it('shows an empty-hospital message without a toolbar', () => {
      const view = render(listOf({ items: [] }));
      expect(view.textContent).toContain('No one is admitted right now.');
      expect(view.querySelector('[data-testid=admitted-toolbar]')).toBeNull();
    });

    it('shows the stale notice with the time of the last good answer', () => {
      const view = render(listOf(), 'Could not refresh. Showing data from 1:00 PM.');
      expect(q(view, '[data-testid=admitted-stale]').textContent).toContain(
        'Could not refresh. Showing data from 1:00 PM.',
      );
      expect(render(listOf(), null).querySelector('[data-testid=admitted-stale]')).toBeNull();
    });
  });

  describe('cards and table', () => {
    it('draws one card per patient with the server abilities', () => {
      const view = render();
      expect(names(view)).toEqual(['Asha Patil', 'Ravi Shah', 'Sunita More']);
      expect(view.querySelectorAll('app-patient-card').length).toBe(3);
      expect(view.querySelectorAll('.pcacts button').length).toBe(9);
    });

    it('shows the table of today when Table is chosen, without the Cards per row switch', async () => {
      const view = render();
      q<HTMLButtonElement>(view, '[data-view="table"]').click();
      await settle();
      expect(view.querySelector('[data-testid=admitted-cards]')).toBeNull();
      expect(view.querySelector('[data-testid=admitted-cols]')).toBeNull();
      expect(q(view, '[data-view="table"]').getAttribute('aria-pressed')).toBe('true');
      const heads = Array.from(view.querySelectorAll('.ipth [role=columnheader]')).map((h) => h.textContent!.trim());
      expect(heads).toEqual(['Patient', 'Bed', 'Doctor', 'Running bill', 'Deposit', 'Status', 'Open']);
      const rows = view.querySelectorAll('.iptr:not(.ipth)');
      expect(rows.length).toBe(3);
      expect(rows[0].textContent).toContain('IP/26-27/0042 · 56 F · Emergency · day 3');
      expect(rows[0].textContent).toContain('Deposit OK');
      expect(rows[2].textContent).toContain('Deposit low');
      expect(q(view, '[aria-label="Open Asha Patil"]')).not.toBeNull();
    });

    it('keeps the hidden Open header inside the table scroll box, so a 360 px page does not scroll sideways (F-3)', async () => {
      const view = render();
      q<HTMLButtonElement>(view, '[data-view="table"]').click();
      await settle();
      const table = q(view, '.iptbl');
      const hidden = q(view, '.ipth .sr-only');
      expect(getComputedStyle(table).overflowX).toBe('auto');
      // An absolutely positioned child is clipped by the scroll box only when the box is its containing block.
      expect(getComputedStyle(table).position).toBe('relative');
      expect(table.contains(hidden)).toBe(true);
      expect(hidden.textContent).toBe('Open');
    });

    it('returns to the cards and the Cards per row switch', async () => {
      const view = render();
      q<HTMLButtonElement>(view, '[data-view="table"]').click();
      await settle();
      q<HTMLButtonElement>(view, '[data-view="cards"]').click();
      await settle();
      expect(names(view).length).toBe(3);
      expect(view.querySelector('[data-testid=admitted-cols]')).not.toBeNull();
    });

    it('keeps the chosen cards per row after Table and back', async () => {
      const view = render();
      q<HTMLButtonElement>(view, '[data-cols="4"]').click();
      q<HTMLButtonElement>(view, '[data-view="table"]').click();
      await settle();
      q<HTMLButtonElement>(view, '[data-view="cards"]').click();
      await settle();
      expect(q(view, '[data-testid=admitted-cards]').classList.contains('cols-4')).toBe(true);
    });

    it('opens the table row in the admission sheet main view', async () => {
      const view = render();
      q<HTMLButtonElement>(view, '[data-view="table"]').click();
      await settle();
      q<HTMLButtonElement>(view, '[aria-label="Open Ravi Shah"]').click();
      expect(opened).toEqual([{ id: 'a2', start: 'details' }]);
    });
  });

  describe('search and ward filter', () => {
    it('narrows the cards as the user types and returns all when cleared', async () => {
      const view = render();
      await type(view, 'sun');
      expect(names(view)).toEqual(['Sunita More']);
      await type(view, '');
      expect(names(view).length).toBe(3);
    });

    it('searches by UHID, bed code and IP number, ignoring case and edge spaces', async () => {
      const view = render();
      await type(view, ' pt-000077 ');
      expect(names(view)).toEqual(['Ravi Shah']);
      await type(view, '102-a');
      expect(names(view)).toEqual(['Sunita More']);
      await type(view, 'ip/26-27/0042');
      expect(names(view)).toEqual(['Asha Patil']);
    });

    it('filters by ward, and needs both ward and text to match', async () => {
      const view = render();
      await pickWard(view, WARD_A);
      expect(names(view)).toEqual(['Asha Patil', 'Sunita More']);
      await type(view, 'sunita');
      expect(names(view)).toEqual(['Sunita More']);
      await type(view, 'ravi');
      expect(names(view)).toEqual([]);
      await pickWard(view, WARD_B);
      expect(names(view)).toEqual(['Ravi Shah']);
    });

    it('shows "No patients match these filters." without the paging buttons', async () => {
      const view = render(listOf({ items: manyRows(61) }));
      await type(view, 'nobody');
      expect(q(view, '[data-testid=admitted-empty]').textContent!.trim()).toBe('No patients match these filters.');
      expect(view.querySelector('[data-testid=admitted-paging]')).toBeNull();
    });

    it('shows the same message for a ward with no patient', async () => {
      const view = render();
      await pickWard(view, WARD_EMPTY);
      expect(q(view, '[data-testid=admitted-empty]').textContent!.trim()).toBe('No patients match these filters.');
    });

    describe('result count in the polite region (R36-4)', () => {
      const announce = (view: HTMLElement) => q(view, '[data-testid=admitted-announce]');

      it('keeps one polite region that is empty until the user searches', () => {
        const view = render();
        expect(announce(view).getAttribute('aria-live')).toBe('polite');
        expect(announce(view).textContent).toBe('');
      });

      it('announces "Showing N of M patients" after a search and after a ward change', async () => {
        const view = render();
        await type(view, 'sun');
        expect(announce(view).textContent).toBe('Showing 1 of 3 patients');
        await pickWard(view, WARD_A);
        expect(announce(view).textContent).toBe('Showing 1 of 3 patients');
        await type(view, '');
        expect(announce(view).textContent).toBe('Showing 2 of 3 patients');
      });

      it('announces "Showing all M patients" when the filters are cleared', async () => {
        const view = render();
        await type(view, 'sun');
        await type(view, '');
        expect(announce(view).textContent).toBe('Showing all 3 patients');
      });

      it('announces "No patients match these filters." in the same region for an empty result', async () => {
        const view = render();
        await type(view, 'nobody');
        expect(announce(view).textContent).toBe('No patients match these filters.');
        await pickWard(view, WARD_EMPTY);
        expect(announce(view).textContent).toBe('No patients match these filters.');
      });

      it('does not clear the region between keystrokes, and the table view has the same region', async () => {
        const view = render();
        const seen: string[] = [];
        const observer = new MutationObserver(() => seen.push(announce(view).textContent ?? ''));
        observer.observe(announce(view), { childList: true, characterData: true, subtree: true });
        await type(view, 's');
        await type(view, 'su');
        await Promise.resolve();
        observer.disconnect();
        expect(seen.every((t) => t !== '')).toBe(true);
        q<HTMLElement>(view, '[data-view=table]').click();
        await settle();
        expect(announce(view)).not.toBeNull();
      });
    });

    it('applies the search and ward to the table and keeps them when the view changes', async () => {
      const view = render();
      await pickWard(view, WARD_A);
      await type(view, 'asha');
      q<HTMLButtonElement>(view, '[data-view="table"]').click();
      await settle();
      const rows = view.querySelectorAll('.iptr:not(.ipth)');
      expect(rows.length).toBe(1);
      expect(rows[0].textContent).toContain('Asha Patil');
      expect(q<HTMLInputElement>(view, '[data-testid=admitted-search]').value).toBe('asha');
      expect(q<HTMLSelectElement>(view, '[data-testid=admitted-ward]').value).toBe(WARD_A);
      await type(view, 'zzz');
      expect(q(view, '[data-testid=admitted-empty]').textContent!.trim()).toBe('No patients match these filters.');
      q<HTMLButtonElement>(view, '[data-view="cards"]').click();
      await settle();
      expect(q<HTMLInputElement>(view, '[data-testid=admitted-search]').value).toBe('zzz');
    });

    it('keeps the filters on the device: nothing is stored in the browser', async () => {
      const set = vi.spyOn(Storage.prototype, 'setItem');
      const view = render();
      await type(view, 'asha');
      await pickWard(view, WARD_A);
      expect(set).not.toHaveBeenCalled();
      set.mockRestore();
    });

    it('keeps the search, ward, view and cards per row when the list is replaced (60-second reload)', async () => {
      const view = render(listOf({ items: manyRows(61) }));
      await type(view, 'patient 1');
      await pickWard(view, WARD_A);
      q<HTMLButtonElement>(view, '[data-cols="4"]').click();
      await settle();
      fixture.componentRef.setInput('list', listOf({ items: manyRows(61), asOf: '2026-10-24T07:31:00Z' }));
      await settle();
      expect(q<HTMLInputElement>(view, '[data-testid=admitted-search]').value).toBe('patient 1');
      expect(q<HTMLSelectElement>(view, '[data-testid=admitted-ward]').value).toBe(WARD_A);
      expect(q(view, '[data-testid=admitted-cards]').classList.contains('cols-4')).toBe(true);
    });

    it('falls back to "All wards" when the chosen ward is no longer a ward of the clinic', async () => {
      const view = render();
      await pickWard(view, WARD_A);
      fixture.componentRef.setInput('list', listOf({ wards: [{ id: WARD_B, name: 'Surgical ward' }] }));
      await settle();
      expect(q<HTMLSelectElement>(view, '[data-testid=admitted-ward]').value).toBe('all');
    });
  });

  describe('paging', () => {
    it('draws all cards for 24 or fewer, without the paging line', () => {
      const view = render(listOf({ items: manyRows(24) }));
      expect(names(view).length).toBe(24);
      expect(view.querySelector('[data-testid=admitted-paging]')).toBeNull();
    });

    it('draws 24 of 61 with "Showing 24 of 61 patients", then 48 and all', async () => {
      const view = render(listOf({ items: manyRows(61) }));
      expect(names(view).length).toBe(24);
      const paging = q(view, '[data-testid=admitted-paging]');
      expect(paging.textContent).toContain('Showing 24 of 61 patients');
      expect(q(view, '[data-testid=show-more]').textContent!.trim()).toBe('Show 24 more');
      expect(q(view, '[data-testid=show-all]').textContent!.trim()).toBe('Show all');
      q<HTMLButtonElement>(view, '[data-testid=show-more]').click();
      await settle();
      expect(names(view).length).toBe(48);
      expect(q(view, '[data-testid=admitted-paging]').textContent).toContain('Showing 48 of 61 patients');
      expect(q(view, '[data-testid=admitted-announce]').textContent).toBe('Showing 48 of 61 patients');
      q<HTMLButtonElement>(view, '[data-testid=show-more]').click();
      await settle();
      expect(names(view).length).toBe(61);
      expect(view.querySelector('[data-testid=admitted-paging]')).toBeNull();
      expect(q(view, '[data-testid=admitted-announce]').textContent).toBe('Showing all 61 patients');
    });

    it('draws everything with "Show all" and moves the focus to the first new card', async () => {
      const view = render(listOf({ items: manyRows(61) }));
      const all = q<HTMLButtonElement>(view, '[data-testid=show-all]');
      all.focus();
      all.click();
      await settle();
      expect(names(view).length).toBe(61);
      expect(view.querySelector('[data-testid=admitted-paging]')).toBeNull();
      const first = view.querySelectorAll<HTMLElement>('[data-testid=card-name]')[24];
      expect(document.activeElement).toBe(first);
    });

    it('keeps the focus on "Show 24 more" while the button stays', async () => {
      const view = render(listOf({ items: manyRows(61) }));
      const more = q<HTMLButtonElement>(view, '[data-testid=show-more]');
      more.focus();
      more.click();
      await settle();
      expect(document.activeElement).toBe(q(view, '[data-testid=show-more]'));
    });

    it('moves the focus to the first new card when the last page is drawn', async () => {
      const view = render(listOf({ items: manyRows(30) }));
      const more = q<HTMLButtonElement>(view, '[data-testid=show-more]');
      more.focus();
      more.click();
      await settle();
      expect(names(view).length).toBe(30);
      expect(view.querySelector('[data-testid=show-more]')).toBeNull();
      expect(document.activeElement).toBe(view.querySelectorAll<HTMLElement>('[data-testid=card-name]')[24]);
    });

    it('starts again at 24 when the search, the ward or the view changes', async () => {
      const view = render(listOf({ items: manyRows(61) }));
      q<HTMLButtonElement>(view, '[data-testid=show-more]').click();
      await settle();
      expect(names(view).length).toBe(48);
      await type(view, 'patient');
      expect(names(view).length).toBe(24);
      q<HTMLButtonElement>(view, '[data-testid=show-all]').click();
      await settle();
      await pickWard(view, WARD_A);
      expect(q(view, '[data-testid=admitted-paging]').textContent).toContain('Showing 24 of 31 patients');
      q<HTMLButtonElement>(view, '[data-testid=show-more]').click();
      await settle();
      q<HTMLButtonElement>(view, '[data-view="table"]').click();
      await settle();
      q<HTMLButtonElement>(view, '[data-view="cards"]').click();
      await settle();
      expect(names(view).length).toBe(24);
    });

    it('keeps "Show all" after a reload that adds a patient', async () => {
      const view = render(listOf({ items: manyRows(40) }));
      q<HTMLButtonElement>(view, '[data-testid=show-all]').click();
      await settle();
      fixture.componentRef.setInput('list', listOf({ items: manyRows(41) }));
      await settle();
      expect(names(view).length).toBe(41);
    });

    it('shows the table with all matching patients (no paging in the table)', async () => {
      const view = render(listOf({ items: manyRows(61) }));
      q<HTMLButtonElement>(view, '[data-view="table"]').click();
      await settle();
      expect(view.querySelectorAll('.iptr:not(.ipth)').length).toBe(61);
      expect(view.querySelector('[data-testid=admitted-paging]')).toBeNull();
    });
  });

  describe('actions', () => {
    it('opens the admission sheet at the right start view and the two sheets by id', () => {
      const view = render(listOf({ card: ABILITIES_OWNER }));
      const card = view.querySelectorAll('app-patient-card')[1];
      card.querySelector<HTMLButtonElement>('[data-testid=card-name]')!.click();
      card.querySelector<HTMLButtonElement>('[data-act=transfer]')!.click();
      card.querySelector<HTMLButtonElement>('[data-act=discharge]')!.click();
      card.querySelector<HTMLButtonElement>('[data-act=nursing]')!.click();
      card.querySelector<HTMLButtonElement>('[data-act=emr]')!.click();
      expect(opened).toEqual([
        { id: 'a2', start: 'details' },
        { id: 'a2', start: 'move' },
        { id: 'a2', start: 'discharge' },
      ]);
      expect(nursing).toEqual(['a2']);
      expect(emr).toEqual(['a2']);
    });

    it('draws only Transfer for front desk and no clinical flags', () => {
      const items = listOf().items.map(withoutClinical);
      const view = render(listOf({ card: ABILITIES_FRONT_DESK, items }));
      const buttons = Array.from(view.querySelectorAll('.pcacts button')).map((b) => b.textContent!.trim());
      expect(buttons).toEqual(['Transfer', 'Transfer', 'Transfer']);
      expect(view.textContent).not.toMatch(/Allergy|EWS|Vitals due|Not seen/);
    });

    it('shows Deposit low only for the roles the server names', () => {
      expect(render(listOf({ card: ABILITIES_NURSE })).textContent).not.toContain('Deposit low');
      expect(render(listOf({ card: ABILITIES_OWNER })).querySelector('[data-flag=deposit]')).not.toBeNull();
    });
  });

  it('keeps what was chosen when the panel is drawn again (a change of tab)', async () => {
    const view = render();
    await type(view, 'asha');
    q<HTMLButtonElement>(view, '[data-cols="2"]').click();
    await settle();
    fixture.destroy();
    const again = mount(listOf());
    expect(q<HTMLInputElement>(again, '[data-testid=admitted-search]').value).toBe('asha');
    expect(names(again)).toEqual(['Asha Patil']);
    expect(q(again, '[data-testid=admitted-cards]').classList.contains('cols-2')).toBe(true);
  });
});
