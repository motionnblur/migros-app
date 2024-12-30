import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ProductBuyComponent } from './product-buy.component';

describe('ProductBuyComponent', () => {
  let component: ProductBuyComponent;
  let fixture: ComponentFixture<ProductBuyComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProductBuyComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ProductBuyComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
