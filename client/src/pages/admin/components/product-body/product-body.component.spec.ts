import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { PageEvent } from '@angular/material/paginator';

import { ProductBodyComponent } from './product-body.component';

describe('ProductBodyComponent', () => {
  let component: ProductBodyComponent;
  let fixture: ComponentFixture<ProductBodyComponent>;
  let httpMock: HttpTestingController;

  const flushInitialLoad = () => {
    httpMock
      .expectOne((req) => req.url.endsWith('/getProductsFromCategory'))
      .flush([]);
    httpMock
      .expectOne((req) => req.url.endsWith('/getProductCountsFromCategory'))
      .flush(0);
  };

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

    httpMock = TestBed.inject(HttpTestingController);

    fixture = TestBed.createComponent(ProductBodyComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    flushInitialLoad();
  });

  afterEach(() => httpMock.verify());

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should list Hepsi along with the real categories in the dropdown', () => {
    const labels = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.dropdown-item'),
    ).map((item) => item.textContent?.trim());

    expect(labels).toContain('Hepsi');
    expect(labels).toContain('Meyve, Sebze');
    expect(labels).toContain('Elektronik');
  });

  it('selecting Hepsi should request all products instead of a category', () => {
    component.onCategorySelected(0);

    httpMock.expectNone(
      (req) => req.url.endsWith('/getProductsFromCategory'),
    );

    const pageRequest = httpMock.expectOne(
      (req) => req.url.endsWith('/getAllProducts'),
    );
    expect(pageRequest.request.method).toBe('GET');
    expect(pageRequest.request.params.get('page')).toBe('0');
    expect(pageRequest.request.params.get('productRange')).toBe('5');
    pageRequest.flush([{ productId: 1, productName: 'Laptop' }]);

    const countRequest = httpMock.expectOne(
      (req) => req.url.endsWith('/getAllProductCounts'),
    );
    countRequest.flush(7);

    expect(component.selectedCategoryName).toBe('Hepsi');
    expect(component.productPageLength).toBe(7);
    expect(component.productsData.length).toBe(1);
  });

  it('selecting a real category should keep using the per-category endpoints', () => {
    component.onCategorySelected(6);

    httpMock.expectNone((req) => req.url.endsWith('/getAllProducts'));

    const pageRequest = httpMock.expectOne(
      (req) => req.url.endsWith('/getProductsFromCategory'),
    );
    expect(pageRequest.request.params.get('categoryId')).toBe('6');
    pageRequest.flush([]);

    const countRequest = httpMock.expectOne(
      (req) => req.url.endsWith('/getProductCountsFromCategory'),
    );
    expect(countRequest.request.params.get('categoryId')).toBe('6');
    countRequest.flush(3);

    expect(component.selectedCategoryName).toBe('İçecek');
  });

  it('pageEvent while Hepsi is selected should page through all products', () => {
    component.onCategorySelected(0);
    httpMock
      .expectOne((req) => req.url.endsWith('/getAllProducts'))
      .flush([]);
    httpMock
      .expectOne((req) => req.url.endsWith('/getAllProductCounts'))
      .flush(7);

    component.pageEvent({
      pageIndex: 2,
      pageSize: 10,
      length: 7,
    } as PageEvent);

    httpMock.expectNone(
      (req) => req.url.endsWith('/getProductsFromCategory'),
    );

    const pageRequest = httpMock.expectOne(
      (req) => req.url.endsWith('/getAllProducts'),
    );
    expect(pageRequest.request.params.get('page')).toBe('2');
    expect(pageRequest.request.params.get('productRange')).toBe('10');
    pageRequest.flush([]);
  });
});
