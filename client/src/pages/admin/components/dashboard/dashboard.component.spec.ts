import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { DashboardComponent } from './dashboard.component';
import { RestService } from '../../../../services/rest/rest.service';
import { categories } from '../../../../memory/global-data';

describe('DashboardComponent', () => {
  let component: DashboardComponent;
  let fixture: ComponentFixture<DashboardComponent>;
  let restServiceSpy: jasmine.SpyObj<RestService>;

  beforeEach(async () => {
    restServiceSpy = jasmine.createSpyObj<RestService>('RestService', [
      'getAllOrders',
      'getSupportUsersForAdmin',
      'getBannedSupportUsersForAdmin',
      'getProductCountsFromCategoryAdmin',
    ]);

    restServiceSpy.getAllOrders.and.returnValue(
      of({
        items: [{ orderId: 1, orderGroupId: 1, totalPrice: 10, status: 'Ordered' }],
        total: 7,
      })
    );
    restServiceSpy.getSupportUsersForAdmin.and.returnValue(of(['a@b.c']));
    restServiceSpy.getBannedSupportUsersForAdmin.and.returnValue(of(['x@y.z']));
    restServiceSpy.getProductCountsFromCategoryAdmin.and.returnValue(of(2));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: RestService, useValue: restServiceSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DashboardComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should derive stats from existing endpoints', () => {
    const orders = component.stats.find((s) => s.key === 'orders');
    const products = component.stats.find((s) => s.key === 'products');

    expect(orders?.value).toBe(7);
    expect(products?.value).toBe(2 * categories.length);
    expect(component.recentOrders.length).toBe(1);
  });
});
