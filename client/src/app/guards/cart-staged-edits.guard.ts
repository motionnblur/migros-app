import { Injectable } from '@angular/core';
import { CanDeactivate } from '@angular/router';
import { Observable, of } from 'rxjs';

/**
 * What the guard needs from the component it is protecting.
 *
 * <p>Deliberately structural: the guard is named by the route table, which is
 * loaded before any component module is, so importing the component type here
 * would tie the two together for no benefit. The component implements this shape;
 * anything routed behind this guard that does not is allowed to leave.
 */
export interface CartStagedEditsHost {
  canDeactivateCart(): Observable<boolean>;
}

/**
 * Stops the cart route from being left while a locally staged edit has not
 * reached the server.
 *
 * <p>The defect this exists for: the cart is a named-outlet route, so the browser
 * Back button - or a forward navigation, a deep link, a restored tab - can leave
 * it without ever going through the component's own close handler. Teardown is far
 * too late to save anything: by the time `ngOnDestroy` runs the customer is
 * already looking at the page they navigated to, and a write issued from there
 * reports its result to a component nobody can see. A quantity they set, or a row
 * they deleted, simply evaporated.
 *
 * <p>It only asks. The router performs the navigation, and the answer is a
 * boolean, because a guard that both decides and navigates cannot tell a
 * cancelled navigation from a completed one - and a navigation started from
 * inside the guard would run whether or not the router agreed to proceed.
 */
@Injectable({ providedIn: 'root' })
export class CartStagedEditsGuard implements CanDeactivate<CartStagedEditsHost> {
  canDeactivate(component: CartStagedEditsHost): Observable<boolean> {
    if (!component || typeof component.canDeactivateCart !== 'function') {
      return of(true);
    }
    return component.canDeactivateCart();
  }
}
