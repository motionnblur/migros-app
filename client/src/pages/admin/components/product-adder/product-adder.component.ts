import {
  Component,
  ElementRef,
  EventEmitter,
  Input,
  Output,
  SimpleChanges,
  ViewChild,
} from '@angular/core';
import {
  FormControl,
  FormGroup,
  FormsModule,
  ReactiveFormsModule,
} from '@angular/forms';
import { RestService } from '../../../../services/rest/rest.service';
import { CommonModule } from '@angular/common';
import { IProductUploader } from '../../../../interfaces/IProductUploader';
import { EventService } from '../../../../services/event/event.service';

@Component({
  selector: 'app-product-adder',
  imports: [CommonModule, FormsModule, ReactiveFormsModule],
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

  @Output() hasProductAdded = new EventEmitter<boolean>();

  outProductAdded() {
    this.hasProductAdded.emit(true);
  }

  imageUrl: string | null = null;

  categories = [
    { value: 1, name: 'Yılbaşı' },
    { value: 2, name: 'Meyve, Sebze' },
    { value: 3, name: 'Süt, Kahvaltılık' },
    { value: 4, name: 'Temel Gıda' },
    { value: 5, name: 'Meze, Hazır yemek, Donut' },
    { value: 6, name: 'İçecek' },
    { value: 7, name: 'Dondurma' },
    { value: 8, name: 'Atistirmalik' },
    { value: 9, name: 'Fırın, Pastane' },
    { value: 10, name: 'Deterjan, Temizlik' },
    { value: 11, name: 'Kağıt, Islak mendil' },
    { value: 12, name: 'Kişisel Bakım,Kozmetik, Sağlık' },
    { value: 13, name: 'Bebek' },
    { value: 14, name: 'Ev, Yaşam' },
    { value: 15, name: 'Kitap, Kırtasiye, Oyuncak' },
    { value: 16, name: 'Çiçek' },
    { value: 17, name: 'Pet Shop' },
    { value: 18, name: 'Elektronik' },
  ];
  selectedFormValue: any = null;

  categoryControl = new FormControl('');

  constructor(
    private restService: RestService,
    private eventManager: EventService
  ) {}

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
      adminId: 1,
      productName: this.productName,
      price: this.price,
      count: this.count,
      discount: this.discount,
      description: this.description,
      selectedImage: this.selectedImage,
      categoryValue: this.selectedFormValue,
    };
    this.restService
      .uploadProductData(productData)
      .subscribe((status: boolean) => {
        if (status) {
          this.outProductAdded();
          this.eventManager.trigger('productAdded');
        }
      });
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['selectedImage']) {
      this.updateView(changes['selectedImage'].currentValue);
    }
  }

  ngOnInit() {
    this.categoryControl.valueChanges.subscribe((value) => {
      //console.log('Selected value:', value);
      if (value !== null && value !== undefined) {
        this.selectedFormValue = value;
      }
    });
  }
}
