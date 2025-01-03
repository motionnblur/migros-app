import { Component, ElementRef, Input, ViewChild } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { IProductData } from '../../../../interfaces/IProductData';
import { CommonModule } from '@angular/common';
import { IProductDescription } from '../../../../interfaces/IProductDescription';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';

@Component({
  selector: 'app-product-buy',
  imports: [CommonModule],
  templateUrl: './product-buy.component.html',
  styleUrl: './product-buy.component.css',
})
export class ProductBuyComponent {
  @Input() productId!: number;
  @ViewChild('product_image_ref')
  productImageRef!: ElementRef<HTMLImageElement>;
  productData!: IProductData;
  productDescriptions: IProductDescription[] = [];
  currentProductDescriptionBody!: SafeHtml;
  currentTabRef: HTMLDivElement | null = null;

  constructor(
    protected restService: RestService,
    protected sanitizer: DomSanitizer
  ) {}

  ngOnInit() {
    this.restService
      .getProductData(this.productId)
      .subscribe((data: IProductData) => {
        this.productData = data;
      });
    this.restService
      .getProductDescription(this.productId)
      .subscribe((data: IProductDescription[]) => {
        this.productDescriptions = data;
        this.currentProductDescriptionBody =
          this.productDescriptions[0].descriptionTabContent;
      });
  }
  ngAfterViewInit() {
    setTimeout(() => {
      const firstTab: HTMLDivElement = document.querySelectorAll(
        '[data-tab-ref]'
      )[0] as HTMLDivElement;
      const firstLine: HTMLDivElement = document.querySelectorAll(
        '[data-line-ref]'
      )[0] as HTMLDivElement;
      if (firstTab) {
        firstTab.style.color = 'orange';
        this.currentTabRef = firstTab;
      }
      if (firstLine) {
        firstLine.style.display = 'block';
      }
    }, 50);

    this.restService.getProductImage(this.productId).subscribe((data: Blob) => {
      this.productImageRef.nativeElement.src = URL.createObjectURL(data);
    });
  }
  changeTab(index: number, tabRef: HTMLDivElement) {
    this.updateProductDescriptionBody(
      this.productDescriptions[index].descriptionTabContent
    );
    this.currentProductDescriptionBody =
      this.productDescriptions[index].descriptionTabContent;

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
}

export class ProductBuyEditableComponent extends ProductBuyComponent {
  constructor(
    protected override restService: RestService,
    protected override sanitizer: DomSanitizer
  ) {
    super(restService, sanitizer);
  }
}
