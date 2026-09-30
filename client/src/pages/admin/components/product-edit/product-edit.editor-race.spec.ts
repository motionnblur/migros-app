import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ProductEditComponent } from './product-edit.component';
import { IProductData } from '../../../../interfaces/IProductData';
import { PRODUCT_VERSION_HEADER } from '../../../../services/rest/product-edit-version';

const PRODUCT_URL = '/user/supply/getProductDataWithProductId';
const DESCRIPTION_URL = '/user/supply/getProductDescription';
const IMAGE_URL = '/user/supply/getProductImage';
const UPDATE_URL = '/admin/panel/updateProduct';

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
 * The reservation race, pinned independently of the sibling contract spec.
 *
 * <p>The regression: after a successful save the editor re-read the product and
 * took ONLY `productVersion` from that read, keeping its own field values. A
 * checkout reserving stock in between moves the server to a newer version, so
 * that read returns a version that vouches for a stock count this form never
 * displayed. The next save then submits a stale absolute `productCount`
 * authorized by that newer version, and the reserved units are silently brought
 * back. Nothing errors: the write is well-formed, it is just wrong.
 *
 * <p>The fix is that the version travels back on the save response - the one
 * write whose values it is guaranteed to describe. So the invariant asserted
 * throughout is the same one: *the editor only ever holds a version its own
 * write produced.* A version it merely read is never adopted.
 *
 * <p>Exercised through the real HTTP stack, because the thing under test is the
 * wire contract: the version has to be read off a response header and travel
 * back as a multipart field. A stubbed service could not tell an adopted
 * header from an adopted re-read.
 */
describe('ProductEditComponent version-vs-reservation race', () => {
  let component: ProductEditComponent;
  let fixture: ComponentFixture<ProductEditComponent>;
  let httpMock: HttpTestingController;

  const isUpdateRequest = (request: { url: string }): boolean =>
    request.url === UPDATE_URL;
  const isDetailRequest = (request: { url: string }): boolean =>
    request.url === PRODUCT_URL;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProductEditComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  /** Creates the component and completes the three reads ngOnInit issues. */
  function create(loaded: IProductData = detail()): void {
    fixture = TestBed.createComponent(ProductEditComponent);
    component = fixture.componentInstance;
    component.productId = 42;
    fixture.detectChanges();

    httpMock.expectOne(isDetailRequest).flush(loaded);
    httpMock.expectOne((r) => r.url === DESCRIPTION_URL).flush({ productId: 42, descriptionList: [] });
    httpMock.expectOne((r) => r.url === IMAGE_URL).flush(new Blob(['bytes']));
    fixture.detectChanges();
  }

  function save(): TestRequest {
    component.saveProduct();
    return httpMock.expectOne(isUpdateRequest);
  }

  function bodyOf(request: TestRequest): FormData {
    return request.request.body as FormData;
  }

  /**
   * Accepts the save and supplies the version the write produced, the way the
   * backend does: on the response, after the change has been flushed.
   */
  function accept(request: TestRequest, producedVersion: number): void {
    request.flush('File uploaded successfully', {
      status: 200,
      statusText: 'OK',
      headers: { [PRODUCT_VERSION_HEADER]: String(producedVersion) },
    });
    fixture.detectChanges();
  }

  it('takes the version from the save response and never re-reads the product', () => {
    create(detail({ productVersion: 4, productCount: 10 }));

    accept(save(), 5);

    expect(component.productVersion).toBe(5);
    // The only detail read in this component's life is the initial load. A
    // post-save refresh is the regression itself, and it would be a request the
    // test backend could answer with a version the form never earned.
    httpMock.expectNone(isDetailRequest);
    httpMock.expectNone(isUpdateRequest);
  });

  /**
   * The exact race, in the direction that makes it visible.
   *
   * <p>Server starts at version 1 / stock 10. The administrator saves, which
   * produces version 2. Before the editor could learn anything, a checkout
   * reserves 2 units: the server is now at version 3 / stock 8. A re-read at
   * this moment would hand back 3 - and pairing 3 with this form's stale 10 is
   * exactly the write that resurrects the reserved units.
   *
   * <p>So the direction picked here is: the header (2) is *lower* than what a
   * re-read would return (3). The editor must keep 2, so the next save is
   * rejected for a stale version rather than accepted with a stale count.
   */
  it('keeps its own write\'s version when a concurrent reservation moved the server on', () => {
    create(detail({ productVersion: 1, productCount: 10 }));

    accept(save(), 2);

    // A checkout reserves 2 units right now. The server is at version 3 / stock
    // 8, and nothing told this form about it.
    expect(component.productVersion).toBe(2);
    expect(component.count).toBe(10);

    // The next save submits the version this editor produced. The server is at
    // 3, so it rejects - and rejecting is the safe outcome here. Accepting would
    // mean writing stock back to 10.
    const next = save();
    expect(bodyOf(next).get('expectedVersion')).toBe('2');
    expect(bodyOf(next).get('productCount')).toBe('10');
    httpMock.expectNone(isDetailRequest);
  });

  it('lets two consecutive saves chain versions with no intervening read', () => {
    create(detail({ productVersion: 4 }));

    accept(save(), 5);

    const second = save();
    expect(bodyOf(second).get('expectedVersion')).toBe('5');
    accept(second, 6);

    // Chained off its own writes, and still without a single re-read: the second
    // save is authorized by the first save's version, not by anything observed.
    expect(component.productVersion).toBe(6);
    httpMock.expectNone(isDetailRequest);
  });

  it('refuses the next save rather than guessing when the response carries no version', () => {
    create(detail({ productVersion: 4 }));

    // A save that succeeded but reported no usable version. Keeping 4 would
    // conflict against this editor's own write; inventing 5 would authorize an
    // absolute-count write against a state nobody observed.
    save().flush('File uploaded successfully', { status: 200, statusText: 'OK' });
    fixture.detectChanges();

    expect(component.productVersion).toBeNull();

    component.saveProduct();

    expect(httpMock.match(isUpdateRequest)).toHaveSize(0);
    expect(component.validationError).toBeTruthy();
    httpMock.expectNone(isDetailRequest);
  });

  it('still preserves the draft and never auto-retries after a 409', () => {
    create(detail({ productVersion: 1, productCount: 10 }));
    component.productName = 'Edited Name';
    component.count = 12;

    const request = save();
    expect(bodyOf(request).get('productName')).toBe('Edited Name');
    request.flush(JSON.stringify({ code: 'PRODUCT_EDIT_CONFLICT', status: 409 }), {
      status: 409,
      statusText: 'Conflict',
    });
    fixture.detectChanges();

    expect(component.editConflict).toBeTrue();
    expect(component.productName).toBe('Edited Name');
    expect(component.count).toBe(12);
    // The version the rejected save carried is still the editor's, and the
    // draft is not resubmitted against anything fresh behind the
    // administrator's back.
    expect(component.productVersion).toBe(1);
    expect(httpMock.match(isUpdateRequest)).toHaveSize(0);
    httpMock.expectNone(isDetailRequest);
  });
});
