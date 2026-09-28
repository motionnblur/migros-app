import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { SignService } from './sign.service';

describe('SignService', () => {
  let service: SignService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(SignService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
