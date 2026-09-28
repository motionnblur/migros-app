import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { SignUserComponent } from './sign-user.component';

describe('SignUserComponent', () => {
  let component: SignUserComponent;
  let fixture: ComponentFixture<SignUserComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SignUserComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    })
    .compileComponents();

    fixture = TestBed.createComponent(SignUserComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
