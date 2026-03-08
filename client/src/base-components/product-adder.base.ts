import {
  Directive,
  ElementRef,
  EventEmitter,
  Input,
  Output,
  SimpleChanges,
  ViewChild,
} from '@angular/core';
import { FormControl } from '@angular/forms';
import { RestService } from '../services/rest/rest.service';
import { EventService } from '../services/event/event.service';
import { categories } from '../memory/global-data';

@Directive()
export abstract class ProductAdderBase {
  @Input() productName: string = '';
  @Input() subCategoryName: string = '';
  @Input() price: number = 0;
  @Input() count: number = 0;
  @Input() discount: number = 0;
  @Input() description: string = '';
  @Input() selectedImage: File | null = null;

  @Output() hasEscapePressed = new EventEmitter<boolean>();

  @ViewChild('imageUploaderRef')
  imageUploaderRef!: ElementRef<HTMLInputElement>;
  @ViewChild('imageRef') imageRef!: ElementRef<HTMLImageElement>;
  @ViewChild('addImageRef') addImageRef!: ElementRef<HTMLDivElement>;

  @Output() hasProductAdded = new EventEmitter<boolean>();

  selectedFormValue: number | null = null;
  protected imageUrl: string | null = null;
  protected categoryControl = new FormControl('');

  protected boundKeyDownEvent!: (event: KeyboardEvent) => void;

  public isUpdateMode: boolean = false;
  public validationError: string = '';

  public categories = categories;

  constructor(
    protected restService: RestService,
    protected eventManager: EventService
  ) {
    this.boundKeyDownEvent = this.keyDownEvent.bind(this);
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['selectedImage']) {
      this.updateView(changes['selectedImage'].currentValue);
    }
  }

  ngOnInit() {
    document.addEventListener('keydown', this.boundKeyDownEvent);
    this.categoryControl.valueChanges.subscribe((value) => {
      if (value !== null && value !== undefined && value !== '') {
        const parsedValue = Number(value);
        this.selectedFormValue = Number.isNaN(parsedValue) ? null : parsedValue;
      }
    });
  }

  ngOnDestroy() {
    document.removeEventListener('keydown', this.boundKeyDownEvent);
  }

  public outProductAdded() {
    this.hasProductAdded.emit(true);
  }

  public openImageUploader() {
    if (this.imageUploaderRef) {
      this.imageUploaderRef.nativeElement.click();
    }
  }

  public onImageSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.selectedImage = input.files[0];
      this.updateView(this.selectedImage);
      this.validationError = '';
    }
  }

  protected validateBeforeSubmit(requireImage: boolean): boolean {
    const normalizedProductName = this.productName.trim();
    const normalizedSubCategoryName = this.subCategoryName.trim();

    if (!normalizedProductName) {
      this.validationError = 'Product name is required.';
      return false;
    }

    if (!normalizedSubCategoryName) {
      this.validationError = 'Subcategory name is required.';
      return false;
    }

    if (this.selectedFormValue === null) {
      this.validationError = 'Category selection is required.';
      return false;
    }

    if (this.price < 0 || this.count < 0 || this.discount < 0 || this.discount > 100) {
      this.validationError = 'Price/count/discount values are invalid.';
      return false;
    }

    if (requireImage && !this.selectedImage) {
      this.validationError = 'Product image is required.';
      return false;
    }

    this.productName = normalizedProductName;
    this.subCategoryName = normalizedSubCategoryName;
    this.description = this.description.trim();
    this.validationError = '';
    return true;
  }

  private updateView(image: File | null) {
    if (image) {
      this.imageUrl = URL.createObjectURL(image);
    } else {
      this.imageUrl = null;
    }
  }

  protected keyDownEvent(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      this.hasEscapePressed.emit(true);
    }
  }

  openEditor?(): void {}
}

