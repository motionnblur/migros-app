import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';

import { AdminApiService } from './admin-api.service';
import { IProductUpdater } from '../../interfaces/IProductUpdater';

const UPDATE_URL = '/admin/panel/updateProduct';

function updater(overrides: Partial<IProductUpdater> = {}): IProductUpdater {
  return {
    adminId: 1,
    productId: 42,
    productName: 'Milk',
    subCategoryName: 'Dairy',
    productPrice: 12.5,
    productCount: 7,
    productDiscount: 3,
    productDescription: 'Fresh milk',
    selectedImage: undefined,
    categoryValue: 5,
    expectedVersion: 4,
    ...overrides,
  };
}

describe('AdminApiService product updates', () => {
  let service: AdminApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AdminApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('submits the expected version alongside the absolute stock count', () => {
    service.updateProductData(updater()).subscribe();

    const request = httpMock.expectOne(UPDATE_URL);
    expect(request.request.method).toBe('POST');

    const body = request.request.body as FormData;
    expect(body.get('expectedVersion')).toBe('4');
    expect(body.get('productId')).toBe('42');
    expect(body.get('productCount')).toBe('7');

    request.flush('File uploaded successfully', { status: 200, statusText: 'OK' });
  });

  it('never omits the expected version, not even for version zero', () => {
    // A freshly created product legitimately sits at version 0. Dropping the
    // field when it is falsy would turn the newest product into the one thing
    // the backend can never accept.
    service.updateProductData(updater({ expectedVersion: 0 })).subscribe();

    const request = httpMock.expectOne(UPDATE_URL);
    expect((request.request.body as FormData).get('expectedVersion')).toBe('0');
    request.flush('File uploaded successfully', { status: 200, statusText: 'OK' });
  });

  it('carries the selected image through as multipart', () => {
    const image = new File(['bytes'], 'shot.png', { type: 'image/png' });
    service.updateProductData(updater({ selectedImage: image })).subscribe();

    const request = httpMock.expectOne(UPDATE_URL);
    const body = request.request.body as FormData;
    expect(body.get('selectedImage')).toBe(image);
    expect(body.get('expectedVersion')).toBe('4');

    request.flush('File uploaded successfully', { status: 200, statusText: 'OK' });
  });

  it('does not invent an image part when none was chosen', () => {
    service.updateProductData(updater({ selectedImage: undefined })).subscribe();

    const request = httpMock.expectOne(UPDATE_URL);
    expect((request.request.body as FormData).get('selectedImage')).toBeNull();

    request.flush('File uploaded successfully', { status: 200, statusText: 'OK' });
  });

  it('reports a rejected edit as an error instead of a failed save', () => {
    let status: number | undefined;
    let errored = false;
    service.updateProductData(updater()).subscribe({
      next: (saved: boolean) => (status = saved ? 200 : 0),
      error: (error) => {
        errored = true;
        status = error.status;
      },
    });

    const request = httpMock.expectOne(UPDATE_URL);
    request.flush(JSON.stringify({ code: 'PRODUCT_EDIT_CONFLICT', status: 409 }), {
      status: 409,
      statusText: 'Conflict',
    });

    expect(errored).toBeTrue();
    expect(status).toBe(409);
  });

  it('keeps upload requests free of the product-edit version', () => {
    service
      .uploadProductData({
        adminId: 1,
        productName: 'Milk',
        subCategoryName: 'Dairy',
        productPrice: 12.5,
        productCount: 7,
        productDiscount: 3,
        productDescription: 'Fresh milk',
        selectedImage: null,
        categoryValue: 5,
      })
      .subscribe();

    const request = httpMock.expectOne('/admin/panel/uploadProduct');
    expect((request.request.body as FormData).get('expectedVersion')).toBeNull();
    request.flush('File uploaded successfully', { status: 200, statusText: 'OK' });
  });
});
