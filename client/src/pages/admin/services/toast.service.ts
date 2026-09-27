import { Injectable } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

/**
 * Thin wrapper around Angular Material's snackbar so admin flows can surface
 * success/error feedback without using native `alert()` dialogs.
 */
@Injectable({
  providedIn: 'root',
})
export class ToastService {
  constructor(private readonly snackBar: MatSnackBar) {}

  success(message: string): void {
    this.show(message, 'toast-success', 3000);
  }

  error(message: string): void {
    this.show(message, 'toast-error', 5000);
  }

  info(message: string): void {
    this.show(message, 'toast-info', 3000);
  }

  private show(message: string, panelClass: string, duration: number): void {
    this.snackBar.open(message, 'Kapat', {
      duration,
      horizontalPosition: 'right',
      verticalPosition: 'bottom',
      panelClass: [panelClass],
    });
  }
}
