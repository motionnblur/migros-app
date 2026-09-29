import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { ProductUpdaterComponent } from './product-updater.component';
import { RestService } from '../../../../services/rest/rest.service';
import { EventService } from '../../../../services/event/event.service';
import { IProductData } from '../../../../interfaces/IProductData';
import { IProductUpdater } from '../../../../interfaces/IProductUpdater';

function detail(overrides: Partial<IProductData> = {}): IProductData {
  return {
    productName: 'Milk',
    subCategoryName: 'Dairy',
    productPrice: 12.5,
    productCount: 7,
    productDiscount: 3,
    productDescription: 'Fresh milk',
    productCategoryId: 5,
    productVersion: 4,
    ...overrides,
  };
}

/**
 * The second, independent product-update path.
 *
 * <p>The admin panel can edit a product either through this inline updater or
 * through the modal editor, and each had to learn the version contract on its
 * own. Asserting it here as well as there is deliberate: a change that only
 * updated one of the two would leave the other writing unguarded absolute counts.
 */
describe('ProductUpdaterComponent', () => {
  let component: ProductUpdaterComponent;
  let fixture: ComponentFixture<ProductUpdaterComponent>;
  let restServiceSpy: jasmine.SpyObj<RestService>;
  let eventServiceSpy: jasmine.SpyObj<EventService>;

  function lastUpdate(): IProductUpdater {
    return restServiceSpy.updateProductData.calls.mostRecent().args[0];
  }

  function rejectAsConflict(): void {
    restServiceSpy.updateProductData.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            statusText: 'Conflict',
            error: JSON.stringify({ code: 'PRODUCT_EDIT_CONFLICT', status: 409 }),
          }),
      ),
    );
  }

  beforeEach(async () => {
    restServiceSpy = jasmine.createSpyObj<RestService>('RestService', [
      'getProductImage',
      'getProductData',
      'updateProductData',
    ]);
    eventServiceSpy = jasmine.createSpyObj<EventService>('EventService', ['trigger']);

    restServiceSpy.getProductImage.and.returnValue(of(new Blob(['bytes'])));
    restServiceSpy.getProductData.and.returnValue(of(detail()));

    await TestBed.configureTestingModule({
      imports: [ProductUpdaterComponent],
      providers: [
        { provide: RestService, useValue: restServiceSpy },
        { provide: EventService, useValue: eventServiceSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductUpdaterComponent);
    component = fixture.componentInstance;
    component.id = 42;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('captures the product version on load', () => {
    restServiceSpy.getProductData.and.returnValue(of(detail({ productVersion: 9 })));
    fixture = TestBed.createComponent(ProductUpdaterComponent);
    const fresh = fixture.componentInstance;
    fresh.id = 42;
    fixture.detectChanges();

    expect(fresh.productVersion).toBe(9);
  });

  it('submits the captured version as expectedVersion', () => {
    restServiceSpy.updateProductData.and.returnValue(of(true));

    component.uploadProductData();

    expect(lastUpdate().expectedVersion).toBe(4);
    expect(lastUpdate().productId).toBe(42);
  });

  it('refuses to submit before a version is known', () => {
    restServiceSpy.getProductData.and.returnValue(of(detail({ productVersion: undefined })));
    fixture = TestBed.createComponent(ProductUpdaterComponent);
    const fresh = fixture.componentInstance;
    fresh.id = 42;
    fixture.detectChanges();

    fresh.uploadProductData();

    expect(restServiceSpy.updateProductData).not.toHaveBeenCalled();
    expect(fresh.validationError).toContain('version');
  });

  it('keeps the draft and does not retry after a rejected update', () => {
    rejectAsConflict();
    component.productName = 'Edited Name';
    component.count = 999;

    component.uploadProductData();

    expect(component.editConflict).toBeTrue();
    expect(component.productName).toBe('Edited Name');
    expect(component.count).toBe(999);
    expect(restServiceSpy.updateProductData).toHaveBeenCalledTimes(1);
  });

  it('offers an explicit reload that replaces the form with the server state', () => {
    rejectAsConflict();
    component.uploadProductData();
    expect(component.editConflict).toBeTrue();

    restServiceSpy.getProductData.calls.reset();
    component.reloadProduct();

    expect(restServiceSpy.getProductData).toHaveBeenCalledWith(42);
    expect(component.count).toBe(7);
    expect(component.productVersion).toBe(4);
    expect(component.editConflict).toBeFalse();
    // The reload is a read. It must not resubmit the rejected draft.
    expect(restServiceSpy.updateProductData).toHaveBeenCalledTimes(1);
  });

  it('renders a reload action in the conflict banner', () => {
    rejectAsConflict();
    component.uploadProductData();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="edit-conflict"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('[data-testid="reload-and-review"]')).toBeTruthy();
  });

  it('refreshes the version after a successful save so the next save can succeed', () => {
    restServiceSpy.updateProductData.and.returnValue(of(true));
    restServiceSpy.getProductData.and.returnValue(of(detail({ productVersion: 6 })));

    component.uploadProductData();

    expect(restServiceSpy.getProductData).toHaveBeenCalledTimes(2);
    expect(component.productVersion).toBe(6);

    component.uploadProductData();
    expect(lastUpdate().expectedVersion).toBe(6);
  });

  it('reports an ordinary failure as a validation error, not as a conflict', () => {
    restServiceSpy.updateProductData.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 400, error: 'Product name is required' })),
    );

    component.uploadProductData();

    expect(component.editConflict).toBeFalse();
    expect(component.validationError).toBe('Product name is required');
  });
});
