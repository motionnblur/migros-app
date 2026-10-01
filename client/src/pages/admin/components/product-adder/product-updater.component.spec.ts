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
    restServiceSpy.updateProductData.and.returnValue(of({ saved: true, productVersion: 5 }));

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

  /**
   * The panel stays open after a save, so the next save must carry the version
   * its own write produced.
   *
   * <p>It must not come from a re-read. The regression this replaces read the
   * version back from the detail endpoint, which is a second and later read: a
   * checkout reserving stock between the save and that read hands the editor a
   * version that vouches for a stock count this form never displayed, and the
   * next absolute-count write then resurrects the reserved units. The version
   * therefore travels back on the save response itself.
   */
  it('adopts the version the save itself produced so the next save can succeed', () => {
    restServiceSpy.updateProductData.and.returnValue(of({ saved: true, productVersion: 5 }));

    component.uploadProductData();

    expect(component.productVersion).toBe(5);
    // Exactly one read, the initial load. A refresh after saving is the bug.
    expect(restServiceSpy.getProductData).toHaveBeenCalledTimes(1);

    component.uploadProductData();
    expect(lastUpdate().expectedVersion).toBe(5);
  });

  /**
   * Two saves in a row with nothing in between must both be accepted, and the
   * second must carry the version the first produced rather than the version
   * the first superseded.
   */
  it('lets two consecutive saves succeed without any intervening write', () => {
    restServiceSpy.updateProductData.and.returnValue(of({ saved: true, productVersion: 5 }));

    component.uploadProductData();
    expect(lastUpdate().expectedVersion).toBe(4);

    restServiceSpy.updateProductData.and.returnValue(of({ saved: true, productVersion: 6 }));
    component.uploadProductData();

    expect(lastUpdate().expectedVersion).toBe(5);
    expect(restServiceSpy.updateProductData).toHaveBeenCalledTimes(2);
  });

  /**
   * A response that carries no usable version leaves the editor without one, so
   * the next save is refused until a deliberate reload.
   *
   * <p>Keeping the superseded version would make the next save conflict with this
   * editor's own write; guessing one would be an unguarded absolute-count write.
   * Reporting "unknown" is the only honest option.
   */
  it('refuses the next save rather than guessing when the response carries no version', () => {
    restServiceSpy.updateProductData.and.returnValue(of({ saved: true, productVersion: null }));

    component.uploadProductData();

    expect(component.productVersion).toBeNull();

    restServiceSpy.updateProductData.calls.reset();
    component.uploadProductData();

    expect(restServiceSpy.updateProductData).not.toHaveBeenCalled();
    expect(component.validationError).toContain('version');
  });

  it('reports an ordinary failure as a validation error, not as a conflict', () => {
    restServiceSpy.updateProductData.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 400, error: 'Product name is required' })),
    );

    component.uploadProductData();

    expect(component.editConflict).toBeFalse();
    expect(component.validationError).toBe('Product name is required');
  });

  /**
   * The optional package fields on the edit path.
   *
   * <p>Two claims are being made here and they are the ones a regression would
   * quietly undo. The form has to show what is stored, so an administrator editing
   * a product can see the size they are about to change. And the pair has to be
   * submitted on every save - including when it is empty - because omitting it
   * would make "cleared" and "unchanged" the same request, and the size would be
   * unremovable through this form.
   */
  describe('package metadata', () => {
    function reloadWith(overrides: Partial<IProductData>): void {
      restServiceSpy.getProductData.and.returnValue(of(detail(overrides)));
      component.reloadProduct();
      fixture.detectChanges();
    }

    it('shows no package size for a product that has none', () => {
      expect(component.packageAmount).toBeNull();
      expect(component.packageUnit).toBe('');
    });

    it('prefills the stored size from the detail read', () => {
      reloadWith({ packageAmount: 0.75, packageUnit: 'KG' });

      expect(component.packageAmount).toBe(0.75);
      expect(component.packageUnit).toBe('KG');
    });

    it('submits the stored size unchanged when the administrator saves it as loaded', () => {
      restServiceSpy.updateProductData.and.returnValue(of({ saved: true, productVersion: 5 }));
      reloadWith({ packageAmount: 0.75, packageUnit: 'KG' });

      component.uploadProductData();

      expect(lastUpdate().packageAmount).toBe(0.75);
      expect(lastUpdate().packageUnit).toBe('KG');
    });

    /**
     * Clearing the fields is how metadata is removed, so the empty pair has to
     * travel with the save rather than being omitted.
     */
    it('submits both fields as null when the administrator clears them', () => {
      restServiceSpy.updateProductData.and.returnValue(of({ saved: true, productVersion: 5 }));
      reloadWith({ packageAmount: 0.75, packageUnit: 'KG' });
      component.packageAmount = null;
      component.packageUnit = '';

      component.uploadProductData();

      expect(lastUpdate().packageAmount).toBeNull();
      expect(lastUpdate().packageUnit).toBeNull();
    });

    it('submits both fields as null for a product that never had a size', () => {
      restServiceSpy.updateProductData.and.returnValue(of({ saved: true, productVersion: 5 }));

      component.uploadProductData();

      expect(lastUpdate().packageAmount).toBeNull();
      expect(lastUpdate().packageUnit).toBeNull();
    });

    it('refuses a half-filled pair before sending anything', () => {
      component.packageAmount = 500;
      component.packageUnit = '';

      component.uploadProductData();

      expect(restServiceSpy.updateProductData).not.toHaveBeenCalled();
      expect(component.validationError).toContain('go together');
    });

    it('refuses an amount with no unit even after the fields were cleared once', () => {
      component.packageUnit = 'L';
      component.packageAmount = null;

      component.uploadProductData();

      expect(restServiceSpy.updateProductData).not.toHaveBeenCalled();
      expect(component.validationError).toContain('go together');
    });

    it('refuses a fractional item count before sending anything', () => {
      component.packageAmount = 1.5;
      component.packageUnit = 'ADET';

      component.uploadProductData();

      expect(restServiceSpy.updateProductData).not.toHaveBeenCalled();
      expect(component.validationError).toContain('whole number');
    });

    it('accepts a fractional continuous measure', () => {
      restServiceSpy.updateProductData.and.returnValue(of({ saved: true, productVersion: 5 }));
      component.packageAmount = 1.5;
      component.packageUnit = 'L';

      component.uploadProductData();

      expect(lastUpdate().packageAmount).toBe(1.5);
      expect(component.validationError).toBe('');
    });

    it('refuses an amount that is not positive', () => {
      component.packageAmount = 0;
      component.packageUnit = 'KG';

      component.uploadProductData();

      expect(restServiceSpy.updateProductData).not.toHaveBeenCalled();
      expect(component.validationError).toContain('greater than zero');
    });

    /**
     * The amount control must not offer a fraction for a unit that counts items.
     * A step of 1 means the browser rejects `1.5` on the control itself, rather
     * than accepting it and failing the save.
     */
    it('offers whole numbers only for the item unit', () => {
      expect(component.packageAmountStep).toBe(0.001);

      component.packageUnit = 'ADET';
      expect(component.packageAmountStep).toBe(1);
    });

    it('offers every accepted unit and no others', () => {
      expect(component.packageUnits).toEqual(['G', 'KG', 'ML', 'L', 'ADET']);
    });

    /**
     * The conflict path must preserve the draft, package fields included. An
     * administrator whose size change was rejected has to be able to fix and
     * resend it, and a reload that quietly dropped the size would lose the edit.
     */
    it('keeps the draft package size when the backend reports a conflict', () => {
      rejectAsConflict();
      component.packageAmount = 0.75;
      component.packageUnit = 'KG';

      component.uploadProductData();

      expect(component.editConflict).toBeTrue();
      expect(component.packageAmount).toBe(0.75);
      expect(component.packageUnit).toBe('KG');
    });
  });
});
