import { Component, OnDestroy, OnInit } from '@angular/core';
import { ProductAdderComponent } from '../product-adder/product-adder.component';
import { CommonModule } from '@angular/common';
import { ProductBodyComponent } from '../product-body/product-body.component';
import { WallComponent } from '../wall/wall.component';
import { ProductUpdaterComponent } from '../product-adder/product-updater.component';
import { EventService } from '../../../../services/event/event.service';
import { ProductEditComponent } from '../product-edit/product-edit.component';
import { OrderPanelComponent } from '../order-panel/order-panel.component';
import { SupportRealtimeService } from '../../../../services/support-realtime/support-realtime.service';
import { Subscription } from 'rxjs';
import { ActivatedRoute, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../../../services/auth/auth.service';
import { staticImageUrl } from '../../../../app/config/supabase-assets';
import { DashboardComponent } from '../dashboard/dashboard.component';
import { AdminSupportComponent } from '../admin-support/admin-support.component';

type AdminSection = 'home' | 'products' | 'orders' | 'support';

@Component({
  selector: 'app-admin-panel',
  standalone: true,
  imports: [
    ProductAdderComponent,
    ProductBodyComponent,
    CommonModule,
    WallComponent,
    ProductUpdaterComponent,
    ProductEditComponent,
    OrderPanelComponent,
    DashboardComponent,
    RouterLink,
    RouterLinkActive,
    AdminSupportComponent,
  ],
  templateUrl: './admin-panel.component.html',
  styleUrl: './admin-panel.component.css',
})
export class AdminPanelComponent implements OnInit, OnDestroy {
  readonly staticImageUrl = staticImageUrl;
  productId!: number;
  hasProductAdderOpened = false;
  hasProductsOpened = false;
  hasProductUpdaterOpened = false;
  hasProductEditOpened = false;
  hasOrdersOpened = false;
  hasSupportOpened = false;
  isLoggingOut = false;
  isSidebarOpen = false;
  currentSection: AdminSection = 'home';

  private routeSub: Subscription | null = null;

  private productChangedCallback!: (productId: number) => void;
  private editorOpenedCallback!: (event: undefined) => void;

  constructor(
    private eventManager: EventService,
    private supportRealtimeService: SupportRealtimeService,
    private authService: AuthService,
    private route: ActivatedRoute,
    private router: Router
  ) {
    this.productChangedCallback = (productId: number) => {
      this.productChangedEventHandler(productId);
    };
    this.editorOpenedCallback = (_event: undefined) => {
      this.editorOpenedEventHandler();
    };
  }

  get sectionTitle(): string {
    switch (this.currentSection) {
      case 'products':
        return 'Ürünler';
      case 'orders':
        return 'Siparişler';
      case 'support':
        return 'Canlı Destek';
      default:
        return 'Dashboard';
    }
  }

  get sectionSubtitle(): string {
    switch (this.currentSection) {
      case 'products':
        return 'Kategori bazlı ürün yönetimi';
      case 'orders':
        return 'Sipariş durumlarını yönetin';
      case 'support':
        return 'Müşteri sohbetlerini yanıtlayın';
      default:
        return 'Mağaza genel bakışı ve son hareketler';
    }
  }

  ngOnInit(): void {
    this.eventManager.on('productChanged', this.productChangedCallback);
    this.eventManager.on('editorOpened', this.editorOpenedCallback);

    this.routeSub = this.route.data.subscribe((data) => {
      const section = (data['section'] ?? 'home') as AdminSection;
      this.setSection(section);
    });
  }

  ngOnDestroy(): void {
    this.eventManager.off('productChanged', this.productChangedCallback);
    this.eventManager.off('editorOpened', this.editorOpenedCallback);
    this.routeSub?.unsubscribe();
  }

  toggleSidebar(): void {
    this.isSidebarOpen = !this.isSidebarOpen;
  }

  closeSidebar(): void {
    this.isSidebarOpen = false;
  }

  logoutAdmin() {
    if (this.isLoggingOut) {
      return;
    }

    this.isLoggingOut = true;
    this.supportRealtimeService.disconnect();

    this.authService.logoutAdmin(() => {
      this.isLoggingOut = false;
      this.router.navigate(['/admin']);
    });
  }

  productAddedEventHandler(event: boolean) {
    if (event === true) {
      this.closeProductAdder();
      this.closeProductUpdater();
    }
  }

  editorOpenedEventHandler() {
    this.hasProductEditOpened = !this.hasProductEditOpened;
  }

  productChangedEventHandler(productId: number) {
    this.productId = productId;
    this.hasProductAdderOpened = false;
    this.hasProductUpdaterOpened = !this.hasProductUpdaterOpened;
  }

  hasWallOnClickedEventAdder(event: boolean) {
    if (event === true) {
      this.closeProductAdder();
      this.closeProductUpdater();
      this.closeProductEdit();
    }
  }

  openProductAdder() {
    this.hasProductUpdaterOpened = false;
    this.hasProductAdderOpened = true;
  }

  closeProductAdder() {
    this.hasProductAdderOpened = false;
  }

  closeProductUpdater() {
    this.hasProductUpdaterOpened = false;
  }

  closeProductEdit() {
    this.hasProductEditOpened = false;
  }

  private setSection(section: AdminSection) {
    this.currentSection = section;

    this.hasProductsOpened = section === 'products';
    this.hasOrdersOpened = section === 'orders';
    this.hasSupportOpened = section === 'support';
  }
}
