import { AdmittedViewState } from './admitted-view.state';
import { PAGE_SIZE } from './admitted.model';

describe('AdmittedViewState', () => {
  let state: AdmittedViewState;

  beforeEach(() => {
    state = new AdmittedViewState();
  });

  it('starts as Cards, 3 per row, no filter, one page', () => {
    expect(state.view()).toBe('cards');
    expect(state.cols()).toBe(3);
    expect(state.query()).toBe('');
    expect(state.ward()).toBe('all');
    expect(state.limit()).toBe(PAGE_SIZE);
    expect(state.filtering()).toBe(false);
  });

  it('adds one page at a time and can show all', () => {
    state.showMore();
    expect(state.limit()).toBe(48);
    state.showAll();
    expect(state.limit()).toBe(Number.POSITIVE_INFINITY);
  });

  it('starts again at one page when the search, the ward or the view changes, but not the cards per row', () => {
    state.showMore();
    state.setCols(4);
    expect(state.limit()).toBe(48);
    state.setQuery('asha');
    expect(state.limit()).toBe(24);
    state.showMore();
    state.setWard('w1');
    expect(state.limit()).toBe(24);
    state.showMore();
    state.setView('table');
    expect(state.limit()).toBe(24);
    state.showMore();
    state.setView('table');
    expect(state.limit()).toBe(48);
  });

  it('knows when a filter is on, ignoring blank text', () => {
    state.setQuery('   ');
    expect(state.filtering()).toBe(false);
    state.setQuery('a');
    expect(state.filtering()).toBe(true);
    state.setQuery('');
    state.setWard('w1');
    expect(state.filtering()).toBe(true);
  });
});
