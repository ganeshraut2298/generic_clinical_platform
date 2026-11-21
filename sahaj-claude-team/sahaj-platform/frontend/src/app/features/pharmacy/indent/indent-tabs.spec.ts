import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { InventoryPage } from '../inventory/inventory.page';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { PharmacyContext } from '../pharmacy.model';
import { CENTRAL_CONTEXT, HOD_CONTEXT, WARD_CONTEXT } from './indent.fixtures';

const BASE = '/api/v1/pharmacy';

describe('Inventory tabs of work package F', () => {
  async function tabs(context: PharmacyContext): Promise<string[]> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    const fixture = TestBed.createComponent(InventoryPage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    http.match(() => true);
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('[role="tab"]')).map((t) => t.textContent?.trim() ?? '');
  }

  it('lists Ward / store indents and Waiting for action for a ward user who raises and receives', async () => {
    expect(await tabs(WARD_CONTEXT)).toEqual(['Batch inventory & expiry', 'Store stock', 'Ward / store indents', 'Waiting for action', 'Stock transfer']);
  });

  it('lists the indent tabs and the purchase tabs for a Central Store user, and the approvals inbox for an HOD', async () => {
    expect(await tabs(CENTRAL_CONTEXT)).toEqual([
      'Batch inventory & expiry',
      'Store stock',
      'Stock adjustments',
      'Purchase orders',
      'Receive stock (GRN)',
      'Vendor return & debit note',
      'Ward / store indents',
      'Waiting for action',
      'Stock transfer',
    ]);
  });

  it('follows the capabilities the server lists: no Approvals inbox without APPROVALS_INBOX, no purchase tabs without PURCHASE', async () => {
    const withoutInbox = await tabs({ ...HOD_CONTEXT, capabilities: ['INVENTORY'] });
    expect(withoutInbox).not.toContain('Approvals inbox');
    const withInbox = await tabs(HOD_CONTEXT);
    expect(withInbox).toContain('Approvals inbox');
    const noPurchase = await tabs({ ...CENTRAL_CONTEXT, capabilities: ['INVENTORY'] });
    expect(noPurchase).not.toContain('Purchase orders');
  });
});
