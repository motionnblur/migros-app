import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { OrderPanelComponent } from './order-panel.component';

describe('OrderPanelComponent', () => {
  let component: OrderPanelComponent;
  let fixture: ComponentFixture<OrderPanelComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [OrderPanelComponent],
      providers: [
        provideNoopAnimations(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    })
    .compileComponents();

    fixture = TestBed.createComponent(OrderPanelComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
