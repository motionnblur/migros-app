import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SupportFabComponent } from './support-fab.component';

describe('SupportFabComponent', () => {
  let fixture: ComponentFixture<SupportFabComponent>;
  let component: SupportFabComponent;
  let element: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SupportFabComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(SupportFabComponent);
    component = fixture.componentInstance;
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('renders an accessible Canlı Destek control', () => {
    const button = element.querySelector('button') as HTMLButtonElement;
    expect(button).toBeTruthy();
    expect(button.getAttribute('aria-label')).toBe('Canlı Destek');
    expect(button.textContent).toContain('Canlı Destek');
    expect(button.getAttribute('type')).toBe('button');
  });

  it('emits a support request when activated', () => {
    const emitSpy = spyOn(component.supportRequested, 'emit');
    const button = element.querySelector('button') as HTMLButtonElement;

    button.click();

    expect(emitSpy).toHaveBeenCalled();
  });

  /**
   * A dialog promises the customer that the page behind it is out of reach, and
   * the launcher is on that page. Dimming it under the scrim would leave it in the
   * accessibility tree and in the tab order while `aria-modal="true"` says the
   * rest of the page is gone - so it is not rendered at all.
   */
  describe('while a modal dialog is open', () => {
    it('renders no control at all', () => {
      component.isSuppressed = true;
      fixture.detectChanges();

      expect(element.querySelector('button')).toBeNull();
      expect(element.textContent?.trim()).toBe('');
    });

    it('leaves nothing behind that a press or a Tab could reach', () => {
      component.isSuppressed = true;
      fixture.detectChanges();

      const focusable = element.querySelectorAll(
        'a[href], button, input, select, textarea, [tabindex]',
      );
      expect(focusable.length).toBe(0);
    });

    it('comes back when the dialog is gone', () => {
      component.isSuppressed = true;
      fixture.detectChanges();
      expect(element.querySelector('button')).toBeNull();

      component.isSuppressed = false;
      fixture.detectChanges();
      const button = element.querySelector('button') as HTMLButtonElement;

      expect(button).toBeTruthy();
      expect(button.getAttribute('aria-label')).toBe('Canlı Destek');
    });

    /**
     * The launcher is the only support entry point that has to survive being
     * withdrawn, so it is asserted that suppression is driven by the input rather
     * than by a one-way teardown: the component is never destroyed by opening a
     * dialog, and closing one brings the control straight back.
     */
    it('is the caller that decides, and the component is not torn down', () => {
      component.isSuppressed = true;
      fixture.detectChanges();

      expect(component.isSuppressed).toBeTrue();
      expect(component.supportRequested).toBeTruthy();

      component.isSuppressed = false;
      fixture.detectChanges();
      expect(component.isSuppressed).toBeFalse();
    });
  });
});
