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
  productsData: IItemPreview[] = [];
  private productAddedCallback!: (data: any) => void;

  constructor(
    private restService: RestService,
    private eventService: EventService
  ) {
    this.productAddedCallback = () => {
      this.loadProducts();
    };
  }

  ngOnInit() {
    this.loadProducts();
    this.eventService.on('productAdded', this.productAddedCallback);
  }
  ngOnDestroy() {
    this.eventService.off('productAdded', this.productAddedCallback);
  }

  private loadProducts() {
    this.restService.getAllAdminProducts(1, 0, 10).subscribe((data: any) => {
      this.productsData = data;
    });
  }

  deleteProduct(itemId: number) {
    this.restService.deleteProduct(itemId).subscribe((status: boolean) => {
      if (status) {
        this.loadProducts();
      }
    });
  }
}
