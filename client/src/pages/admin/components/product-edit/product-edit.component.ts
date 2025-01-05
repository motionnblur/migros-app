import {
  Component,
  ElementRef,
  EventEmitter,
  Input,
  Output,
  SimpleChanges,
  ViewChild,
} from '@angular/core';
import { ProductBuyBase } from '../../../../base-components/product-buy.base';
import { CommonModule } from '@angular/common';
import { RestService } from '../../../../services/rest/rest.service';
import { EventService } from '../../../../services/event/event.service';
import { IProductDescription } from '../../../../interfaces/IProductDescription';

@Component({
  selector: 'app-product-edit',
  imports: [CommonModule],
  templateUrl: './product-edit.component.html',
  styleUrl: './product-edit.component.css',
})
export class ProductEditComponent extends ProductBuyBase {
  @Input() selectedImage: File | null = null;
  @Output() hasEscapePressed = new EventEmitter<boolean>();
  @ViewChild('imageUploaderRef')
  imageUploaderRef!: ElementRef<HTMLInputElement>;
  currentSelectedTabIndis: number = 0;

  private boundKeyDownEvent!: (event: KeyboardEvent) => void;
  imageUrl: string | null = null;
  isEditing = false;

  constructor(
    protected override restService: RestService,
    protected override eventManager: EventService
  ) {
    super(restService, eventManager);
    this.boundKeyDownEvent = this.keyDownEvent.bind(this);
  }
  override ngOnInit() {
    super.ngOnInit();
    document.addEventListener('keydown', this.boundKeyDownEvent);
  }
  ngOnChanges(changes: SimpleChanges) {
    if (changes['selectedImage']) {
      this.updateView(changes['selectedImage'].currentValue);
    }
  }
  ngOnDestroy() {
    document.removeEventListener('keydown', this.boundKeyDownEvent);
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

  keyDownEvent(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      this.hasEscapePressed.emit(true);
    }
  }
  updateView(image: File | null) {
    if (image) {
      this.imageUrl = URL.createObjectURL(image);
    } else {
      this.imageUrl = null;
    }
  }

  makeEditable() {
    this.isEditing = true;
  }

  protected override changeTab(index: number, tabRef: HTMLDivElement): void {
    super.changeTab(index, tabRef);
    this.currentSelectedTabIndis = index;
  }

  changeTabName(event: any, index: number) {
    this.productDescriptions[index].descriptionTabName = event.target.innerText;
  }
  changeTabBody(event: any) {
    this.currentProductDescriptionBody = event.target.innerHTML;

    this.productDescriptions[
      this.currentSelectedTabIndis
    ].descriptionTabContent = event.target.innerHTML;
  }
  createProductTab() {
    const newTab: IProductDescription = {
      productId: this.productId,
      descriptionTabName: 'Yeni Tab',
      descriptionTabContent: '',
    };
    this.productDescriptions.push(newTab);
    //this.currentProductDescriptionBody = '';
  }
  deleteProductTab(event: any, index: number) {
    event.stopPropagation();
    this.productDescriptions[index].descriptionTabContent = '';
    this.productDescriptions.splice(index, 1);

    if (this.productDescriptions.length > 0) {
      this.changeTab(0, this.currentTabRef!);
    } else {
      this.currentProductDescriptionBody = '';
      this.currentTabRef = null;
      this.currentSelectedTabIndis = 0;
    }
  }
}
