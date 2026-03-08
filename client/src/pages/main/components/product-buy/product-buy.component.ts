import { EventService } from '../../../../services/event/event.service';
import { DomSanitizer } from '@angular/platform-browser';
import { ProductBuyBase } from '../../../../base-components/product-buy.base';
import { RestService } from '../../../../services/rest/rest.service';
import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { AuthService } from '../../../../services/auth/auth.service';
import { ActivatedRoute, Router } from '@angular/router';

@Component({
  selector: 'app-product-buy',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './product-buy.component.html',
  styleUrl: './product-buy.component.css',
})
export class ProductBuyComponent extends ProductBuyBase {
  public selectedTabIndex: number = 0;

  public get discountedPrice(): number {
    if (!this.productData) return 0;

    const price = this.productData.productPrice ?? 0;
    const discount = this.productData.productDiscount ?? 0;

    if (discount <= 0) {
      return price;
    }

    return +(price - (price * discount) / 100).toFixed(2);
  }

  public get isOutOfStock(): boolean {
    return (this.productData?.productCount ?? 0) <= 0;
  }

  constructor(
    protected override restService: RestService,
    protected sanitizer: DomSanitizer,
    protected override eventManager: EventService,
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute
  ) {
    super(restService, eventManager);
  }

  public onTabClick(index: number) {
    this.selectedTabIndex = index;
    const content =
      this.productDescriptions.descriptionList[index].descriptionTabContent;
    this.updateProductDescriptionBody(content);
  }

  private updateProductDescriptionBody(description: string) {
    this.currentProductDescriptionBody =
      this.sanitizer.bypassSecurityTrustHtml(description);
  }

  public addProductToUserCart() {
    if (this.isOutOfStock) {
      alert('Bu urun stokta kalmadi.');
      return;
    }

    if (!this.authService.isLoggedIn()) {
      this.router.navigate([{ outlets: { modal: ['login'] } }], {
        relativeTo: this.route,
      });
      return;
    }

    this.restService.addProductToUserCart(this.productId).subscribe({
      next: () => {},
      error: (error: any) => {
        const message = error?.error || 'Urun sepete eklenemedi.';
        alert(message);
      },
      complete: () => alert('Urun sepete eklendi!'),
    });
  }
}
