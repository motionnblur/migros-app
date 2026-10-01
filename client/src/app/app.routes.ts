import { Routes } from '@angular/router';
import { MainComponent } from '../pages/main/main.component';
import { DiscoverComponent } from '../pages/main/components/discover-area/parent/discover-area.component';
import { ProductPageComponent } from '../pages/main/components/product-page/product-page.component';
import { SearchResultsComponent } from '../pages/main/components/search-results/search-results.component';
import { SignUserComponent } from '../pages/main/components/sign-user/sign-user.component';
import { UserCartComponent } from '../pages/main/components/user-cart/user-cart.component';
import { UserProfileComponent } from '../pages/main/components/user-profile/user-profile.component';
import { OrderTrackerComponent } from '../pages/main/components/order-tracker/order-tracker.component';
import { OrderHistoryComponent } from '../pages/main/components/order-history/order-history.component';
import { SupportChatComponent } from '../pages/main/components/support-chat/support-chat.component';
import { CartStagedEditsGuard } from './guards/cart-staged-edits.guard';

export const routes: Routes = [
  {
    path: '',
    component: MainComponent,
    children: [
      { path: '', pathMatch: 'full', component: DiscoverComponent },
      { path: 'search', component: SearchResultsComponent },
      { path: 'category/:categoryId', component: ProductPageComponent },
      {
        path: 'category/:categoryId/product/:productId',
        component: ProductPageComponent,
      },
      // The category-less product detail: a search result belongs to no single
      // category, so its card has no `/category/:id/product/:pid` URL to build and
      // links here instead. The filters that produced it stay in the query string
      // so the breadcrumb and the return link restore the same results.
      { path: 'product/:productId', component: ProductPageComponent },
      {
        path: 'cart',
        outlet: 'modal',
        component: UserCartComponent,
        // The modal can be left by the browser Back button, a deep link or a
        // forward navigation, none of which reach the component's own close
        // handler. Teardown is too late to save a staged edit, so the route asks
        // first and the router navigates only once the server has taken it.
        canDeactivate: [CartStagedEditsGuard],
      },
      { path: 'profile', outlet: 'modal', component: UserProfileComponent },
      { path: 'login', outlet: 'modal', component: SignUserComponent },
      {
        path: 'order-tracker',
        outlet: 'modal',
        component: OrderTrackerComponent,
      },
      {
        path: 'order-history',
        outlet: 'modal',
        component: OrderHistoryComponent,
      },
      { path: 'support', outlet: 'modal', component: SupportChatComponent },
      { path: 'reset-password/:token', component: SignUserComponent },
    ],
  },
  {
    path: 'admin',
    loadComponent: () =>
      import('../pages/admin/admin.component').then((module) => module.AdminComponent),
    children: [
      {
        path: '',
        pathMatch: 'full',
        loadComponent: () =>
          import('../pages/admin/components/admin-panel/admin-panel.component').then(
            (module) => module.AdminPanelComponent,
          ),
        data: { section: 'home' },
      },
      {
        path: 'products',
        loadComponent: () =>
          import('../pages/admin/components/admin-panel/admin-panel.component').then(
            (module) => module.AdminPanelComponent,
          ),
        data: { section: 'products' },
      },
      {
        path: 'orders',
        loadComponent: () =>
          import('../pages/admin/components/admin-panel/admin-panel.component').then(
            (module) => module.AdminPanelComponent,
          ),
        data: { section: 'orders' },
      },
      {
        path: 'support',
        loadComponent: () =>
          import('../pages/admin/components/admin-panel/admin-panel.component').then(
            (module) => module.AdminPanelComponent,
          ),
        data: { section: 'support' },
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
