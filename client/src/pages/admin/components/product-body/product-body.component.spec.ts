import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { ProductBodyComponent } from './product-body.component';

describe('ProductBodyComponent', () => {
  let component: ProductBodyComponent;
  let fixture: ComponentFixture<ProductBodyComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProductBodyComponent],
      providers: [
        provideNoopAnimations(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
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
