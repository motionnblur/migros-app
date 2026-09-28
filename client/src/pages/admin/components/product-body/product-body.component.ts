import {Component, EventEmitter, Output, OnInit, OnDestroy} from '@angular/core';
import {RestService} from '../../../../services/rest/rest.service';
import {CommonModule} from '@angular/common';
import {EventService} from '../../../../services/event/event.service';
import {IAdminProductPreview} from '../../../../interfaces/IAdminProductPreview';
import {categories as productCategories} from '../../../../memory/global-data';
import {MatPaginatorModule, PageEvent} from '@angular/material/paginator';
import {MatDialog} from '@angular/material/dialog';
import {ConfirmDialogComponent} from '../confirm-dialog/confirm-dialog.component';
import {ToastService} from '../../services/toast.service';

@Component({
  selector: 'app-product-body',
  standalone: true,
  imports: [CommonModule, MatPaginatorModule],
  templateUrl: './product-body.component.html',
  styleUrl: './product-body.component.css',
})
export class ProductBodyComponent implements OnInit, OnDestroy {
  @Output() hasBodyClicked = new EventEmitter();
  @Output() hasAddProductButtonClicked = new EventEmitter();

  productsData: IAdminProductPreview[] = [];
  readonly allCategory = { value: 0, name: 'Hepsi' };
  categories = [this.allCategory, ...productCategories];
  selectedCategoryName: string = 'Lütfen bir kategori seçin';
  productPageLength: number = 0;
  currentPageSize: number = 5;
  currentPageNumber: number = 0;
  isDropdownOpen = false;

  private selectedCategoryId = 1;
  private showAll = false;

  // Callback reference for the event service
  private productAddedCallback = () => this.loadProducts();

  constructor(
    private restService: RestService,
    private eventService: EventService,
    private dialog: MatDialog,
    private toastService: ToastService
  ) {
  }

  ngOnInit() {
    this.loadProducts();
    this.eventService.on('productAdded', this.productAddedCallback);
  }

  ngOnDestroy() {
    this.eventService.off('productAdded', this.productAddedCallback);
  }

  private loadProducts() {
    this.fetchPageData(0, this.currentPageSize);
    this.fetchProductCount();
  }

  private fetchPageData(page: number, size: number) {
    const request = this.showAll
      ? this.restService.getAllProductsAdmin(page, size)
      : this.restService.getProductPageDataAdmin(this.selectedCategoryId, page, size);
    request.subscribe({
      next: (res: any) => {
        this.productsData = res;
        this.selectedCategoryName = this.resolveSelectedCategoryName();
      },
      error: (err) => console.error('Error loading products', err)
    });
  }

  private fetchProductCount() {
    const request = this.showAll
      ? this.restService.getAllProductCountsAdmin()
      : this.restService.getProductCountsFromCategoryAdmin(this.selectedCategoryId);
    request.subscribe({
      next: (count: any) => this.productPageLength = count
    });
  }

  private resolveSelectedCategoryName(): string {
    if (this.showAll) {
      return this.allCategory.name;
    }
    const category = this.categories.find(c => c.value === this.selectedCategoryId);
    return category ? category.name : 'Bilinmeyen Kategori';
  }

  public onCategorySelected(value: number) {
    if (value === this.allCategory.value) {
      this.showAll = true;
    } else {
      this.showAll = false;
      this.selectedCategoryId = value;
    }
    this.currentPageNumber = 0;
    this.fetchPageData(0, this.currentPageSize);
    this.fetchProductCount();
  }

  public onBodyClick(productId: number) {
    this.hasBodyClicked.emit();
    this.eventService.trigger('productChanged', productId);
    //this.eventService.trigger('editorOpened', productId);
  }

  public deleteProduct(productId: number) {
    this.dialog
      .open(ConfirmDialogComponent, {
        width: '420px',
        maxWidth: '92vw',
        autoFocus: false,
        panelClass: 'admin-confirm-dialog',
        data: {
          title: 'Ürünü sil',
          message: 'Bu ürünü silmek istediğinize emin misiniz?',
          confirmText: 'Sil',
          danger: true,
        },
      })
      .afterClosed()
      .subscribe((approved) => {
        if (!approved) {
          return;
        }

        this.restService.deleteProduct(productId).subscribe({
          complete: () => {
            this.productsData = this.productsData.filter(p => p.productId !== productId);
            this.productPageLength = Math.max(0, this.productPageLength - 1);
            this.toastService.success('Ürün silindi.');
          },
          error: () => {
            this.toastService.error('Ürün silinemedi.');
          }
        });
      });
  }

  pageEvent($event: PageEvent) {
    this.currentPageSize = $event.pageSize;
    this.currentPageNumber = $event.pageIndex;
    this.fetchPageData($event.pageIndex, $event.pageSize);
  }

  public openProductAdder() {
    this.hasAddProductButtonClicked.emit();
  }
}
