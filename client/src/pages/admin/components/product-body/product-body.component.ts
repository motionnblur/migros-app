import { Component } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';

@Component({
  selector: 'app-product-body',
  imports: [],
  templateUrl: './product-body.component.html',
  styleUrl: './product-body.component.css',
})
export class ProductBodyComponent {
  constructor(private restService: RestService) {}

  ngOnInit() {
    this.restService
      .getAllAdminProducts(1, 0, 10)
      .subscribe((data: any) => console.log(data));
  }
}
