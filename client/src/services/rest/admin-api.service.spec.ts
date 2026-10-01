import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';

import { AdminApiService } from './admin-api.service';
import { IProductUpdater } from '../../interfaces/IProductUpdater';
import { IProductUploader } from '../../interfaces/IProductUploader';

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
      next: (result) => (status = result.saved ? 200 : 0),
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

/**
 * The optional package parameters, as they leave the browser.
 *
 * <p>These are the assertions that keep a partial form from becoming a rejected
 * request. An admin form hands over `null`, `undefined` and `''` for the same
 * "not filled in" state, and the multipart body is the only place that distinction
 * disappears - an appended `packageUnit=` is read by the server as a unit that was
 * submitted, which is half a pair and a 400.
 */
describe('AdminApiService package metadata', () => {
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

  function uploader(
    overrides: Partial<IProductUploader> = {},
  ): IProductUploader {
    return {
      adminId: 1,
      productName: 'Milk',
      subCategoryName: 'Dairy',
      productPrice: 12.5,
      productCount: 7,
      productDiscount: 3,
      productDescription: 'Fresh milk',
      selectedImage: null,
      categoryValue: 5,
      ...overrides,
    };
  }

  function sendUpload(overrides: Partial<IProductUploader> = {}): FormData {
    service.uploadProductData(uploader(overrides)).subscribe();
    const request = httpMock.expectOne('/admin/panel/uploadProduct');
    const body = request.request.body as FormData;
    request.flush('File uploaded successfully', { status: 200, statusText: 'OK' });
    return body;
  }

  function sendUpdate(overrides: Partial<IProductUpdater> = {}): FormData {
    service.updateProductData(updater(overrides)).subscribe();
    const request = httpMock.expectOne(UPDATE_URL);
    const body = request.request.body as FormData;
    request.flush('File uploaded successfully', { status: 200, statusText: 'OK' });
    return body;
  }

  it('sends a complete pair as multipart fields', () => {
    const body = sendUpload({ packageAmount: 1.5, packageUnit: 'L' });

    expect(body.get('packageAmount')).toBe('1.5');
    expect(body.get('packageUnit')).toBe('L');
  });

  it('sends a complete pair on an update', () => {
    const body = sendUpdate({ packageAmount: 0.75, packageUnit: 'KG' });

    expect(body.get('packageAmount')).toBe('0.75');
    expect(body.get('packageUnit')).toBe('KG');
  });

  /**
   * The client that predates the feature sends nothing, and must keep working.
   */
  it('sends neither parameter when the caller omits both', () => {
    const body = sendUpload();

    expect(body.get('packageAmount')).toBeNull();
    expect(body.get('packageUnit')).toBeNull();
  });

  it('sends neither parameter for explicit nulls', () => {
    const body = sendUpdate({ packageAmount: null, packageUnit: null });

    expect(body.get('packageAmount')).toBeNull();
    expect(body.get('packageUnit')).toBeNull();
  });

  /**
   * Empty strings are what an untouched number input contributes. Appending one
   * would be read as a submitted value and refused as half a pair.
   */
  it('treats empty strings as no package metadata', () => {
    const body = sendUpload({
      packageAmount: null,
      packageUnit: '   ',
    });

    expect(body.get('packageAmount')).toBeNull();
    expect(body.get('packageUnit')).toBeNull();
  });

  /**
   * Only one of the pair is never sent. The backend refuses half a pair, and a
   * client that forwarded it would turn a form mistake into a failed save with a
   * message about the wrong field.
   */
  it('never sends half a pair, whatever the caller passes', () => {
    expect(sendUpload({ packageAmount: 500, packageUnit: null }).get('packageAmount')).toBeNull();
    expect(sendUpdate({ packageAmount: null, packageUnit: 'KG' }).get('packageUnit')).toBeNull();
  });

  it('forwards the unit exactly as the form holds it', () => {
    // Normalization belongs to the server, which owns the accepted vocabulary. A
    // second spelling list here would be a second thing that can disagree.
    expect(sendUpload({ packageAmount: 1, packageUnit: 'kg' }).get('packageUnit')).toBe('kg');
  });
});
