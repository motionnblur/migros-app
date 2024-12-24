import { Component } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { IItemPreview } from '../../../../interfaces/IItemPreview';
import { CommonModule } from '@angular/common';
import { EventService } from '../../../../services/event/event.service';

@Component({
  selector: 'app-product-body',
  imports: [CommonModule],
  templateUrl: './product-body.component.html',
  styleUrl: './product-body.component.css',
})
export class ProductBodyComponent {
  constructor(
    private restService: RestService,
    private eventManager: EventService
  ) {}

  productsData: IItemPreview[] = [];

  ngOnInit() {
    this.restService.getAllAdminProducts(1, 0, 10).subscribe((data: any) => {
      this.productsData = data;
    });

    this.eventManager.on('productAdded', () => {
      this.restService.getAllAdminProducts(1, 0, 10).subscribe((data: any) => {
        this.productsData = data;
      });
    });
  }
}
