import { Directive, Input } from '@angular/core';
import { EventService } from '../services/event/event.service';
import { RestService } from '../services/rest/rest.service';
import { IProductData } from '../interfaces/IProductData';
import { IProductDescription } from '../interfaces/IProductDescription';
import { SafeHtml } from '@angular/platform-browser';

@Directive()
export abstract class ProductBuyBase {
  @Input() productId!: number;
  productData!: IProductData;
  productDescriptions: IProductDescription[] = [];
  currentProductDescriptionBody!: SafeHtml;

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
      .subscribe((data: IProductDescription[]) => {
        this.productDescriptions = data;
        this.currentProductDescriptionBody =
          this.productDescriptions[0].descriptionTabContent;
      });
  }
}
