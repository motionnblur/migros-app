import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SignUserComponent } from './sign-user.component';

describe('SignUserComponent', () => {
  let component: SignUserComponent;
  let fixture: ComponentFixture<SignUserComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SignUserComponent]
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
