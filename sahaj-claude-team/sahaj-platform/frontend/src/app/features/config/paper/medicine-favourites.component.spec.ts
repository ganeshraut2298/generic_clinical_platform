import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { MedicineFavouritesComponent } from './medicine-favourites.component';

const URL = '/api/v1/opd/medicines';

const PAGE = {
  items: [
    { id: 'm1', name: 'Paracetamol 650 mg', form: 'Tablet', usualDose: '1 tablet', favourite: true },
    { id: 'm2', name: 'Cetirizine 10 mg', form: 'Tablet', favourite: false },
  ],
  total: 3,
};

describe('MedicineFavouritesComponent', () => {
  let fixture: ComponentFixture<MedicineFavouritesComponent>;
  let http: HttpTestingController;

  async function open(role: Role) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MedicineFavouritesComponent);
    fixture.detectChanges();
    http.expectOne((r) => r.url === URL).flush(PAGE);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  it('lists medicines with favourites as the server ordered them', async () => {
    const el = await open('OWNER');
    const names = Array.from(el.querySelectorAll('.li .main b')).map((b) => b.textContent);
    expect(names).toEqual(['Paracetamol 650 mg', 'Cetirizine 10 mg']);
    expect(el.textContent).toContain('Tablet · 1 tablet');
  });

  it('marks a medicine as a favourite for the clinic', async () => {
    const el = await open('OWNER');
    el.querySelector<HTMLButtonElement>('[aria-label="Add Cetirizine 10 mg to favourites"]')!.click();
    const req = http.expectOne('/api/v1/opd/medicine-favourites/m2');
    expect(req.request.method).toBe('PUT');
    req.flush(null, { status: 204, statusText: 'No Content' });
    fixture.detectChanges();
    expect(el.querySelector('[aria-label="Remove Cetirizine 10 mg from favourites"]')).toBeTruthy();
  });

  it('removes a favourite', async () => {
    const el = await open('OWNER');
    el.querySelector<HTMLButtonElement>('[aria-label="Remove Paracetamol 650 mg from favourites"]')!.click();
    const req = http.expectOne('/api/v1/opd/medicine-favourites/m1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('shows more medicines on request', async () => {
    const el = await open('OWNER');
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Show more'))!.click();
    const req = http.expectOne((r) => r.url === URL);
    expect(req.request.params.get('page')).toBe('1');
    req.flush({ items: [{ id: 'm3', name: 'Ambroxol syrup', favourite: false }], total: 3 });
    fixture.detectChanges();
    expect(el.querySelectorAll('.li').length).toBe(3);
  });

  it('is read-only for a nurse: favourites show as a pill, no star buttons', async () => {
    const el = await open('NURSE');
    expect(el.querySelector('.star')).toBeNull();
    expect(el.querySelector('.pill')?.textContent).toContain('Favourite');
  });
});
