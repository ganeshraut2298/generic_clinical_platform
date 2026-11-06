import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ShellApi } from './shell.api';
import { ShellStore } from './shell.store';
import { Me } from './shell.model';

const ME: Me = {
  userId: 'u1',
  name: 'Dr. Meera',
  role: 'OWNER',
  clinic: { name: 'Shree Sai Clinic', brand: 'teal', plan: 'free', planDaysLeft: 300 },
  menu: ['today', 'queue', 'config'],
  preferences: { language: 'en', theme: 'light', menuMode: 'advanced', sidebarCollapsed: false },
};

describe('ShellStore', () => {
  let store: ShellStore;
  let api: { getMe: ReturnType<typeof vi.fn>; updatePreferences: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    api = { getMe: vi.fn(), updatePreferences: vi.fn() };
    TestBed.configureTestingModule({ providers: [{ provide: ShellApi, useValue: api }] });
    store = TestBed.inject(ShellStore);
  });

  it('starts with no user and an empty menu', () => {
    expect(store.me()).toBeNull();
    expect(store.menu()).toEqual([]);
  });

  it('loads /me once and caches it for later calls', () => {
    api.getMe.mockReturnValue(of(ME));
    store.load().subscribe();
    store.load().subscribe();
    expect(api.getMe).toHaveBeenCalledTimes(1);
    expect(store.menu()).toEqual(['today', 'queue', 'config']);
    expect(store.theme()).toBe('light');
  });

  it('falls back to defaults when a preference is missing', () => {
    api.getMe.mockReturnValue(
      of({ ...ME, preferences: {} }),
    );
    store.load().subscribe();
    expect(store.language()).toBe('en');
    expect(store.menuMode()).toBe('advanced');
    expect(store.sidebarCollapsed()).toBe(false);
  });

  it('updatePreferences() applies the server response', () => {
    api.getMe.mockReturnValue(of(ME));
    store.load().subscribe();
    api.updatePreferences.mockReturnValue(
      of({ ...ME, preferences: { ...ME.preferences, theme: 'dark' } }),
    );
    store.updatePreferences({ theme: 'dark' }).subscribe();
    expect(store.theme()).toBe('dark');
  });

  it('updatePreferences() restores the previous value on failure', () => {
    api.getMe.mockReturnValue(of(ME));
    store.load().subscribe();
    api.updatePreferences.mockReturnValue(throwError(() => new Error('network')));
    store.updatePreferences({ theme: 'dark' }).subscribe({ error: () => undefined });
    expect(store.theme()).toBe('light');
  });

  it('reloadMenu() updates the menu without clearing me', () => {
    api.getMe.mockReturnValueOnce(of(ME)).mockReturnValueOnce(of({ ...ME, menu: ['today', 'staff'] }));
    store.load().subscribe();
    store.reloadMenu().subscribe();
    expect(store.menu()).toEqual(['today', 'staff']);
    expect(store.me()).not.toBeNull();
  });

  it('clear() forces the next load() to call the server again', () => {
    api.getMe.mockReturnValue(of(ME));
    store.load().subscribe();
    store.clear();
    store.load().subscribe();
    expect(api.getMe).toHaveBeenCalledTimes(2);
  });
});
