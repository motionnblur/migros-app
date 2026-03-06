// ... imports stay the same

import {EventService} from '../../../../services/event/event.service';
import {DomSanitizer} from '@angular/platform-browser';
import {ProductBuyBase} from '../../../../base-components/product-buy.base';
import {RestService} from '../../../../services/rest/rest.service';
import {CommonModule} from '@angular/common';
import {Component} from '@angular/core';

@Component({
  selector: 'app-product-buy',
  standalone: true, // <--- ADD THIS LINE
  imports: [CommonModule], // Ensure CommonModule is here if you use *ngIf or *ngFor
  templateUrl: './product-buy.component.html',
  styleUrl: './product-buy.component.css',
})
export class ProductBuyComponent extends ProductBuyBase {
  public selectedTabIndex: number = 0; // Track which tab is active

  public get discountedPrice(): number {
    if (!this.productData) return 0;

    const price = this.productData.productPrice ?? 0;
    const discount = this.productData.productDiscount ?? 0;

    if (discount <= 0) {
      return price;
    }

    return +(price - (price * discount) / 100).toFixed(2);
  }

  constructor(
    protected override restService: RestService,
    protected sanitizer: DomSanitizer,
    protected override eventManager: EventService
  ) {
    super(restService, eventManager);
  }

  // Simplified Tab Switching Logic
  public onTabClick(index: number) {
    this.selectedTabIndex = index;
    const content = this.productDescriptions.descriptionList[index].descriptionTabContent;
    this.updateProductDescriptionBody(content);
  }

  private updateProductDescriptionBody(description: string) {
    this.currentProductDescriptionBody = this.sanitizer.bypassSecurityTrustHtml(description);
  }

  public addProductToUserCart() {
    this.restService.addProductToUserCart(this.productId).subscribe({
      next: () => {
        // You could emit a global event here to refresh the cart count in the header
      },
      error: (error) => console.error('Error adding product', error),
      complete: () => alert('Ürün sepete eklendi!'),
    });
  }
}
