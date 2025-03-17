import { Component } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { CommonModule } from '@angular/common';
import { DomSanitizer } from '@angular/platform-browser';
import { ProductBuyBase } from '../../../../base-components/product-buy.base';
import { EventService } from '../../../../services/event/event.service';

@Component({
  selector: 'app-product-buy',
  imports: [CommonModule],
  templateUrl: './product-buy.component.html',
  styleUrl: './product-buy.component.css',
})
export class ProductBuyComponent extends ProductBuyBase {
  constructor(
    protected override restService: RestService,
    protected sanitizer: DomSanitizer,
    protected override eventManager: EventService // Add the eventManager parameter
  ) {
    super(restService, eventManager); // Call the base class constructor
  }

  override changeTab(index: number, tabRef: HTMLDivElement) {
    this.updateProductDescriptionBody(
      this.productDescriptions.descriptionList[index].descriptionTabContent
    );
    this.currentProductDescriptionBody =
      this.productDescriptions.descriptionList[index].descriptionTabContent;

    if (this.currentTabRef) {
      if (this.currentTabRef !== tabRef) {
        tabRef.style.color = 'orange';
        this.currentTabRef.style.color = '#696969'; // Set previous tab to default color

        (tabRef.children[1] as HTMLElement).style.display = 'block';
        (this.currentTabRef.children[1] as HTMLElement).style.display = 'none';

        this.currentTabRef = tabRef;
      }
    } else {
      this.currentTabRef = tabRef;
    }
  }

  private updateProductDescriptionBody(description: string) {
    this.currentProductDescriptionBody =
      this.sanitizer.bypassSecurityTrustHtml(description);
  }
  public addProductToUserCart() {
    this.restService.addProductToUserCart(this.productId).subscribe({
      next: () => {
        // // Emit the event
      },
      error: (error) => {
        console.error('Error adding product to user cart');
      },
      complete: () => {
        alert('Product added to cart');
      },
    });
  }
}
