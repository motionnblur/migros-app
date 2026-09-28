import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { CategoryButtonComponent } from './category-button.component';

describe('CategoryButtonComponent', () => {
  let component: CategoryButtonComponent;
  let fixture: ComponentFixture<CategoryButtonComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CategoryButtonComponent],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(CategoryButtonComponent);
    component = fixture.componentInstance;
    component.categoryId = 7;
    component.name = 'Dondurma';
    component.image = '/discover-items/dondurma.png';
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('renders a semantic link pointing at the category route', () => {
    const link = fixture.nativeElement.querySelector(
      'a.category-card'
    ) as HTMLAnchorElement;

    expect(link).toBeTruthy();
    expect(link.getAttribute('href')).toBe('/category/7');
    expect(link.textContent?.trim()).toBe('Dondurma');
  });

  it('exposes the route target for the configured category id', () => {
    expect(component.categoryLink).toEqual(['/category', 7]);
  });

  it('renders a lazy image with reserved space and empty alt text', () => {
    const image = fixture.nativeElement.querySelector(
      'img.category-card__image'
    ) as HTMLImageElement;

    expect(image).toBeTruthy();
    expect(image.getAttribute('src')).toBe('/discover-items/dondurma.png');
    expect(image.getAttribute('loading')).toBe('lazy');
    expect(image.getAttribute('alt')).toBe('');
    expect(image.getAttribute('width')).toBe('88');
    expect(image.getAttribute('height')).toBe('88');
  });
});
