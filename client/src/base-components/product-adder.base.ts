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
import { ProductPackageUnit } from '../interfaces/IProductPackageMetadata';
import {
  PRODUCT_PACKAGE_UNIT_OPTIONS,
  ProductPackageFields,
  packageAmountStepFor,
  readPackageFields,
  validatePackageFields,
} from './product-package-fields';
import { Subscription } from 'rxjs';

@Directive()
export abstract class ProductAdderBase {
  @Input() productName: string = '';
  @Input() subCategoryName: string = '';
  @Input() price: number = 0;
  @Input() count: number = 0;
  @Input() discount: number = 0;
  @Input() description: string = '';
  @Input() selectedImage: File | null = null;
  /**
   * Optional package size, shared by the creation and the update form.
   *
   * `null` rather than a zero default, because zero is not "no size" and a
   * default of zero would send "0 G" for every product that has no package
   * metadata - which is most of the catalogue. Leaving both empty is how an
   * administrator says "this product has no package size", and that has to be a
   * value the backend accepts rather than a value it rejects.
   */
  @Input() packageAmount: number | null = null;
  @Input() packageUnit: ProductPackageUnit | '' = '';

  @Output() hasEscapePressed = new EventEmitter<boolean>();

  @ViewChild('imageUploaderRef')
  imageUploaderRef!: ElementRef<HTMLInputElement>;
  @ViewChild('imageRef') imageRef!: ElementRef<HTMLImageElement>;
  @ViewChild('addImageRef') addImageRef!: ElementRef<HTMLDivElement>;

  @Output() hasProductAdded = new EventEmitter<boolean>();

  selectedFormValue: number | null = null;
  protected imageUrl: string | null = null;
  private imageObjectUrl: string | null = null;
  protected categoryControl = new FormControl('');
  protected readonly requests = new Subscription();

  protected boundKeyDownEvent!: (event: KeyboardEvent) => void;

  public isUpdateMode: boolean = false;
  public validationError: string = '';

  public categories = categories;

  /**
   * The unit options the amount field can be read against.
   *
   * The same closed list the backend accepts, so a unit the form offers is a
   * unit the server will store rather than one it rejects after the save.
   */
  public readonly packageUnits: readonly ProductPackageUnit[] =
    PRODUCT_PACKAGE_UNIT_OPTIONS;

  /**
   * The step the amount input offers for the chosen unit.
   *
   * `ADET` counts discrete items, so the browser itself is never offered `1.5`
   * for it. That is a courtesy rather than the enforcement: the backend refuses
   * the value and `validateBeforeSubmit` says so before the save is attempted.
   */
  public get packageAmountStep(): number {
    return packageAmountStepFor(this.packageUnit);
  }

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
    this.requests.add(this.categoryControl.valueChanges.subscribe((value) => {
      if (value !== null && value !== undefined && value !== '') {
        const parsedValue = Number(value);
        this.selectedFormValue = Number.isNaN(parsedValue) ? null : parsedValue;
      }
    }));
  }

  ngOnDestroy() {
    document.removeEventListener('keydown', this.boundKeyDownEvent);
    this.requests.unsubscribe();
    this.releaseImageUrl();
  }

  public outProductAdded() {
    this.hasProductAdded.emit(true);
  }

  public openImageUploader() {
    if (this.imageUploaderRef) {
      this.imageUploaderRef.nativeElement.click();
    }
  }

  public cancel() {
    this.hasEscapePressed.emit(true);
  }

  public onImageSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.selectedImage = input.files[0];
      this.updateView(this.selectedImage);
      this.validationError = '';
    }
  }

  /**
   * Reads the two optional package fields as the pair the API expects.
   *
   * Delegates to the shared rules rather than re-deriving them, because the modal
   * editor is a separate component with its own save and its own fields: a second
   * implementation here is a second thing that can disagree about what a
   * half-filled pair means.
   */
  protected readPackageMetadata(): ProductPackageFields {
    return readPackageFields(this.packageAmount, this.packageUnit);
  }

  /**
   * Rejects the states the backend would reject, with a message naming what to
   * fix.
   *
   * The backend remains the authority - these rules are checked there on every
   * path, including any client that is not this one - but repeating them here
   * means a half-filled pair or a fractional item count is caught while the
   * administrator is still looking at the field, instead of after a round trip
   * whose only content is an error.
   */
  protected validatePackageMetadata(): boolean {
    const error = validatePackageFields(this.packageAmount, this.packageUnit);
    if (error === null) {
      return true;
    }

    this.validationError = error;
    return false;
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

    if (!this.validatePackageMetadata()) {
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
    this.releaseImageUrl();
    if (image) {
      this.setImagePreview(image);
    } else {
      this.imageUrl = null;
    }
  }

  protected setImagePreview(image: Blob): void {
    this.releaseImageUrl();
    this.imageObjectUrl = URL.createObjectURL(image);
    this.imageUrl = this.imageObjectUrl;
  }

  private releaseImageUrl(): void {
    if (this.imageObjectUrl) {
      URL.revokeObjectURL(this.imageObjectUrl);
      this.imageObjectUrl = null;
    }
  }

  protected keyDownEvent(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      this.hasEscapePressed.emit(true);
    }
  }

  openEditor?(): void {}
}

