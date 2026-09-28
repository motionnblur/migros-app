import { ComponentFixture, TestBed } from '@angular/core/testing';
import {
  ActivatedRoute,
  ParamMap,
  Router,
  convertToParamMap,
} from '@angular/router';
import { provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';

import { DiscoverComponent } from './discover-area.component';
import { CATEGORY_CATALOG } from '../../../../../memory/category-catalog';

describe('DiscoverAreaComponent', () => {
  let component: DiscoverComponent;
  let fixture: ComponentFixture<DiscoverComponent>;
  let router: Router;
  let queryParamMap$: BehaviorSubject<ParamMap>;
  let fragment$: BehaviorSubject<string | null>;
  let rafQueue: FrameRequestCallback[];
  let scrollSpy: jasmine.Spy;
  let focusSpy: jasmine.Spy;

  beforeEach(async () => {
    queryParamMap$ = new BehaviorSubject<ParamMap>(convertToParamMap({}));
    fragment$ = new BehaviorSubject<string | null>(null);
    rafQueue = [];

    spyOn(window, 'requestAnimationFrame').and.callFake(
      (callback: FrameRequestCallback) => {
        rafQueue.push(callback);
        return rafQueue.length;
      }
    );
    scrollSpy = spyOn(Element.prototype, 'scrollIntoView');
    focusSpy = spyOn(HTMLElement.prototype, 'focus');

    await TestBed.configureTestingModule({
      imports: [DiscoverComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            queryParamMap: queryParamMap$.asObservable(),
            fragment: fragment$.asObservable(),
          },
        },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(DiscoverComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  function setSearchQuery(query: string): void {
    queryParamMap$.next(
      convertToParamMap(query.trim() ? { q: query.trim() } : {})
    );
    fixture.detectChanges();
  }

  function flushAnimationFrames(): void {
    const pending = rafQueue;
    rafQueue = [];
    pending.forEach((callback) => callback(0));
  }

  function emitFragment(fragment: string | null): void {
    fragment$.next(fragment);
    flushAnimationFrames();
  }

  function setReducedMotion(prefersReduced: boolean): void {
    spyOn(window, 'matchMedia').and.returnValue({
      matches: prefersReduced,
    } as MediaQueryList);
  }

  function categorySection(): HTMLElement {
    return fixture.nativeElement.querySelector(
      '#categories'
    ) as HTMLElement;
  }

  function categoryLinks(): HTMLAnchorElement[] {
    const root = fixture.nativeElement as HTMLElement;
    return Array.from(root.querySelectorAll('.discover__grid a'));
  }

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('renders all 18 categories with corrected labels and route targets', () => {
    const links = categoryLinks();

    expect(links.length).toBe(18);
    expect(links.map((link) => link.getAttribute('href'))).toEqual(
      CATEGORY_CATALOG.map((category) => `/category/${category.value}`)
    );

    const labels = links.map((link) => link.textContent?.trim());
    expect(labels).toContain('Atıştırmalık');
    expect(labels).toContain('Çiçek');
    expect(labels).toContain('Meyve, Sebze');
    expect(component.searchQuery).toBe('');
  });

  it('keeps every category for an empty query', () => {
    setSearchQuery('   ');
    expect(categoryLinks().length).toBe(18);
    expect(component.visibleCategories.length).toBe(18);
  });

  it('filters with a plain matching query', () => {
    setSearchQuery('meyve');

    const links = categoryLinks();
    expect(links.length).toBe(1);
    expect(links[0].getAttribute('href')).toBe('/category/2');
    expect(links[0].textContent?.trim()).toBe('Meyve, Sebze');
    expect(component.searchQuery).toBe('meyve');
  });

  it('matches Turkish characters tolerantly and case-insensitively', () => {
    setSearchQuery('cicek');
    expect(categoryLinks().map((link) => link.getAttribute('href'))).toEqual([
      '/category/16',
    ]);

    setSearchQuery('CICEK');
    expect(categoryLinks()[0].textContent?.trim()).toBe('Çiçek');

    setSearchQuery('  ATISTIRMALIK  ');
    expect(categoryLinks().map((link) => link.getAttribute('href'))).toEqual([
      '/category/8',
    ]);
    expect(categoryLinks()[0].textContent?.trim()).toBe('Atıştırmalık');
  });

  it('shows a clearable no-results state when nothing matches', () => {
    setSearchQuery('balon patlamasi');

    expect(categoryLinks().length).toBe(0);
    const emptyState = fixture.nativeElement.querySelector(
      '.discover__empty'
    ) as HTMLElement;
    expect(emptyState).toBeTruthy();
    expect(emptyState.textContent).toContain('sonuç bulunamadı');
    expect(emptyState.textContent).toContain('balon patlamasi');

    const navigateSpy = spyOn(router, 'navigate');
    const clearButton = Array.from(
      fixture.nativeElement.querySelectorAll('button.discover__clear')
    ).find((button) =>
      (button as HTMLElement).textContent?.includes('Tüm kategorileri göster')
    ) as HTMLButtonElement;

    expect(clearButton).toBeTruthy();
    clearButton.click();

    expect(navigateSpy).toHaveBeenCalledWith(['/']);
  });

  it('restores every category after the query is cleared', () => {
    setSearchQuery('balon patlamasi');
    expect(categoryLinks().length).toBe(0);

    setSearchQuery('');
    expect(categoryLinks().length).toBe(18);
  });

  it('offers a clear control while a query is active', () => {
    setSearchQuery('dondurma');
    const root = fixture.nativeElement as HTMLElement;
    const clearButtons = Array.from(
      root.querySelectorAll('button.discover__clear')
    );
    expect(clearButtons.length).toBe(1);
    expect(clearButtons[0].textContent).toContain('Aramayı temizle');
  });

  it('smoothly scrolls to the category section for a route fragment', () => {
    setReducedMotion(false);
    const target = categorySection();

    emitFragment('categories');

    expect(scrollSpy).toHaveBeenCalledWith({
      behavior: 'smooth',
      block: 'start',
    });
    expect(scrollSpy.calls.mostRecent().object).toBe(target);
  });

  it('selects an instant scroll when the user prefers reduced motion', () => {
    setReducedMotion(true);

    emitFragment('categories');

    expect(scrollSpy).toHaveBeenCalledWith({
      behavior: 'auto',
      block: 'start',
    });
  });

  it('focuses the category section with preventScroll after scrolling', () => {
    setReducedMotion(false);
    const target = categorySection();

    emitFragment('categories');

    expect(focusSpy).toHaveBeenCalledWith({ preventScroll: true });
    expect(focusSpy.calls.mostRecent().object).toBe(target);
  });

  it('filters the catalog even when the route also carries the fragment', () => {
    setReducedMotion(false);

    queryParamMap$.next(convertToParamMap({ q: 'meyve' }));
    emitFragment('categories');
    fixture.detectChanges();

    expect(component.searchQuery).toBe('meyve');
    const links = categoryLinks();
    expect(links.length).toBe(1);
    expect(links[0].getAttribute('href')).toBe('/category/2');
    expect(scrollSpy).toHaveBeenCalledWith({
      behavior: 'smooth',
      block: 'start',
    });
  });
});
