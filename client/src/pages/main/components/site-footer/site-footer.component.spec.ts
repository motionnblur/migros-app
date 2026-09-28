import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { SiteFooterComponent } from './site-footer.component';

describe('SiteFooterComponent', () => {
  let fixture: ComponentFixture<SiteFooterComponent>;
  let component: SiteFooterComponent;
  let element: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SiteFooterComponent],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(SiteFooterComponent);
    component = fixture.componentInstance;
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('renders a semantic footer with a brand summary and a category shortcut', () => {
    const footer = element.querySelector('footer');
    expect(footer).toBeTruthy();

    expect(element.querySelector('.site-footer__summary')?.textContent).toContain(
      '18 kategori'
    );

    const categoryLink = element.querySelector(
      'a.site-footer__link'
    ) as HTMLAnchorElement;
    expect(categoryLink.getAttribute('href')).toContain('#categories');
    expect(categoryLink.textContent).toContain('Kategoriler');
  });

  it('emits order tracking and live support actions', () => {
    const emitted: string[] = [];
    component.action.subscribe((action) => emitted.push(action));

    const buttons = Array.from(
      element.querySelectorAll<HTMLButtonElement>('button.site-footer__link')
    );
    const tracking = buttons.find((button) =>
      button.textContent?.includes('Sipariş Takibi')
    ) as HTMLButtonElement;
    const support = buttons.find((button) =>
      button.textContent?.includes('Canlı Destek')
    ) as HTMLButtonElement;

    expect(tracking).toBeTruthy();
    expect(support).toBeTruthy();

    tracking.click();
    support.click();

    expect(emitted).toEqual(['orderTracker', 'support']);
  });

  it('renders a benefits row that uses bootstrap icons only', () => {
    const benefits = Array.from(
      element.querySelectorAll('.site-footer__benefits li')
    );

    expect(benefits.length).toBeGreaterThan(0);
    for (const benefit of benefits) {
      const icon = benefit.querySelector('i.bi');
      expect(icon).toBeTruthy();
      expect(icon?.getAttribute('aria-hidden')).toBe('true');
    }
  });

  it('does not render the legacy footer screenshot links', () => {
    expect(element.querySelector('img[src*="migros-footer"]')).toBeNull();
    expect(element.querySelectorAll('img').length).toBe(1);
  });
});
