import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ShellStore } from '../../../core/shell/shell.store';
import { TEAM_ROLES, TeamMember } from './team.model';
import { TeamPage } from './team.page';

const BASE = '/api/v1/platform';

const OWNER: TeamMember = {
  id: 'm-owner',
  name: 'Dr. Asha Kulkarni',
  mobileMasked: '••••••1001',
  role: 'OWNER',
  status: 'ACTIVE',
  lastLoginAt: null,
  doctor: { registrationNo: '' },
};
const KEEPER: TeamMember = {
  id: 'm-keeper',
  name: 'Test Store Staff One',
  mobileMasked: '••••••7411',
  role: 'STORE_STAFF',
  status: 'ACTIVE',
  lastLoginAt: null,
};

/**
 * SAH-78-4 (DEC-062 Q3, OQ-78-40): the role list offers "Store staff" only while Pharmacy & Inventory
 * is on for the clinic. With the module off the list is exactly as before.
 */
describe('TeamPage role list and Store staff', () => {
  let fixture: ComponentFixture<TeamPage>;
  let http: HttpTestingController;

  async function open(menu: string[], members: TeamMember[] = [OWNER]) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        {
          provide: ShellStore,
          useValue: { me: () => ({ userId: 'u1', membershipId: 'm-owner', role: 'OWNER', menu }) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TeamPage);
    fixture.detectChanges();
    http.expectOne(`${BASE}/team`).flush(members);
    http.expectOne(`${BASE}/role-permissions`).flush([]);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.componentInstance as unknown as {
      roles(): readonly string[];
      rolesFor(role: string): readonly string[];
    };
  }

  afterEach(() => http.verify());

  it('lists the same roles as before while the module is off', async () => {
    const page = await open(['today', 'queue', 'config']);
    expect(page.roles()).toEqual(TEAM_ROLES);
    expect(page.roles()).not.toContain('STORE_STAFF');
  });

  it('adds Store staff at the end of the list while the module is on', async () => {
    const page = await open(['today', 'pharmacy', 'config']);
    expect(page.roles()).toEqual([...TEAM_ROLES, 'STORE_STAFF']);
  });

  it('still shows a Store staff member their own role when the module is off', async () => {
    const page = await open(['today', 'config'], [OWNER, KEEPER]);
    expect(page.roles()).not.toContain('STORE_STAFF');
    expect(page.rolesFor('STORE_STAFF')).toContain('STORE_STAFF');
    expect(page.rolesFor('NURSE')).toEqual(TEAM_ROLES);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Store staff');
  });
});
