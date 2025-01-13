import {
  Component,
  ElementRef,
  EventEmitter,
  Output,
  ViewChild,
} from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { data } from '../../../../memory/global-data';

@Component({
  selector: 'app-product-page-switcher',
  imports: [],
  templateUrl: './product-page-switcher.component.html',
  styleUrl: './product-page-switcher.component.css',
})
export class ProductPageSwitcherComponent {
  @Output() changePageEvent = new EventEmitter<number>();

  @ViewChild('switch_1') switch_1!: ElementRef<HTMLDivElement>;
  @ViewChild('switch_2') switch_2!: ElementRef<HTMLDivElement>;
  @ViewChild('switch_3') switch_3!: ElementRef<HTMLDivElement>;

  public currentPage: number = 1;
  public increaseFactor: number = 0;
  public selectedButonIndex: number = 0;
  private currentPageNumber: number = 1;
  private productCounts: number = 0;
  private pageCount: number = 1;

  constructor(private restService: RestService) {}

  ngOnInit(): void {
    this.restService
      .getProductCountsFromCategory(data.currentSelectedCategoryId)
      .subscribe({
        next: (data: any) => {
          this.productCounts = data;
          this.pageCount = Math.ceil(this.productCounts / 10);
          console.log(this.pageCount);
        },
        error: (error: any) => {
          console.error(error);
        },
        complete: () => {
          console.log('completed');
        },
      });
  }

  public changePage(buttonIndex: number, pageNumber: number) {
    if (this.pageCount < pageNumber) return;

    switch (buttonIndex) {
      case 0:
        if (buttonIndex === this.selectedButonIndex) return;

        if (this.increaseFactor > 0) {
          this.increaseFactor--;
          if (this.currentPageNumber > 1) this.currentPageNumber--;

          this.changePageEvent.emit(this.currentPageNumber);
          return;
        }
        this.selectedButonIndex = 0;

        this.switch_1.nativeElement.style.backgroundColor = '#ff7f00';
        this.switch_2.nativeElement.style.backgroundColor = '#f1f2f5';
        this.switch_3.nativeElement.style.backgroundColor = '#f1f2f5';

        break;
      case 1:
        if (buttonIndex === this.selectedButonIndex) return;

        this.selectedButonIndex = 1;

        this.switch_2.nativeElement.style.backgroundColor = '#ff7f00';
        this.switch_1.nativeElement.style.backgroundColor = '#f1f2f5';
        this.switch_3.nativeElement.style.backgroundColor = '#f1f2f5';

        break;
      case 2:
        this.increaseFactor++;
        this.selectedButonIndex = 2;

        this.switch_2.nativeElement.style.backgroundColor = '#ff7f00';
        this.switch_1.nativeElement.style.backgroundColor = '#f1f2f5';

        this.currentPageNumber++;
        this.switch_3.nativeElement.style.backgroundColor = '#f1f2f5';

        break;
    }
    this.currentPageNumber = pageNumber;

    this.changePageEvent.emit(this.currentPageNumber);
  }
  public switchLeft() {
    if (this.currentPageNumber === 1) return;

    if (this.increaseFactor === 1) {
      this.increaseFactor--;
      this.switch_2.nativeElement.style.backgroundColor = '#ff7f00';
      this.switch_1.nativeElement.style.backgroundColor = '#f1f2f5';
      this.switch_3.nativeElement.style.backgroundColor = '#f1f2f5';
    } else if (this.increaseFactor === 0) {
      this.selectedButonIndex = 0;

      this.switch_1.nativeElement.style.backgroundColor = '#ff7f00';
      this.switch_2.nativeElement.style.backgroundColor = '#f1f2f5';
      this.switch_3.nativeElement.style.backgroundColor = '#f1f2f5';

      if (this.currentPageNumber > 1) this.currentPageNumber--;
    } else {
      this.increaseFactor--;
    }
    if (this.currentPageNumber > 1) {
      this.currentPageNumber--;
      this.changePageEvent.emit(this.currentPageNumber);
    } else if (this.currentPageNumber === 1) {
      this.changePageEvent.emit(this.currentPageNumber);
    }
  }

  public switchRight() {
    if (this.currentPageNumber + this.increaseFactor >= this.pageCount) return;

    if (this.selectedButonIndex === 0) {
      this.selectedButonIndex = 1;

      this.switch_2.nativeElement.style.backgroundColor = '#ff7f00';
      this.switch_1.nativeElement.style.backgroundColor = '#f1f2f5';
      this.switch_3.nativeElement.style.backgroundColor = '#f1f2f5';
    } else {
      this.increaseFactor++;
    }
    this.currentPageNumber++;

    this.changePageEvent.emit(this.currentPageNumber);
  }
}
