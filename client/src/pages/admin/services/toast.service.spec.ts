import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { ToastService } from './toast.service';

describe('ToastService', () => {
  let service: ToastService;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  beforeEach(() => {
    snackBarSpy = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

    TestBed.configureTestingModule({
      providers: [
        provideNoopAnimations(),
        { provide: MatSnackBar, useValue: snackBarSpy },
      ],
    });

    service = TestBed.inject(ToastService);
  });

  it('should open a success snackbar', () => {
    service.success('done');
    expect(snackBarSpy.open).toHaveBeenCalledWith(
      'done',
      'Kapat',
      jasmine.objectContaining({ panelClass: ['toast-success'] })
    );
  });

  it('should open an error snackbar', () => {
    service.error('failed');
    expect(snackBarSpy.open).toHaveBeenCalledWith(
      'failed',
      'Kapat',
      jasmine.objectContaining({ panelClass: ['toast-error'] })
    );
  });
});
