import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { LandingHeroComponent } from './landing-hero.component';

describe('LandingHeroComponent', () => {
  let fixture: ComponentFixture<LandingHeroComponent>;
  let element: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LandingHeroComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(LandingHeroComponent);
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders the landing headline and the category CTA', () => {
    const title = element.querySelector('h1');
    expect(title?.textContent).toContain(
      'İhtiyacın olan her şey, tek sepetle kapında.'
    );

    const cta = element.querySelector('.landing-hero__cta');
    expect(cta?.textContent).toContain('Kategorileri keşfet');
    expect(cta?.getAttribute('type')).toBe('button');
  });

  it('renders the three trust indicators', () => {
    const indicators = Array.from(
      element.querySelectorAll('.landing-hero__trust li')
    ).map((item) => item.textContent?.replace(/\s+/g, ' ').trim());

    expect(indicators).toEqual([
      'Kolay alışveriş',
      'Güvenli ödeme',
      'Canlı destek',
    ]);
  });

  it('uses decorative images with empty alternative text', () => {
    const images = Array.from(element.querySelectorAll('img'));
    expect(images.length).toBe(3);
    for (const image of images) {
      expect(image.getAttribute('alt')).toBe('');
    }
  });
});

describe('LandingHeroComponent category CTA', () => {
  @Component({
    standalone: true,
    imports: [LandingHeroComponent],
    template: `<div id="categories" tabindex="-1"></div><app-landing-hero />`,
  })
  class HostComponent {}

  let fixture: ComponentFixture<HostComponent>;
  let target: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HostComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    target = fixture.nativeElement.querySelector('#categories') as HTMLElement;
  });

  it('scrolls to the category section when the CTA is used', () => {
    const scrollSpy = spyOn(target, 'scrollIntoView');
    const cta = fixture.nativeElement.querySelector(
      '.landing-hero__cta'
    ) as HTMLButtonElement;

    cta.click();
    fixture.detectChanges();

    expect(scrollSpy).toHaveBeenCalledWith(
      jasmine.objectContaining({ block: 'start' })
    );
  });
});
