import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of, Subject } from 'rxjs';
import { IChatMessage } from '../../../../interfaces/IChatMessage';
import { RestService } from '../../../../services/rest/rest.service';
import { SupportRealtimeService } from '../../../../services/support-realtime/support-realtime.service';
import { ToastService } from '../../services/toast.service';
import { AdminSupportComponent } from './admin-support.component';

describe('AdminSupportComponent', () => {
  let component: AdminSupportComponent;
  let fixture: ComponentFixture<AdminSupportComponent>;
  let restServiceSpy: jasmine.SpyObj<RestService>;
  let supportRealtimeServiceSpy: jasmine.SpyObj<SupportRealtimeService> & {
    events$: Subject<any>;
  };
  let toastServiceSpy: jasmine.SpyObj<ToastService>;

  const customerMail = 'customer@example.com';
  const message: IChatMessage = {
    id: 7,
    message: 'Merhaba',
    sender: 'USER',
    createdAt: '',
  };

  beforeEach(async () => {
    restServiceSpy = jasmine.createSpyObj<RestService>('RestService', [
      'getSupportUsersForAdmin',
      'getBannedSupportUsersForAdmin',
      'getSupportMessagesForAdmin',
      'searchSupportCustomersForAdmin',
      'sendSupportReplyFromAdmin',
      'editSupportMessageForAdmin',
      'deleteSupportMessageForAdmin',
      'closeSupportChatForAdmin',
      'banSupportUserFromAdmin',
      'unbanSupportUserFromAdmin',
    ]);
    restServiceSpy.getSupportUsersForAdmin.and.returnValue(of([customerMail]));
    restServiceSpy.getBannedSupportUsersForAdmin.and.returnValue(of([]));
    restServiceSpy.getSupportMessagesForAdmin.and.returnValue(of([message]));
    restServiceSpy.searchSupportCustomersForAdmin.and.returnValue(of([]));
    restServiceSpy.sendSupportReplyFromAdmin.and.returnValue(of(true));
    restServiceSpy.editSupportMessageForAdmin.and.returnValue(of(true));
    restServiceSpy.deleteSupportMessageForAdmin.and.returnValue(of(true));
    restServiceSpy.closeSupportChatForAdmin.and.returnValue(of(true));
    restServiceSpy.banSupportUserFromAdmin.and.returnValue(of(true));
    restServiceSpy.unbanSupportUserFromAdmin.and.returnValue(of(true));

    const supportServiceBase = jasmine.createSpyObj<SupportRealtimeService>(
      'SupportRealtimeService',
      ['connect', 'disconnect']
    );
    supportRealtimeServiceSpy = Object.assign(supportServiceBase, {
      events$: new Subject<any>(),
    });
    toastServiceSpy = jasmine.createSpyObj<ToastService>('ToastService', [
      'success',
      'error',
    ]);

    await TestBed.configureTestingModule({
      imports: [AdminSupportComponent],
      providers: [
        { provide: RestService, useValue: restServiceSpy },
        { provide: SupportRealtimeService, useValue: supportRealtimeServiceSpy },
        {
          provide: MatDialog,
          useValue: { open: () => ({ afterClosed: () => of(true) }) },
        },
        { provide: ToastService, useValue: toastServiceSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminSupportComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('loads and renders the selected support conversation', () => {
    expect(supportRealtimeServiceSpy.connect).toHaveBeenCalled();
    expect(component.selectedSupportUserMail).toBe(customerMail);
    expect(fixture.nativeElement.querySelector('.support-chat-layout')).toBeTruthy();
    expect(fixture.nativeElement.textContent).toContain('Merhaba');
  });

  it('refreshes the active conversation and sends a reply', () => {
    const initialLoadCount = restServiceSpy.getSupportMessagesForAdmin.calls.count();
    supportRealtimeServiceSpy.events$.next({ userMail: customerMail });

    expect(restServiceSpy.getSupportMessagesForAdmin.calls.count()).toBeGreaterThan(
      initialLoadCount
    );

    component.supportReplyInput = 'Yanıt';
    component.sendSupportReply();
    expect(restServiceSpy.sendSupportReplyFromAdmin).toHaveBeenCalledWith(
      customerMail,
      'Yanıt'
    );
    expect(component.supportReplyInput).toBe('');
  });

  it('debounces customer search and cancels pending work on teardown', fakeAsync(() => {
    component.supportCustomerQuery = '  Ada  ';
    component.onSupportCustomerSearchChange();
    tick(299);
    expect(restServiceSpy.searchSupportCustomersForAdmin).not.toHaveBeenCalled();

    fixture.destroy();
    tick(1);
    expect(restServiceSpy.searchSupportCustomersForAdmin).not.toHaveBeenCalled();
    expect(supportRealtimeServiceSpy.disconnect).toHaveBeenCalled();
  }));

  it('unsubscribes from realtime events and stops polling on teardown', fakeAsync(() => {
    const userLoadCount = restServiceSpy.getSupportUsersForAdmin.calls.count();
    fixture.destroy();
    supportRealtimeServiceSpy.events$.next({ userMail: customerMail });
    tick(5000);

    expect(restServiceSpy.getSupportUsersForAdmin.calls.count()).toBe(userLoadCount);
    expect(supportRealtimeServiceSpy.disconnect).toHaveBeenCalled();
  }));
});
