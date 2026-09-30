import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';

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
    productCount: 7,
    productDiscount: 3,
    productDescription: 'Fresh milk',
    productCategoryId: 5,
    productVersion: 4,
    ...overrides,
  };
}

/**
 * The editor's contract with the backend.
 *
 * <p>Exercised through the real HTTP stack rather than a stubbed service, because
 * the thing under test is the wire contract: the version has to be captured from
 * the detail read and travel as a multipart field, and a rejected edit has to
 * leave the draft alone instead of quietly resubmitting it.
 */
describe('ProductEditComponent', () => {
  let component: ProductEditComponent;
  let fixture: ComponentFixture<ProductEditComponent>;
  let httpMock: HttpTestingController;

  const isUpdateRequest = (request: { url: string }): boolean => request.url === UPDATE_URL;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProductEditComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  /** Creates the component and completes the three reads ngOnInit issues. */
  function create(loaded: IProductData = detail()): void {
    fixture = TestBed.createComponent(ProductEditComponent);
    component = fixture.componentInstance;
    component.productId = 42;
    fixture.detectChanges();

    httpMock.expectOne((r) => r.url === PRODUCT_URL).flush(loaded);
    httpMock.expectOne((r) => r.url === DESCRIPTION_URL).flush({ productId: 42, descriptionList: [] });
    httpMock.expectOne((r) => r.url === IMAGE_URL).flush(new Blob(['bytes']));
    fixture.detectChanges();
  }

  /** Issues the save and hands back the pending request so the test can assert
   *  on its multipart body and choose the response. */
  function save(): TestRequest {
    component.saveProduct();
    return httpMock.expectOne(isUpdateRequest);
  }

  function bodyOf(request: TestRequest): FormData {
    return request.request.body as FormData;
  }

  function rejectAsConflict(request: TestRequest): void {
    request.flush(JSON.stringify({ code: 'PRODUCT_EDIT_CONFLICT', status: 409 }), {
      status: 409,
      statusText: 'Conflict',
    });
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
  }

  it('should create', () => {
    create();

    expect(component).toBeTruthy();
  });

  it('captures the product version from the detail load', () => {
    create(detail({ productVersion: 12 }));

    expect(component.productVersion).toBe(12);
  });

  it('submits the captured version as expectedVersion in the multipart body', () => {
    create(detail({ productVersion: 12 }));

    const body = bodyOf(save());

    expect(body.get('expectedVersion')).toBe('12');
    expect(body.get('productId')).toBe('42');
    expect(body.get('productCount')).toBe('7');
  });

  it('refuses to save before a version is known instead of guessing one', () => {
    create(detail({ productVersion: undefined }));

    component.saveProduct();

    expect(component.validationError).toBeTruthy();
    expect(httpMock.match(isUpdateRequest)).toHaveSize(0);
  });

  it('keeps the draft and does not retry when the save is rejected as a conflict', () => {
    create();
    component.productName = 'Edited Name';
    component.count = 999;

    const request = save();
    expect(bodyOf(request).get('productName')).toBe('Edited Name');

    rejectAsConflict(request);
    fixture.detectChanges();

    expect(component.editConflict).toBeTrue();
    expect(component.productName).toBe('Edited Name');
    expect(component.count).toBe(999);
    expect(component.productVersion).toBe(4);
    // The decisive assertion: nothing was re-sent behind the administrator's
    // back. An automatic retry with a fresh version would silently reapply this
    // very draft over whatever moved the stock.
    httpMock.expectNone(isUpdateRequest);
  });

  it('offers an explicit reload instead of a silent one', () => {
    create();
    rejectAsConflict(save());
    fixture.detectChanges();

    const banner = fixture.nativeElement.querySelector('[data-testid="edit-conflict"]');
    expect(banner).toBeTruthy();
    const reloadButton = fixture.nativeElement.querySelector('[data-testid="reload-and-review"]');
    expect(reloadButton).toBeTruthy();

    // Nothing was fetched on the administrator's behalf until they asked.
    httpMock.expectNone((r) => r.url === PRODUCT_URL);

    component.reloadProduct();
    httpMock
      .expectOne((r) => r.url === PRODUCT_URL)
      .flush(detail({ productCount: 3, productName: 'Server Name', productVersion: 5 }));
    fixture.detectChanges();

    expect(component.count).toBe(3);
    expect(component.productName).toBe('Server Name');
    expect(component.productVersion).toBe(5);
    expect(component.editConflict).toBeFalse();
  });

  it('requires a reviewed save after the reload rather than replaying the draft', () => {
    create();
    rejectAsConflict(save());

    component.reloadProduct();
    httpMock.expectOne((r) => r.url === PRODUCT_URL).flush(detail({ productVersion: 5 }));

    // Reloading reads; it never writes.
    httpMock.expectNone(isUpdateRequest);

    const body = bodyOf(save());
    expect(body.get('expectedVersion')).toBe('5');
    expect(body.get('productName')).toBe('Milk');
  });

  /**
   * The modal stays open after a save, so the next save must carry the version
   * its own write produced.
   *
   * <p>It must not come from a re-read. The regression this replaces re-read the
   * version from the detail endpoint, which is a second and later read: a
   * checkout reserving stock between the save and that read hands the editor a
   * version that vouches for a stock count this form never displayed, and the
   * next absolute-count write resurrects the reserved units. The version
   * travels back on the save response instead.
   */
  it('adopts the version the save itself produced so the next save can succeed', () => {
    create(detail({ productVersion: 4 }));

    accept(save(), 5);

    expect(component.productVersion).toBe(5);
    // The only detail read is the initial load. A post-save refresh is the bug.
    httpMock.expectNone((r) => r.url === PRODUCT_URL);

    expect(bodyOf(save()).get('expectedVersion')).toBe('5');
  });

  it('lets two consecutive saves succeed without any intervening write', () => {
    create(detail({ productVersion: 4 }));

    accept(save(), 5);

    // The second request has to be captured and accepted before the third save:
    // saveProduct() refuses to run again while one is in flight, so issuing a
    // save without holding its request would silently no-op.
    const second = save();
    expect(bodyOf(second).get('expectedVersion')).toBe('5');
    accept(second, 6);

    expect(component.productVersion).toBe(6);
  });

  it('refuses the next save rather than guessing when the response carries no version', () => {
    create(detail({ productVersion: 4 }));

    // A save that succeeded but reported no version: the editor must not keep
    // the superseded version, and must not invent one.
    save().flush('File uploaded successfully', { status: 200, statusText: 'OK' });
    fixture.detectChanges();

    expect(component.productVersion).toBeNull();

    component.saveProduct();

    expect(httpMock.match(isUpdateRequest)).toHaveSize(0);
    expect(component.validationError).toBeTruthy();
  });

  it('surfaces an ordinary failure as a message rather than as a conflict', () => {
    create();

    component.saveProduct();
    httpMock
      .expectOne(isUpdateRequest)
      .flush('Product name is required', { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(component.editConflict).toBeFalse();
    expect(component.saveError).toBe('Product name is required');
    expect(fixture.nativeElement.querySelector('[data-testid="edit-conflict"]')).toBeNull();
  });
});
