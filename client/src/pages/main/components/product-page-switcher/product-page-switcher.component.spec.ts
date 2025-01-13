import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ProductPageSwitcherComponent } from './product-page-switcher.component';

describe('ProductPageSwitcherComponent', () => {
  let component: ProductPageSwitcherComponent;
  let fixture: ComponentFixture<ProductPageSwitcherComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProductPageSwitcherComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ProductPageSwitcherComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
