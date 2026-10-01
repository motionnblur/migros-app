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
    expect(eventServiceSpy.trigger.calls.mostRecent().args[0]).toBe('productAdded');
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

  /**
   * The optional package fields on the creation path.
   *
   * <p>The creation form is where a guessed quantity would start, so the two rules
   * that matter most here are the ones that keep a guess out: absent stays absent,
   * and a half-filled pair is refused rather than quietly rounded into "no package
   * data".
   */
  describe('package metadata', () => {
    function fillValidForm(): void {
      component.productName = 'Milk';
      component.subCategoryName = 'Dairy';
      component.price = 10;
      component.count = 3;
      component.discount = 0;
      component.description = 'Fresh';
      component.selectedFormValue = 1;
      component.selectedImage = new File(['x'], 'x.png', { type: 'image/png' });
    }

    function lastUpload() {
      return restServiceSpy.uploadProductData.calls.mostRecent().args[0];
    }

    it('creates a product with no package size when both fields are empty', () => {
      restServiceSpy.uploadProductData.and.returnValue(of(true));
      fillValidForm();

      component.uploadProductData();

      expect(lastUpload().packageAmount).toBeNull();
      expect(lastUpload().packageUnit).toBeNull();
    });

    it('submits a filled pair', () => {
      restServiceSpy.uploadProductData.and.returnValue(of(true));
      fillValidForm();
      component.packageAmount = 1;
      component.packageUnit = 'L';

      component.uploadProductData();

      expect(lastUpload().packageAmount).toBe(1);
      expect(lastUpload().packageUnit).toBe('L');
    });

    it('refuses an amount with no unit before sending anything', () => {
      fillValidForm();
      component.packageAmount = 500;

      component.uploadProductData();

      expect(restServiceSpy.uploadProductData).not.toHaveBeenCalled();
      expect(component.validationError).toContain('go together');
    });

    it('refuses a unit with no amount before sending anything', () => {
      fillValidForm();
      component.packageUnit = 'KG';

      component.uploadProductData();

      expect(restServiceSpy.uploadProductData).not.toHaveBeenCalled();
      expect(component.validationError).toContain('go together');
    });

    it('refuses a fractional item count', () => {
      fillValidForm();
      component.packageAmount = 1.5;
      component.packageUnit = 'ADET';

      component.uploadProductData();

      expect(restServiceSpy.uploadProductData).not.toHaveBeenCalled();
      expect(component.validationError).toContain('whole number');
    });

    it('refuses an amount that is not positive', () => {
      fillValidForm();
      component.packageAmount = -1;
      component.packageUnit = 'KG';

      component.uploadProductData();

      expect(restServiceSpy.uploadProductData).not.toHaveBeenCalled();
      expect(component.validationError).toContain('greater than zero');
    });

    it('offers whole numbers only for the item unit', () => {
      expect(component.packageAmountStep).toBe(0.001);
      component.packageUnit = 'ADET';
      expect(component.packageAmountStep).toBe(1);
    });

    it('offers exactly the accepted units', () => {
      expect(component.packageUnits).toEqual(['G', 'KG', 'ML', 'L', 'ADET']);
    });

    /**
     * Both fields are labelled optional, so an untouched form has to produce a
     * valid request rather than a half-filled one.
     */
    it('renders the optional package fields as part of the creation form', () => {
      const amount = fixture.nativeElement.querySelector('#packageAmount') as HTMLInputElement;
      const unit = fixture.nativeElement.querySelector(
        'select[aria-label="Paket birimi"]',
      ) as HTMLSelectElement;

      expect(amount).toBeTruthy();
      expect(amount.value).toBe('');
      expect(unit).toBeTruthy();
      expect(unit.value).toBe('');
      expect(
        Array.from(unit.options).map((option) => option.value),
      ).toEqual(['', 'G', 'KG', 'ML', 'L', 'ADET']);
    });
  });
});
