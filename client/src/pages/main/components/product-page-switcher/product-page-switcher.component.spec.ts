import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ProductPageSwitcherComponent } from './product-page-switcher.component';

describe('ProductPageSwitcherComponent', () => {
  let component: ProductPageSwitcherComponent;
  let fixture: ComponentFixture<ProductPageSwitcherComponent>;
  let emitted: number[];

  function setInputs(currentPage: number, pageCount: number): void {
    component.currentPage = currentPage;
    component.pageCount = pageCount;
    component.ngOnChanges();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProductPageSwitcherComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductPageSwitcherComponent);
    component = fixture.componentInstance;
    emitted = [];
    component.pageChange.subscribe((page) => emitted.push(page));
    setInputs(1, 1);
  });

  it('creates', () => {
    expect(component).toBeTruthy();
  });

  it('renders a labeled button for every page', () => {
    setInputs(2, 4);

    const buttons = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    );

    expect(buttons.length).toBe(4 + 4);
    buttons.forEach((button) => {
      expect(button.getAttribute('aria-label')).toBeTruthy();
    });
    expect(
      fixture.nativeElement.querySelectorAll('button[aria-current="page"]').length,
    ).toBe(1);
  });

  it('marks the current page with aria-current', () => {
    setInputs(3, 5);

    const current = fixture.nativeElement.querySelector(
      'button[aria-current="page"]',
    ) as HTMLButtonElement;

    expect(current.textContent?.trim()).toBe('3');
  });

  it('emits the selected page instead of navigating itself', () => {
    setInputs(1, 3);

    component.selectPage(3);
    expect(emitted).toEqual([3]);
  });

  it('emits the first page for the edge buttons', () => {
    setInputs(3, 5);

    component.selectFirst();
    component.selectPrevious();
    expect(emitted).toEqual([1, 2]);
  });

  it('emits the last page for the trailing edge buttons', () => {
    setInputs(1, 5);

    component.selectNext();
    component.selectLast();
    expect(emitted).toEqual([2, 5]);
  });

  it('never emits the already selected page or an out of range page', () => {
    setInputs(2, 3);

    component.selectPage(2);
    component.selectPage(0);
    component.selectPage(9);
    component.selectPage(Number.NaN);

    expect(emitted).toEqual([]);
  });

  it('disables the backward controls on the first page', () => {
    setInputs(1, 4);

    expect(component.canGoPrevious).toBeFalse();
    expect(component.canGoNext).toBeTrue();
    expect(
      (fixture.nativeElement.querySelector('button[aria-label="Önceki sayfa, sayfa 0"]') as HTMLButtonElement)
        ?.disabled,
    ).toBeTrue();
  });

  it('disables the forward controls on the last page', () => {
    setInputs(4, 4);

    expect(component.canGoNext).toBeFalse();
  });

  it('normalizes out of bound inputs', () => {
    setInputs(9, 3);

    expect(component.normalizedCurrentPage).toBe(3);
    expect(component.normalizedPageCount).toBe(3);
  });

  it('lists every page for small page counts and a window for large ones', () => {
    setInputs(1, 7);
    expect(component.visiblePages).toEqual([1, 2, 3, 4, 5, 6, 7]);

    setInputs(10, 20);
    expect(component.visiblePages).toEqual([1, 'gap', 9, 10, 11, 'gap', 20]);
  });
});
