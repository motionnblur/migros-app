import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { ProductAdderComponent } from './product-adder.component';
import { RestService } from '../../../../services/rest/rest.service';
import { EventService } from '../../../../services/event/event.service';

describe('ProductAdderComponent', () => {
  let component: ProductAdderComponent;
  let fixture: ComponentFixture<ProductAdderComponent>;
  let restServiceSpy: jasmine.SpyObj<RestService>;
  let eventServiceSpy: jasmine.SpyObj<EventService>;

  beforeEach(async () => {
    restServiceSpy = jasmine.createSpyObj<RestService>('RestService', [
      'uploadProductData',
    ]);
    eventServiceSpy = jasmine.createSpyObj<EventService>('EventService', [
      'trigger',
    ]);

    await TestBed.configureTestingModule({
      imports: [ProductAdderComponent],
      providers: [
        { provide: RestService, useValue: restServiceSpy },
        { provide: EventService, useValue: eventServiceSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductAdderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should not call upload when productName is invalid', () => {
    component.productName = '   ';
    component.subCategoryName = 'Sub';
    component.selectedFormValue = 1;
    component.selectedImage = new File(['x'], 'x.png', { type: 'image/png' });

    component.uploadProductData();

    expect(restServiceSpy.uploadProductData).not.toHaveBeenCalled();
    expect(component.validationError).toBe('Product name is required.');
  });

  it('should call upload when form data is valid', () => {
    restServiceSpy.uploadProductData.and.returnValue(of(true));

    component.productName = 'Milk';
    component.subCategoryName = 'Dairy';
    component.price = 10;
    component.count = 3;
    component.discount = 0;
    component.description = 'Fresh';
    component.selectedFormValue = 1;
    component.selectedImage = new File(['x'], 'x.png', { type: 'image/png' });

    component.uploadProductData();

    expect(restServiceSpy.uploadProductData).toHaveBeenCalled();
    expect(eventServiceSpy.trigger).toHaveBeenCalledWith('productAdded');
  });

  it('should show backend error when upload fails', () => {
    restServiceSpy.uploadProductData.and.returnValue(
      throwError(() => ({ error: 'Product name is required' }))
    );

    component.productName = 'Milk';
    component.subCategoryName = 'Dairy';
    component.price = 10;
    component.count = 3;
    component.discount = 0;
    component.description = 'Fresh';
    component.selectedFormValue = 1;
    component.selectedImage = new File(['x'], 'x.png', { type: 'image/png' });

    component.uploadProductData();

    expect(component.validationError).toBe('Product name is required');
  });
});
