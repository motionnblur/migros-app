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
});
