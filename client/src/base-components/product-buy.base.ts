import { Directive, ElementRef, Input, ViewChild } from '@angular/core';
import { EventService } from '../services/event/event.service';
import { RestService } from '../services/rest/rest.service';
import { IProductData } from '../interfaces/IProductData';
import { IProductDescription } from '../interfaces/IProductDescription';
import { SafeHtml } from '@angular/platform-browser';

@Directive()
export abstract class ProductBuyBase {
  @Input() productId!: number;

  @ViewChild('product_image_ref')
  productImageRef!: ElementRef<HTMLImageElement>;
  @ViewChild('product_addbutton_ref')
  productAddButtonRef!: ElementRef<HTMLDivElement>;
  @ViewChild('product_money_ref')
  productMoneyRef!: ElementRef<HTMLDivElement>;

  productData!: IProductData;
  productDescriptions!: IProductDescription;
  currentProductDescriptionBody!: SafeHtml;
  currentTabRef: HTMLDivElement | null = null;

  protected restService: RestService;
  protected eventManager: EventService;
  constructor(restService: RestService, eventManager: EventService) {
    this.restService = restService;
    this.eventManager = eventManager;
  }

  ngOnInit() {
    this.restService
      .getProductData(this.productId)
      .subscribe((data: IProductData) => {
        this.productData = data;
      });
    this.restService
      .getProductDescription(this.productId)
      .subscribe((data: IProductDescription) => {
        this.productDescriptions = data;
        this.currentProductDescriptionBody =
          this.productDescriptions.descriptionList[0].descriptionTabContent;
        this.onProductDescritptionUpdate(data);
      });
    this.restService.getProductImage(this.productId).subscribe((data: Blob) => {
      this.productImageRef.nativeElement.src = URL.createObjectURL(data);
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
  }
  protected onProductDescritptionUpdate(data: IProductDescription) {
    console.log('onProductDescritptionUpdate');
  }
  protected changeTab(index: number, tabRef: HTMLDivElement) {
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
}
