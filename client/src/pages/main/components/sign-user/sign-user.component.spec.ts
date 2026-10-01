import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { SignUserComponent } from './sign-user.component';

describe('SignUserComponent', () => {
  let component: SignUserComponent;
  let fixture: ComponentFixture<SignUserComponent>;

  const query = <T extends Element>(selector: string): T => {
    const element = fixture.nativeElement.querySelector(selector);
    if (!element) {
      throw new Error(`Expected element ${selector} to be rendered`);
    }
    return element as T;
  };

  const inputType = (selector: string): string | null =>
    query<HTMLInputElement>(selector).getAttribute('type');

  const clickToggle = (testId: string): void => {
    query<HTMLButtonElement>(`[data-testid="${testId}"]`).click();
    fixture.detectChanges();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SignUserComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    })
    .compileComponents();

    fixture = TestBed.createComponent(SignUserComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  describe('password visibility', () => {
    it('shows and hides the login password from the toggle button', () => {
      expect(inputType('#loginPassword2')).toBe('password');

      clickToggle('toggle-login-password');
      expect(inputType('#loginPassword2')).toBe('text');

      clickToggle('toggle-login-password');
      expect(inputType('#loginPassword2')).toBe('password');
    });

    it('keeps the password and its confirmation independent while signing up', () => {
      component.openSign();
      fixture.detectChanges();

      expect(inputType('#loginPassword')).toBe('password');
      expect(inputType('#loginPasswordConfirm')).toBe('password');

      clickToggle('toggle-signup-password');
      expect(inputType('#loginPassword')).toBe('text');
      expect(inputType('#loginPasswordConfirm')).toBe('password');

      clickToggle('toggle-signup-password-confirm');
      expect(inputType('#loginPassword')).toBe('text');
      expect(inputType('#loginPasswordConfirm')).toBe('text');
    });

    it('keeps the new password and its confirmation independent', () => {
      component.isLoginPhaseActive = false;
      component.isNewPasswordPhaseActive = true;
      fixture.detectChanges();

      expect(inputType('#newPassword')).toBe('password');
      expect(inputType('#newPasswordConfirm')).toBe('password');

      clickToggle('toggle-new-password');
      expect(inputType('#newPassword')).toBe('text');
      expect(inputType('#newPasswordConfirm')).toBe('password');

      clickToggle('toggle-new-password-confirm');
      expect(inputType('#newPassword')).toBe('text');
      expect(inputType('#newPasswordConfirm')).toBe('text');
    });

    it('hides a revealed password again when the phase changes', () => {
      clickToggle('toggle-login-password');
      expect(inputType('#loginPassword2')).toBe('text');

      component.openSign();
      fixture.detectChanges();
      component.openLogin();
      fixture.detectChanges();

      expect(inputType('#loginPassword2')).toBe('password');
      expect(component.isPasswordVisible('loginPassword')).toBeFalse();
    });

    it('renders no password toggle in the email-only reset phase', () => {
      component.openResetPassword();
      fixture.detectChanges();

      expect(
        fixture.nativeElement.querySelectorAll('.password-toggle').length,
      ).toBe(0);
      expect(fixture.nativeElement.querySelector('input[type="password"]')).toBeNull();
    });

    it('exposes the toggle state to assistive technology', () => {
      const toggle = query<HTMLButtonElement>(
        '[data-testid="toggle-login-password"]',
      );

      expect(toggle.getAttribute('aria-label')).toBe('Şifreyi göster');
      expect(toggle.getAttribute('aria-pressed')).toBe('false');

      clickToggle('toggle-login-password');

      expect(toggle.getAttribute('aria-label')).toBe('Şifreyi gizle');
      expect(toggle.getAttribute('aria-pressed')).toBe('true');
      expect(toggle.querySelector('i')?.getAttribute('aria-hidden')).toBe(
        'true',
      );
    });

    it('contains each toggle inside its own field box', () => {
      const toggle = query<HTMLButtonElement>(
        '[data-testid="toggle-login-password"]',
      );
      const wrapper = toggle.parentElement as HTMLElement;

      expect(wrapper.classList).toContain('password-field');
      expect(getComputedStyle(wrapper).position).toBe('relative');
      expect(wrapper.querySelector('input')).toBe(
        query<HTMLInputElement>('#loginPassword2'),
      );
    });

    it('does not close the modal when a toggle is clicked', () => {
      const router = TestBed.inject(Router);
      const navigateSpy = spyOn(router, 'navigate');

      clickToggle('toggle-login-password');

      expect(navigateSpy).not.toHaveBeenCalled();
      expect(component.isLoginPhaseActive).toBeTrue();
    });
  });
});
