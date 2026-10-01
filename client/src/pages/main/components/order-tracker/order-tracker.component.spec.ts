import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { OrderTrackerComponent } from './order-tracker.component';

describe('OrderTrackerComponent', () => {
  let component: OrderTrackerComponent;
  let fixture: ComponentFixture<OrderTrackerComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [OrderTrackerComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    })
    .compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(OrderTrackerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock
      .expectOne((request) =>
        request.url.includes('/user/supply/getAllOrderIds'),
      )
      .flush([]);
  });

  afterEach(() => httpMock.verify());

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('warns that a shipped order cannot be cancelled', () => {
    component.openOrderTrackerComponent(1);
    httpMock.expectOne((request) =>
      request.url.includes('/user/supply/getOrderStatusByOrderId'),
    ).flush('Shipped');
    fixture.detectChanges();

    const warning = fixture.nativeElement.querySelector('[role="status"]');
    expect(warning.textContent).toContain('Yalnızca bekleyen siparişler');
    expect(fixture.nativeElement.querySelector('.btn-outline-danger')).toBeNull();

    component.cancelOrder();
    httpMock.expectNone((request) => request.url.includes('/cancelOrder'));
  });

  it('allows a pending order to be cancelled and explains a status race', () => {
    component.openOrderTrackerComponent(1);
    httpMock.expectOne((request) =>
      request.url.includes('/user/supply/getOrderStatusByOrderId'),
    ).flush('Pending');
    fixture.detectChanges();

    expect(component.getStatusClass('Ordered')).toBe('active');
    expect(fixture.nativeElement.querySelector('.btn-outline-danger')).not.toBeNull();

    component.cancelOrder();
    httpMock.expectOne((request) =>
      request.method === 'DELETE' && request.url.includes('/cancelOrder'),
    ).flush('Only pending orders can be canceled.', {
      status: 400,
      statusText: 'Bad Request',
    });
    httpMock.expectOne((request) =>
      request.url.includes('/user/supply/getOrderStatusByOrderId'),
    ).flush('Shipped');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="status"]').textContent)
      .toContain('Yalnızca bekleyen siparişler');
    expect(fixture.nativeElement.querySelector('.btn-outline-danger')).toBeNull();
  });
});
