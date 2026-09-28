import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { Subject } from 'rxjs';

import { MainComponent } from './main.component';
import { AuthService } from '../../services/auth/auth.service';
import { SupportRealtimeService } from '../../services/support-realtime/support-realtime.service';
import { ISupportRealtimeEvent } from '../../interfaces/support/ISupportRealtimeEvent';

describe('MainComponent', () => {
  let component: MainComponent;
  let fixture: ComponentFixture<MainComponent>;
  let element: HTMLElement;
  let router: Router;
  let httpMock: HttpTestingController;
  let supportStub: {
    events$: Subject<ISupportRealtimeEvent>;
    connect: jasmine.Spy;
    disconnect: jasmine.Spy;
  };

  beforeEach(async () => {
    supportStub = {
      events$: new Subject<ISupportRealtimeEvent>(),
      connect: jasmine.createSpy('connect'),
      disconnect: jasmine.createSpy('disconnect'),
    };

    await TestBed.configureTestingModule({
      imports: [MainComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: SupportRealtimeService, useValue: supportStub },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    httpMock = TestBed.inject(HttpTestingController);

    fixture = TestBed.createComponent(MainComponent);
    component = fixture.componentInstance;
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();

    const sessionRequest = httpMock.expectOne((req) =>
      req.url.includes('/user/session')
    );
    sessionRequest.flush({ userMail: '' });
    fixture.detectChanges();
  });

  function loginAs(userMail: string): void {
    TestBed.inject(AuthService).refreshUserSession().subscribe();
    const sessionRequest = httpMock.expectOne((req) =>
      req.url.includes('/user/session')
    );
    sessionRequest.flush({ userMail });
    fixture.detectChanges();
  }

  function query<T extends Element = HTMLElement>(selector: string): T {
    return element.querySelector(selector) as T;
  }

  function click(selector: string): void {
    query<HTMLElement>(selector).click();
    fixture.detectChanges();
  }

  function lastNavigationCommand(): unknown {
    const spy = router.navigate as jasmine.Spy;
    return spy.calls.mostRecent().args[0];
  }

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('exposes accessible landing controls', () => {
    expect(query('.support-fab').getAttribute('aria-label')).toBe(
      'Canlı Destek'
    );
    expect(query('form[role="search"]')).toBeTruthy();
    expect(query('#site-header-search')).toBeTruthy();
  });

  describe('when logged out', () => {
    beforeEach(() => {
      spyOn(router, 'navigate');
    });

    it('sends cart, order tracking and support controls to the login modal', () => {
      click('button[aria-label="Sepetim"]');
      expect(lastNavigationCommand()).toEqual([
        { outlets: { modal: ['login'] } },
      ]);

      click('button[aria-label="Sipariş Takibi"]');
      expect(lastNavigationCommand()).toEqual([
        { outlets: { modal: ['login'] } },
      ]);

      click('.support-fab');
      expect(lastNavigationCommand()).toEqual([
        { outlets: { modal: ['login'] } },
      ]);
    });

    it('opens the login modal from the account control', () => {
      click('.site-header__login');
      expect(lastNavigationCommand()).toEqual([
        { outlets: { modal: ['login'] } },
      ]);
      expect(query('.site-header__dropdown')).toBeNull();
    });
  });

  describe('when logged in', () => {
    beforeEach(() => {
      spyOn(router, 'navigate');
      loginAs('ayse@example.com');
    });

    it('connects realtime support for the signed-in account', () => {
      expect(supportStub.connect).toHaveBeenCalledWith('ayse@example.com');
    });

    it('opens the existing destinations for account and support controls', () => {
      const openMenuAndClick = (label: string): void => {
        click('.site-header__account-toggle');
        const item = Array.from(
          element.querySelectorAll('[role="menuitem"]')
        ).find((entry) => entry.textContent?.includes(label)) as HTMLButtonElement;

        expect(item).toBeTruthy();
        item.click();
        fixture.detectChanges();
      };

      openMenuAndClick('Sepetim');
      expect(lastNavigationCommand()).toEqual([
        { outlets: { modal: ['cart'] } },
      ]);

      openMenuAndClick('Profilim');
      expect(lastNavigationCommand()).toEqual([
        { outlets: { modal: ['profile'] } },
      ]);

      openMenuAndClick('Siparişlerim');
      expect(lastNavigationCommand()).toEqual([
        { outlets: { modal: ['order-history'] } },
      ]);

      click('.support-fab');
      expect(lastNavigationCommand()).toEqual([
        { outlets: { modal: ['support'] } },
      ]);
    });

    it('tracks the account menu expanded state', () => {
      const toggle = query<HTMLButtonElement>('.site-header__account-toggle');
      expect(toggle.getAttribute('aria-expanded')).toBe('false');

      toggle.click();
      fixture.detectChanges();
      expect(query('.site-header__account-toggle').getAttribute('aria-expanded')).toBe(
        'true'
      );

      document.dispatchEvent(new Event('click', { bubbles: true }));
      fixture.detectChanges();
      expect(query('.site-header__account-toggle').getAttribute('aria-expanded')).toBe(
        'false'
      );
    });
  });

  describe('category search', () => {
    beforeEach(() => {
      spyOn(router, 'navigate');
    });

    it('navigates to the landing query parameter on explicit submit', () => {
      const input = query<HTMLInputElement>('#site-header-search');
      input.value = '  çiçek  ';
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();

      expect(router.navigate).not.toHaveBeenCalled();

      query('form').dispatchEvent(
        new Event('submit', { bubbles: true, cancelable: true })
      );
      fixture.detectChanges();

      expect(router.navigate).toHaveBeenCalledWith(['/'], {
        queryParams: { q: 'çiçek' },
        fragment: 'categories',
      });
    });

    it('clears the query when an empty search is submitted', () => {
      query('form').dispatchEvent(
        new Event('submit', { bubbles: true, cancelable: true })
      );
      fixture.detectChanges();

      expect(router.navigate).toHaveBeenCalledWith(['/'], {
        queryParams: {},
        fragment: 'categories',
      });
    });

    it('keeps the header field aligned with the active query parameter', () => {
      component.searchQuery = 'dondurma';
      fixture.detectChanges();

      const input = query<HTMLInputElement>('#site-header-search');
      expect(input.value).toBe('dondurma');
    });
  });
});
