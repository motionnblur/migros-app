import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute } from '@angular/router';

import { ProductBuyComponent } from './product-buy.component';

describe('ProductBuyComponent', () => {
  let component: ProductBuyComponent;
  let fixture: ComponentFixture<ProductBuyComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProductBuyComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: {} },
      ],
    })
    .compileComponents();

    fixture = TestBed.createComponent(ProductBuyComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('formatPrice should always display two decimal places', () => {
    expect(component.formatPrice(50)).toBe('50.00');
    expect(component.formatPrice(50.5)).toBe('50.50');
    expect(component.formatPrice(50.99)).toBe('50.99');
  });

  it('formatPrice should fall back to two decimals when value is missing', () => {
    expect(component.formatPrice(undefined)).toBe('0.00');
    expect(component.formatPrice(null)).toBe('0.00');
  });
});
