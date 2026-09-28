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
});
