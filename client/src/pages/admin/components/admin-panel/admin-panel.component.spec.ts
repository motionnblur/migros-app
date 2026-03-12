import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of, Subject } from 'rxjs';

import { AdminPanelComponent } from './admin-panel.component';
import { EventService } from '../../../../services/event/event.service';
import { RestService } from '../../../../services/rest/rest.service';
import { SupportRealtimeService } from '../../../../services/support-realtime/support-realtime.service';
import { AuthService } from '../../../../services/auth/auth.service';

describe('AdminPanelComponent', () => {
  let component: AdminPanelComponent;
  let fixture: ComponentFixture<AdminPanelComponent>;

  let eventServiceSpy: jasmine.SpyObj<EventService>;
  let restServiceSpy: jasmine.SpyObj<RestService>;
  let supportRealtimeServiceSpy: jasmine.SpyObj<SupportRealtimeService> & {
    events$: Subject<any>;
  };
  let authServiceSpy: jasmine.SpyObj<AuthService>;
  let routerSpy: jasmine.SpyObj<Router>;

  beforeEach(async () => {
    eventServiceSpy = jasmine.createSpyObj<EventService>('EventService', [
      'on',
      'off',
      'trigger',
    ]);

    restServiceSpy = jasmine.createSpyObj<RestService>('RestService', [
      'getSupportUsersForAdmin',
      'getBannedSupportUsersForAdmin',
      'getSupportMessagesForAdmin',
      'sendSupportReplyFromAdmin',
      'editSupportMessageForAdmin',
      'deleteSupportMessageForAdmin',
      'closeSupportChatForAdmin',
      'banSupportUserFromAdmin',
      'unbanSupportUserFromAdmin',
    ]);

    restServiceSpy.getSupportUsersForAdmin.and.returnValue(of([]));
    restServiceSpy.getBannedSupportUsersForAdmin.and.returnValue(of([]));
    restServiceSpy.getSupportMessagesForAdmin.and.returnValue(of([]));
    restServiceSpy.editSupportMessageForAdmin.and.returnValue(of(true));
    restServiceSpy.deleteSupportMessageForAdmin.and.returnValue(of(true));

    const supportSpyBase = jasmine.createSpyObj<SupportRealtimeService>(
      'SupportRealtimeService',
      ['connect', 'disconnect']
    );
    supportRealtimeServiceSpy = Object.assign(supportSpyBase, {
      events$: new Subject<any>(),
    });

    authServiceSpy = jasmine.createSpyObj<AuthService>('AuthService', [
      'logoutAdmin',
    ]);
    authServiceSpy.logoutAdmin.and.callFake((onComplete?: () => void) => {
      onComplete?.();
    });

    routerSpy = jasmine.createSpyObj<Router>('Router', ['navigate']);
    routerSpy.navigate.and.returnValue(Promise.resolve(true));

    await TestBed.configureTestingModule({
      imports: [AdminPanelComponent],
      providers: [
        { provide: EventService, useValue: eventServiceSpy },
        { provide: RestService, useValue: restServiceSpy },
        { provide: SupportRealtimeService, useValue: supportRealtimeServiceSpy },
        { provide: AuthService, useValue: authServiceSpy },
        { provide: Router, useValue: routerSpy },
        { provide: ActivatedRoute, useValue: { data: of({ section: 'home' }) } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminPanelComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should logout admin and navigate to /admin', () => {
    component.logoutAdmin();

    expect(authServiceSpy.logoutAdmin).toHaveBeenCalled();
    expect(supportRealtimeServiceSpy.disconnect).toHaveBeenCalled();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/admin']);
    expect(component.isLoggingOut).toBeFalse();
  });
});
