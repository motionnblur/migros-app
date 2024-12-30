import { Component, OnInit } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { ProductPreviewComponent } from '../product-preview/product-preview.component';
import { NgFor } from '@angular/common';
import { IProductPreview } from '../../../../interfaces/IProductPreview';
import { data } from '../../../../memory/global-data';

@Component({
  selector: 'app-product-page',
  imports: [ProductPreviewComponent, NgFor],
  templateUrl: './product-page.component.html',
  styleUrl: './product-page.component.css',
})
export class ProductPageComponent implements OnInit {
  items: IProductPreview[] = [];

  constructor(public restService: RestService) {}
  ngOnInit(): void {
    this.restService
      .getProductPageData(data.currentSelectedCategoryId, 0, 10)
      .subscribe((data: any) => {
        data.forEach((productData: IProductPreview) => {
          this.items.push(productData);
        });
      });
  }
}
