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
import { IDescription } from '../../../../interfaces/IDescription';
import { IProductDescriptionTab } from '../../../../interfaces/IProductDescriptionTab';
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
  productDescriptionTabs: IProductDescriptionTab[] = [];
  productDescriptionTabsToDelete: number[] = [];
  localProductDescriptions: IProductDescription | null = null;

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

  protected override onProductDescritptionUpdate(
    data: IProductDescription
  ): void {
    if (this.localProductDescriptions === null) {
      this.localProductDescriptions = JSON.parse(JSON.stringify(data));
    }

    var tabIndex: number = 0;
    data.descriptionList.forEach((description: IDescription) => {
      const tab: IProductDescriptionTab = {
        tabIndex: tabIndex++,
        description: description,
      };
      this.productDescriptionTabs.push(tab);
    });
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

  protected override changeTab(index: number, tabRef: HTMLDivElement): void {
    super.changeTab(index, tabRef);
    this.currentSelectedTabIndis = index;
  }

  changeTabName(event: any, index: number) {
    this.productDescriptions.descriptionList[index].descriptionTabName =
      event.target.innerText;
  }
  changeProductDescription(event: any) {
    this.productData.productDescription = event.target.innerText;
  }
  changeTabBody(event: any) {
    this.currentProductDescriptionBody = event.target.innerHTML;

    this.productDescriptions.descriptionList[
      this.currentSelectedTabIndis
    ].descriptionTabContent = event.target.innerHTML;
  }
  createProductTab() {
    const newTab: IDescription = {
      descriptionId: 0,
      descriptionTabName: 'Test tab',
      descriptionTabContent: 'Test body',
    };
    if (this.productDescriptions === undefined) {
      this.productDescriptions = {
        productId: 0,
        descriptionList: [],
      };
    }
    if (this.productDescriptions.descriptionList === null) {
      this.productDescriptions.descriptionList = [];
    }
    this.productDescriptions.descriptionList.push(newTab);
    //this.currentProductDescriptionBody = '';
  }
  deleteProductTab(event: any, index: number) {
    event.stopPropagation();

    if (this.productDescriptionTabs[index] === undefined) {
      this.productDescriptions.descriptionList.splice(index, 1);
      this.currentProductDescriptionBody = '';
      return;
    }

    this.productDescriptionTabsToDelete.push(
      this.productDescriptionTabs[index].description.descriptionId
    );

    this.productDescriptions.descriptionList[index].descriptionTabContent = '';
    this.productDescriptions.descriptionList.splice(index, 1);

    if (this.productDescriptions.descriptionList.length > 0) {
      this.changeTab(0, this.currentTabRef!);
    } else {
      this.currentProductDescriptionBody = '';
      this.currentTabRef = null;
      this.currentSelectedTabIndis = 0;
    }
  }
  updateButtonClick() {
    this.productDescriptions.productId = this.productId;

    if (this.productDescriptionTabsToDelete.length > 0) {
      this.productDescriptionTabsToDelete.forEach((descriptionId) => {
        this.restService
          .deleteProductDescription(descriptionId)
          .subscribe((status: boolean) => {
            if (status) {
              console.log(status);
            }
          });
      });
      this.productDescriptionTabsToDelete = [];
    }

    if (
      JSON.stringify(this.localProductDescriptions) !==
      JSON.stringify(this.productDescriptions)
    ) {
      this.restService
        .addProductDescription(this.productDescriptions)
        .subscribe((status: boolean) => {
          if (status) {
            //this.productDescriptions = this.localProductDescriptions!;
            this.localProductDescriptions = JSON.parse(
              JSON.stringify(this.productDescriptions)
            );
            console.log(status);
          }
        });
    }
  }
}
