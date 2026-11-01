import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes)],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('has lazy routes for home, login, setup, the app shell and the admin placeholder', () => {
    const paths = routes.map((r) => r.path);
    expect(paths).toEqual(['', 'login', 'setup', 'app', 'admin', '**']);
    expect(routes[0].loadChildren).toBeDefined();
    expect(routes[1].loadComponent).toBeDefined();
    expect(routes[2].loadComponent).toBeDefined();
    expect(routes[2].canActivate).toBeDefined();
    expect(routes[3].loadComponent).toBeDefined();
    expect(routes[3].canActivate).toBeDefined();
    expect(routes[4].loadComponent).toBeDefined();
  });
});
