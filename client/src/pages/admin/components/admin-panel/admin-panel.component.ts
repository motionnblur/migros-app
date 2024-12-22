import { Component } from '@angular/core';
import { ProductAdderComponent } from '../product-adder/product-adder.component';

@Component({
  selector: 'app-admin-panel',
  imports: [ProductAdderComponent],
  templateUrl: './admin-panel.component.html',
  styleUrl: './admin-panel.component.css',
})
export class AdminPanelComponent {}
