import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ProductBodyComponent } from './product-body.component';

describe('ProductBodyComponent', () => {
  let component: ProductBodyComponent;
  let fixture: ComponentFixture<ProductBodyComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProductBodyComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ProductBodyComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
