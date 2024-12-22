import { Component, Input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RestService } from '../../../../services/rest/rest.service';

@Component({
  selector: 'app-product-adder',
  imports: [FormsModule],
  templateUrl: './product-adder.component.html',
  styleUrl: './product-adder.component.css',
})
export class ProductAdderComponent {
  @Input() productName!: string;
  @Input() price!: number;
  @Input() count!: number;
  @Input() discount!: number;
  @Input() description!: string;

  imageInputRef: HTMLElement | null = null;
  selectedImage: File | null = null;

  constructor(private restService: RestService) {}

  openImageUploader() {
    if (this.imageInputRef) {
      this.imageInputRef.click();
    }
  }
  onImageSelected(event: any) {
    this.selectedImage = event.target.files[0];
  }
  uploadImage() {
    this.restService.uploadImage(this.selectedImage!).subscribe();
  }

  ngOnInit() {
    this.imageInputRef = document.getElementById('imageUploader');
  }
}
