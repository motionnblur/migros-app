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

  it('omits the account panel entirely when logged out', () => {
    expect(query('.site-header__login')).toBeTruthy();
    expect(query('.site-header__account')).toBeNull();
    expect(element.querySelector('#site-header-account-panel')).toBeNull();
    expect(document.getElementById('site-header-account-panel')).toBeNull();
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

  describe('account disclosure', () => {
    beforeEach(() => {
      establishSession('ayse@example.com');
    });

    function toggleButton(): HTMLButtonElement {
      return query<HTMLButtonElement>('.site-header__account-toggle');
    }

    function openToggle(): HTMLButtonElement {
      const toggle = toggleButton();
      toggle.click();
      fixture.detectChanges();
      return toggle;
    }

    function panel(): HTMLElement | null {
      return query<HTMLElement>('#site-header-account-panel');
    }

    function panelButtons(): HTMLButtonElement[] {
      return Array.from(
        element.querySelectorAll<HTMLButtonElement>(
          '#site-header-account-panel button'
        )
      );
    }

    function pressEscape(): boolean {
      const event = new KeyboardEvent('keydown', {
        key: 'Escape',
        bubbles: true,
        cancelable: true,
      });
      const notPrevented = document.dispatchEvent(event);
      fixture.detectChanges();
      return notPrevented;
    }

    it('renders the signed-in account control with a collapsed disclosure', () => {
      const toggle = toggleButton();
      expect(toggle).toBeTruthy();
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      const controls = toggle.getAttribute('aria-controls');
      expect(controls).toBe('site-header-account-panel');
      expect(toggle.hasAttribute('aria-haspopup')).toBe(false);

      const collapsed = panel();
      expect(collapsed).toBeTruthy();
      expect(collapsed?.id).toBe(controls ?? '');
      expect(document.getElementById('site-header-account-panel')).toBe(
        collapsed
      );
      expect(collapsed?.hidden).toBe(true);
      expect(collapsed?.hasAttribute('hidden')).toBe(true);
      expect(getComputedStyle(collapsed as Element).display).toBe('none');

      const buttons = panelButtons();
      expect(buttons.length).toBe(4);
      buttons.forEach((button) => {
        expect(button.offsetParent).toBeNull();
      });
    });

    it('associates the toggle with the revealed panel and removes menu roles', () => {
      const collapsedPanel = panel() as HTMLElement;
      expect(collapsedPanel).toBeTruthy();

      const toggle = openToggle();

      const revealed = panel();
      expect(revealed).toBeTruthy();
      expect(revealed).toBe(collapsedPanel);
      expect(document.getElementById('site-header-account-panel')).toBe(
        collapsedPanel
      );
      expect(revealed?.tagName).toBe('UL');
      const controls = toggle.getAttribute('aria-controls');
      expect(controls).toBe('site-header-account-panel');
      expect(revealed?.id).toBe(controls ?? '');
      expect(revealed?.hidden).toBe(false);
      expect(revealed?.hasAttribute('hidden')).toBe(false);
      expect(revealed?.hasAttribute('role')).toBe(false);

      const forbiddenRoles = Array.from(element.querySelectorAll('[role]'))
        .map((node) => node.getAttribute('role'))
        .filter(
          (role) => role === 'menu' || role === 'menuitem' || role === 'none'
        );
      expect(forbiddenRoles).toEqual([]);
      expect(element.querySelectorAll('[aria-haspopup]').length).toBe(0);
    });

    it('never applies forbidden menu ARIA semantics in either state', () => {
      const forbiddenRoles = (): (string | null)[] =>
        Array.from(element.querySelectorAll('[role]'))
          .map((node) => node.getAttribute('role'))
          .filter(
            (role) => role === 'menu' || role === 'menuitem' || role === 'none'
          );

      expect(forbiddenRoles()).toEqual([]);
      expect(element.querySelectorAll('[aria-haspopup]').length).toBe(0);

      openToggle();

      expect(forbiddenRoles()).toEqual([]);
      expect(element.querySelectorAll('[aria-haspopup]').length).toBe(0);
    });

    it('keeps the account actions as native buttons in document order', () => {
      openToggle();
      expect(panel()?.hidden).toBe(false);
      const buttons = panelButtons();

      expect(
        buttons.map((button) =>
          button.textContent?.replace(/\s+/g, ' ').trim()
        )
      ).toEqual(['Sepetim', 'Profilim', 'Siparişlerim', 'Çıkış Yap']);

      buttons.forEach((button) => {
        expect(button instanceof HTMLButtonElement).toBe(true);
        expect(button.disabled).toBe(false);
        expect(button.getAttribute('tabindex')).toBeNull();
        expect(button.closest('li')).toBeTruthy();
        expect(button.offsetParent).not.toBeNull();
        button.focus();
        expect(document.activeElement).toBe(button);
      });
    });

    it('emits the exact account actions from the panel buttons', () => {
      const emitted: string[] = [];
      component.action.subscribe((action) => emitted.push(action));

      const expectations = [
        { label: 'Sepetim', action: 'cart' },
        { label: 'Profilim', action: 'profile' },
        { label: 'Siparişlerim', action: 'orderHistory' },
        { label: 'Çıkış Yap', action: 'logout' },
      ];

      expectations.forEach((entry, index) => {
        openToggle();

        const item = panelButtons().find((button) =>
          button.textContent?.includes(entry.label)
        ) as HTMLButtonElement;
        expect(item).toBeTruthy();

        item.click();
        fixture.detectChanges();

        expect(emitted).toEqual(
          expectations.slice(0, index + 1).map((e) => e.action)
        );
        expect(panel()).toBeTruthy();
        expect(panel()?.hidden).toBe(true);
        expect(panel()?.hasAttribute('hidden')).toBe(true);
        expect(toggleButton().getAttribute('aria-expanded')).toBe('false');
      });
    });

    it('closes the disclosure on escape, prevents the default and restores focus', () => {
      const toggle = openToggle();
      expect(toggle.getAttribute('aria-expanded')).toBe('true');
      expect(panel()?.hidden).toBe(false);

      toggle.focus();
      expect(document.activeElement).toBe(toggle);

      const panelItem = panelButtons()[0];
      panelItem.focus();
      expect(document.activeElement).toBe(panelItem);

      const notPrevented = pressEscape();

      expect(notPrevented).toBe(false);
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(panel()).toBeTruthy();
      expect(panel()?.hidden).toBe(true);
      expect(getComputedStyle(panel() as Element).display).toBe('none');
      expect(document.activeElement).toBe(toggle);
    });

    it('ignores escape while the disclosure is already closed', () => {
      const toggle = toggleButton();
      const actionButton = query<HTMLButtonElement>(
        'button[aria-label="Sipariş Takibi"]'
      );
      actionButton.focus();
      expect(document.activeElement).toBe(actionButton);

      const notPrevented = pressEscape();

      expect(notPrevented).toBe(true);
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(panel()).toBeTruthy();
      expect(panel()?.hidden).toBe(true);
      expect(document.activeElement).toBe(actionButton);
      expect(document.activeElement).not.toBe(toggle);
    });

    it('closes the disclosure on outside clicks without emitting an action', () => {
      const emitted: string[] = [];
      component.action.subscribe((action) => emitted.push(action));

      const toggle = openToggle();
      const actionButton = query<HTMLButtonElement>(
        'button[aria-label="Sipariş Takibi"]'
      );
      actionButton.focus();
      expect(document.activeElement).toBe(actionButton);

      document.dispatchEvent(new Event('click', { bubbles: true }));
      fixture.detectChanges();

      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(panel()).toBeTruthy();
      expect(panel()?.hidden).toBe(true);
      expect(emitted).toEqual([]);
      expect(document.activeElement).toBe(actionButton);
    });

    it('shows the signed-in label and a logout action', () => {
      expect(query('.site-header__account-name').textContent).toContain(
        'ayse@example.com'
      );

      const emitted: string[] = [];
      component.action.subscribe((action) => emitted.push(action));

      openToggle();
      const logoutItem = panelButtons().find((button) =>
        button.textContent?.includes('Çıkış Yap')
      ) as HTMLButtonElement;
      logoutItem.click();

      expect(emitted).toEqual(['logout']);
    });
  });
});
