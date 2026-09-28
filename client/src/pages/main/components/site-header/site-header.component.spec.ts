import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SimpleChange } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { SiteHeaderComponent } from './site-header.component';
import { AuthService } from '../../../../services/auth/auth.service';

describe('SiteHeaderComponent', () => {
  let fixture: ComponentFixture<SiteHeaderComponent>;
  let component: SiteHeaderComponent;
  let httpMock: HttpTestingController;
  let element: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SiteHeaderComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SiteHeaderComponent);
    component = fixture.componentInstance;
    element = fixture.nativeElement as HTMLElement;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  function flushSession(session: { userMail: string }): void {
    const request = httpMock.expectOne((req) =>
      req.url.includes('/user/session')
    );
    request.flush(session);
    fixture.detectChanges();
  }

  function establishSession(userMail: string): void {
    TestBed.inject(AuthService).refreshUserSession().subscribe();
    flushSession({ userMail });
  }

  function query<T extends Element = HTMLElement>(selector: string): T {
    return element.querySelector(selector) as T;
  }

  function submitSearch(value: string): void {
    const input = query<HTMLInputElement>('#site-header-search');
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    query('form').dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true })
    );
    fixture.detectChanges();
  }

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('shows the login control when logged out and emits the login action', () => {
    const loginButton = query<HTMLButtonElement>('.site-header__login');
    expect(loginButton).toBeTruthy();
    expect(loginButton.getAttribute('aria-label')).toBe(
      'Üye Ol veya Giriş Yap'
    );

    const emitted: string[] = [];
    component.action.subscribe((action) => emitted.push(action));

    loginButton.click();
    expect(emitted).toEqual(['login']);
  });

  it('emits the order tracking and cart actions for logged-out visitors', () => {
    const emitted: string[] = [];
    component.action.subscribe((action) => emitted.push(action));

    query<HTMLButtonElement>('button[aria-label="Sipariş Takibi"]').click();
    query<HTMLButtonElement>('button[aria-label="Sepetim"]').click();

    expect(emitted).toEqual(['orderTracker', 'cart']);
  });

  it('submits an explicit trimmed search instead of searching while typing', () => {
    const submitted: string[] = [];
    component.searchSubmitted.subscribe((term) => submitted.push(term));

    const input = query<HTMLInputElement>('#site-header-search');
    input.value = '  çiçek  ';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(submitted.length).toBe(0);

    query('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    fixture.detectChanges();

    expect(submitted).toEqual(['çiçek']);
  });

  it('emits an empty term when the search is submitted blank', () => {
    const submitted: string[] = [];
    component.searchSubmitted.subscribe((term) => submitted.push(term));

    submitSearch('   ');

    expect(submitted).toEqual(['']);
  });

  it('keeps the search field aligned with the active landing query', () => {
    component.searchQuery = 'dondurma';
    component.ngOnChanges({
      searchQuery: new SimpleChange('', 'dondurma', false),
    });
    fixture.detectChanges();

    const input = query<HTMLInputElement>('#site-header-search');
    expect(input.value).toBe('dondurma');
  });

  describe('account menu', () => {
    beforeEach(() => {
      establishSession('ayse@example.com');
    });

    it('renders the signed-in account control with an unexpanded menu', () => {
      const toggle = query<HTMLButtonElement>('.site-header__account-toggle');
      expect(toggle).toBeTruthy();
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(toggle.getAttribute('aria-haspopup')).toBe('menu');
      expect(query('.site-header__dropdown')).toBeNull();
    });

    it('toggles the expanded state and exposes the account destinations', () => {
      const toggle = query<HTMLButtonElement>('.site-header__account-toggle');
      const emitted: string[] = [];
      component.action.subscribe((action) => emitted.push(action));

      toggle.click();
      fixture.detectChanges();

      expect(toggle.getAttribute('aria-expanded')).toBe('true');

      const menuItems = Array.from(
        element.querySelectorAll('[role="menuitem"]')
      ).map((item) => item.textContent?.replace(/\s+/g, ' ').trim());
      expect(menuItems).toEqual([
        'Sepetim',
        'Profilim',
        'Siparişlerim',
        'Çıkış Yap',
      ]);

      const cartItem = Array.from(
        element.querySelectorAll('[role="menuitem"]')
      ).find((item) => item.textContent?.includes('Sepetim')) as HTMLButtonElement;
      cartItem.click();
      fixture.detectChanges();

      expect(emitted).toEqual(['cart']);
      expect(
        query('.site-header__account-toggle').getAttribute('aria-expanded')
      ).toBe('false');
    });

    it('closes the menu on escape and on outside clicks', () => {
      const toggle = query<HTMLButtonElement>('.site-header__account-toggle');

      toggle.click();
      fixture.detectChanges();
      expect(toggle.getAttribute('aria-expanded')).toBe('true');

      document.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })
      );
      fixture.detectChanges();
      expect(
        query('.site-header__account-toggle').getAttribute('aria-expanded')
      ).toBe('false');

      query<HTMLButtonElement>('.site-header__account-toggle').click();
      fixture.detectChanges();
      document.dispatchEvent(new Event('click', { bubbles: true }));
      fixture.detectChanges();
      expect(
        query('.site-header__account-toggle').getAttribute('aria-expanded')
      ).toBe('false');
    });

    it('shows the signed-in label and a logout action', () => {
      expect(query('.site-header__account-name').textContent).toContain(
        'ayse@example.com'
      );

      const emitted: string[] = [];
      component.action.subscribe((action) => emitted.push(action));

      query<HTMLButtonElement>('.site-header__account-toggle').click();
      fixture.detectChanges();
      const logoutItem = Array.from(
        element.querySelectorAll('[role="menuitem"]')
      ).find((item) => item.textContent?.includes('Çıkış Yap')) as HTMLButtonElement;
      logoutItem.click();

      expect(emitted).toEqual(['logout']);
    });
  });
});
