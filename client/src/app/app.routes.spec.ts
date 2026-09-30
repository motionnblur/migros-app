import { routes } from './app.routes';
import { CartStagedEditsGuard } from './guards/cart-staged-edits.guard';

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
});
