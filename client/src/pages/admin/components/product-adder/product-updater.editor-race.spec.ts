import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';

import { ProductUpdaterComponent } from './product-updater.component';
import { RestService } from '../../../../services/rest/rest.service';
import { EventService } from '../../../../services/event/event.service';
import { IProductData } from '../../../../interfaces/IProductData';
import { IProductUpdater } from '../../../../interfaces/IProductUpdater';
import { IProductUpdateResult } from '../../../../services/rest/product-edit-version';

function detail(overrides: Partial<IProductData> = {}): IProductData {
  return {
    productName: 'Milk',
    subCategoryName: 'Dairy',
    productPrice: 12.5,
    productCount: 10,
    productDiscount: 3,
    productDescription: 'Fresh milk',
    productCategoryId: 5,
    productVersion: 1,
    ...overrides,
  };
}

/**
 * The reservation race on the inline updater, pinned independently of the
 * modal editor's copy of the same rule.
 *
 * <p>The regression: after a successful save the updater re-read the product
 * and adopted only `productVersion` from that read, keeping its own field
 * values. A checkout reserving stock in between moves the server to a newer
 * version, so that read returns a version vouching for a stock count this form
 * never displayed, and the next absolute-count save silently restores the
 * reserved units. The version now travels back on the save response instead.
 *
 * <p>Proved through a spy rather than the HTTP stack, matching the sibling
 * spec: the updater's boundary is the `IProductUpdateResult` it is handed, so
 * what matters is which version reaches the next request and whether a second
 * read happens at all - not how the header is parsed.
 */
describe('ProductUpdaterComponent version-vs-reservation race', () => {
  let component: ProductUpdaterComponent;
  let fixture: ComponentFixture<ProductUpdaterComponent>;
  let restServiceSpy: jasmine.SpyObj<RestService>;
  let eventServiceSpy: jasmine.SpyObj<EventService>;

  function lastUpdate(): IProductUpdater {
    return restServiceSpy.updateProductData.calls.mostRecent().args[0];
  }

  function respondWith(result: IProductUpdateResult): void {
    restServiceSpy.updateProductData.and.returnValue(of(result));
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
    respondWith({ saved: true, productVersion: 2 });

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

  it('takes the version from the save result and never re-reads the product', () => {
    component.uploadProductData();

    expect(component.productVersion).toBe(2);
    // Exactly one read, the initial load. A refresh after saving is the
    // regression: it would be a second, later read whose version has nothing to
    // do with the values just written.
    expect(restServiceSpy.getProductData).toHaveBeenCalledTimes(1);
    expect(restServiceSpy.getProductData).toHaveBeenCalledWith(42);
  });

  /**
   * The exact race, in the direction that makes it visible.
   *
   * <p>Server starts at version 1 / stock 10. The administrator saves, which
   * produces version 2. A checkout then reserves 2 units, moving the server to
   * version 3 / stock 8 - a state this form never saw. A re-read now would hand
   * back 3, and pairing 3 with this form's stale 10 is the write that brings
   * the reserved units back.
   *
   * <p>So the header version (2) is deliberately *lower* than what a re-read
   * would return (3), and the updater must keep 2: the next save is then refused
   * for a stale version instead of accepted with a stale count.
   */
  it('keeps its own write\'s version when a concurrent reservation moved the server on', () => {
    component.uploadProductData();

    // The reservation happens now, unobserved. Server: version 3, stock 8.
    expect(component.productVersion).toBe(2);
    expect(component.count).toBe(10);

    component.uploadProductData();

    // Authorized by this editor's own write, never by the newer server state.
    expect(lastUpdate().expectedVersion).toBe(2);
    expect(lastUpdate().productCount).toBe(10);
    expect(restServiceSpy.getProductData).toHaveBeenCalledTimes(1);
  });

  it('lets two consecutive saves chain versions with no intervening read', () => {
    component.uploadProductData();
    expect(lastUpdate().expectedVersion).toBe(1);

    respondWith({ saved: true, productVersion: 3 });
    component.uploadProductData();

    expect(lastUpdate().expectedVersion).toBe(2);
    expect(component.productVersion).toBe(3);
    expect(restServiceSpy.updateProductData).toHaveBeenCalledTimes(2);
    expect(restServiceSpy.getProductData).toHaveBeenCalledTimes(1);
  });

  it('refuses the next save rather than guessing when the response carries no version', () => {
    respondWith({ saved: true, productVersion: null });

    component.uploadProductData();

    expect(component.productVersion).toBeNull();

    restServiceSpy.updateProductData.calls.reset();
    component.uploadProductData();

    // No request at all: a guessed version here would be an unguarded
    // absolute-count write against a state nobody observed.
    expect(restServiceSpy.updateProductData).not.toHaveBeenCalled();
    expect(component.validationError).toContain('version');
    expect(restServiceSpy.getProductData).toHaveBeenCalledTimes(1);
  });

  it('still preserves the draft and never auto-retries after a 409', () => {
    restServiceSpy.updateProductData.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            statusText: 'Conflict',
            error: JSON.stringify({ code: 'PRODUCT_EDIT_CONFLICT', status: 409 }),
          }),
      ) as Observable<IProductUpdateResult>,
    );
    component.productName = 'Edited Name';
    component.count = 12;

    component.uploadProductData();

    expect(component.editConflict).toBeTrue();
    expect(component.productName).toBe('Edited Name');
    expect(component.count).toBe(12);
    // The rejected save's version is still the editor's, and the draft is not
    // resubmitted against anything fetched behind the administrator's back.
    expect(component.productVersion).toBe(1);
    expect(restServiceSpy.updateProductData).toHaveBeenCalledTimes(1);
    expect(restServiceSpy.getProductData).toHaveBeenCalledTimes(1);
  });
});
