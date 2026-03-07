import { Routes } from '@angular/router';
import { MainComponent } from '../pages/main/main.component';
import { AdminComponent } from '../pages/admin/admin.component';
import { DiscoverComponent } from '../pages/main/components/discover-area/parent/discover-area.component';
import { ProductPageComponent } from '../pages/main/components/product-page/product-page.component';
import { SignUserComponent } from '../pages/main/components/sign-user/sign-user.component';
import { UserCartComponent } from '../pages/main/components/user-cart/user-cart.component';
import { UserProfileComponent } from '../pages/main/components/user-profile/user-profile.component';
import { OrderTrackerComponent } from '../pages/main/components/order-tracker/order-tracker.component';
import { OrderHistoryComponent } from '../pages/main/components/order-history/order-history.component';
import { SupportChatComponent } from '../pages/main/components/support-chat/support-chat.component';

export const routes: Routes = [
  {
    path: '',
    component: MainComponent,
    children: [
      { path: '', pathMatch: 'full', component: DiscoverComponent },
      { path: 'category/:categoryId', component: ProductPageComponent },
      { path: 'category/:categoryId/product/:productId', component: ProductPageComponent },
      { path: 'cart', outlet: 'modal', component: UserCartComponent },
      { path: 'profile', outlet: 'modal', component: UserProfileComponent },
      { path: 'login', outlet: 'modal', component: SignUserComponent },
      { path: 'order-tracker', outlet: 'modal', component: OrderTrackerComponent },
      { path: 'order-history', outlet: 'modal', component: OrderHistoryComponent },
      { path: 'support', outlet: 'modal', component: SupportChatComponent },
    ],
  },
  { path: 'admin', component: AdminComponent },
  { path: '**', redirectTo: '' },
];
