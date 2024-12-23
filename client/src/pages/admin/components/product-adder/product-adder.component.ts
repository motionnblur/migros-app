import {
  Component,
  ElementRef,
  Input,
  SimpleChanges,
  ViewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RestService } from '../../../../services/rest/rest.service';
import { CommonModule } from '@angular/common';
import { IProductUploader } from '../../../../interfaces/IProductUploader';

@Component({
  selector: 'app-product-adder',
  imports: [CommonModule, FormsModule],
  templateUrl: './product-adder.component.html',
  styleUrl: './product-adder.component.css',
})
export class ProductAdderComponent {
  @Input() productName!: string;
  @Input() price!: number;
  @Input() count!: number;
  @Input() discount!: number;
  @Input() description!: string;
  @Input() selectedImage: File | null = null;

  @ViewChild('imageUploaderRef')
  imageUploaderRef!: ElementRef<HTMLInputElement>;
  @ViewChild('imageRef') imageRef!: ElementRef<HTMLImageElement>;
  @ViewChild('addImageRef') addImageRef!: ElementRef<HTMLDivElement>;

  imageUrl: string | null = null;

  constructor(private restService: RestService) {}

  updateView(image: File | null) {
    if (image) {
      this.imageUrl = URL.createObjectURL(image);
    } else {
      this.imageUrl = null;
    }
  }
  openImageUploader() {
    if (this.imageUploaderRef) {
      this.imageUploaderRef.nativeElement.click();
    }
  }
  onImageSelected(event: any) {
    if (event.target.files && event.target.files.length > 0) {
      this.selectedImage = event.target.files[0];
      this.updateView(this.selectedImage);
    }
  }
  uploadProductData() {
    const productData: IProductUploader = {
      productName: this.productName,
      price: this.price,
      count: this.count,
      discount: this.discount,
      description: this.description,
      selectedImage: this.selectedImage,
    };
    this.restService
      .uploadProductData(productData)
      .subscribe((status: boolean) => {
        console.log(status);
      });
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['selectedImage']) {
      this.updateView(changes['selectedImage'].currentValue);
    }
  }
}
