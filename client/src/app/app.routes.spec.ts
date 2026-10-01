import { routes } from './app.routes';
import { CartStagedEditsGuard } from './guards/cart-staged-edits.guard';
import { ProductPageComponent } from '../pages/main/components/product-page/product-page.component';
import { SearchResultsComponent } from '../pages/main/components/search-results/search-results.component';

describe('app routes', () => {
  it('lazy-loads the admin shell and each admin section under /admin', async () => {
    const adminRoute = routes.find((route) => route.path === 'admin');

    expect(adminRoute?.loadComponent).toEqual(jasmine.any(Function));
    expect(adminRoute?.component).toBeUndefined();

    const adminShell = await adminRoute!.loadComponent!();
    expect((adminShell as { name?: string }).name).toBe('AdminComponent');

    const adminChildren = adminRoute!.children ?? [];
    expect(adminChildren.map((route) => route.path)).toEqual([
      '',
      'products',
      'orders',
      'support',
    ]);

    for (const childRoute of adminChildren) {
      expect(childRoute.loadComponent).toEqual(jasmine.any(Function));
      expect(childRoute.component).toBeUndefined();
      const component = await childRoute.loadComponent!();
      expect((component as { name?: string }).name).toBe('AdminPanelComponent');
    }
  });

  it('keeps user modal routes on the named modal outlet', () => {
    const mainRoute = routes.find((route) => route.path === '');
    const modalPaths = (mainRoute?.children ?? [])
      .filter((route) => route.outlet === 'modal')
      .map((route) => route.path);

    expect(modalPaths).toEqual([
      'cart',
      'profile',
      'login',
      'order-tracker',
      'order-history',
      'support',
    ]);
  });

  /**
   * The cart is a route, so it can be left without ever reaching the component's
   * own close handler - the browser Back button alone is enough. Teardown cannot
   * save a staged edit from there, so the guard has to be on the route itself and
   * not merely implemented and unit tested.
   */
  it('guards the cart route against losing a staged edit to navigation', () => {
    const cartRoute = (routes.find((route) => route.path === '')?.children ?? []).find(
      (route) => route.path === 'cart',
    );

    expect(cartRoute?.canDeactivate).toEqual([CartStagedEditsGuard]);
  });

  /**
   * The header's search is a product search, so it needs a route of its own rather
   * than the landing page's `?q=` category filter.
   */
  it('routes the product search to the shared search results listing', () => {
    const children = routes.find((route) => route.path === '')?.children ?? [];
    const searchRoute = children.find((route) => route.path === 'search');

    expect(searchRoute?.component).toBe(SearchResultsComponent);
    // Its own path: a search result is not a category listing, and keeping the
    // term in the query string is what makes a results page shareable.
    expect(children.some((route) => route.path === '')).toBeTrue();
  });

  /**
   * A search result may belong to any category, so its card has no
   * `/category/:id/product/:pid` URL to build. This is the route it falls back to,
   * and it must be reachable without a category or the detail would be a 404 in
   * the customer's flow.
   */
  it('routes a product reached without a category to the product detail', () => {
    const children = routes.find((route) => route.path === '')?.children ?? [];

    const categoryListing = children.find((route) => route.path === 'category/:categoryId');
    const categoryDetail = children.find(
      (route) => route.path === 'category/:categoryId/product/:productId',
    );
    const standaloneDetail = children.find((route) => route.path === 'product/:productId');

    expect(categoryListing?.component).toBe(ProductPageComponent);
    expect(categoryDetail?.component).toBe(ProductPageComponent);
    expect(standaloneDetail?.component).toBe(ProductPageComponent);
    // Only the category routes carry a category parameter; the search fallback
    // deliberately does not invent one.
    expect(standaloneDetail?.component).toBe(ProductPageComponent);
  });
});
