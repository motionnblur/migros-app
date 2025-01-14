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
  @Output() changePageToFirstEvent = new EventEmitter<void>();
  @Output() changePageToLastEvent = new EventEmitter<number>();

  @ViewChild('switch_1') switch_1!: ElementRef<HTMLDivElement>;
  @ViewChild('switch_2') switch_2!: ElementRef<HTMLDivElement>;
  @ViewChild('switch_3') switch_3!: ElementRef<HTMLDivElement>;

  public buttons: any = [];
  public currentPage: number = 1;

  private currentPageNumber: number = 1;
  private productCounts: number = 0;
  private pageCount: number = 1;

  constructor(private restService: RestService) {
    this.buttons.push(
      { index: 0, pageNumber: 1, isSelected: true },
      { index: 1, pageNumber: 2, isSelected: false },
      { index: 2, pageNumber: 3, isSelected: false }
    );
  }

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

  public changePage(button: any) {
    if (button.isSelected) return;
    if (this.pageCount < button.pageNumber) return;

    this.currentPageNumber = button.pageNumber;
    this.changePageEvent.emit(button.pageNumber);

    this.buttons.forEach((button: any) => {
      button.isSelected = false;
    });
    button.isSelected = true;

    this.setButtonColorAsSelected(button.index);
  }
  public switchLeft() {
    if (this.currentPageNumber === 1) return;

    this.currentPageNumber--;

    this.buttons.forEach((button: any) => {
      button.isSelected = false;
    });
    this.buttons[this.currentPageNumber - 1].isSelected = true;

    this.changePageEvent.emit(this.currentPageNumber);
    this.setButtonColorAsSelected(
      this.buttons[this.currentPageNumber - 1].index
    );
  }

  public switchRight() {
    if (this.currentPageNumber === this.pageCount) return;

    this.currentPageNumber++;

    this.buttons.forEach((button: any) => {
      button.isSelected = false;
    });
    this.buttons[this.currentPageNumber - 1].isSelected = true;

    this.changePageEvent.emit(this.currentPageNumber);
    this.setButtonColorAsSelected(
      this.buttons[this.currentPageNumber - 1].index
    );
  }

  public switchFirst() {
    if (this.currentPageNumber === 1) return;

    this.changePageToFirstEvent.emit();
    this.currentPageNumber = 1;

    this.buttons.forEach((button: any) => {
      button.isSelected = false;
    });
    this.buttons[0].isSelected = true;

    this.setButtonColorAsSelected(this.buttons[0].index);
  }
  public switchLast() {
    if (this.currentPageNumber === this.pageCount) return;

    this.changePageToLastEvent.emit(this.pageCount);
    this.currentPageNumber = this.pageCount;

    this.buttons.forEach((button: any) => {
      button.isSelected = false;
    });
    this.buttons[this.maxIndexThirdButtonCanHave()].isSelected = true;

    this.setButtonColorAsSelected(
      this.buttons[this.maxIndexThirdButtonCanHave()].index
    );
  }

  private setButtonColorAsSelected(buttonIndex: number) {
    switch (buttonIndex) {
      case 0:
        this.switch_1.nativeElement.style.backgroundColor = '#ff7f00';
        this.switch_2.nativeElement.style.backgroundColor = '#f1f2f5';
        this.switch_3.nativeElement.style.backgroundColor = '#f1f2f5';
        break;
      case 1:
        this.switch_2.nativeElement.style.backgroundColor = '#ff7f00';
        this.switch_1.nativeElement.style.backgroundColor = '#f1f2f5';
        this.switch_3.nativeElement.style.backgroundColor = '#f1f2f5';
        break;
      case 2:
        this.switch_2.nativeElement.style.backgroundColor = '#f1f2f5';
        this.switch_1.nativeElement.style.backgroundColor = '#f1f2f5';
        this.switch_3.nativeElement.style.backgroundColor = '#ff7f00';
        break;
    }
  }
  private maxIndexThirdButtonCanHave(): number {
    return this.pageCount - 1;
  }
}
