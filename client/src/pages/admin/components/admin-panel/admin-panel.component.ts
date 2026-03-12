import { Component, OnDestroy, OnInit } from '@angular/core';
import { ProductAdderComponent } from '../product-adder/product-adder.component';
import { CommonModule } from '@angular/common';
import { ProductBodyComponent } from '../product-body/product-body.component';
import { WallComponent } from '../wall/wall.component';
import { ProductUpdaterComponent } from '../product-adder/product-updater.component';
import { EventService } from '../../../../services/event/event.service';
import { ProductEditComponent } from '../product-edit/product-edit.component';
import { OrderPanelComponent } from '../order-panel/order-panel.component';
import { RestService } from '../../../../services/rest/rest.service';
import { FormsModule } from '@angular/forms';
import { IChatMessage } from '../../../../interfaces/IChatMessage';
import { SupportRealtimeService } from '../../../../services/support-realtime/support-realtime.service';
import { ISupportRealtimeEvent } from '../../../../interfaces/support/ISupportRealtimeEvent';
import { ISupportCustomerSummary } from '../../../../interfaces/support/ISupportCustomerSummary';
import { Subscription } from 'rxjs';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../../services/auth/auth.service';

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
    FormsModule,
    RouterLink,
  ],
  templateUrl: './admin-panel.component.html',
  styleUrl: './admin-panel.component.css',
})
export class AdminPanelComponent implements OnInit, OnDestroy {
  productId!: number;
  hasProductAdderOpened = false;
  hasProductsOpened = false;
  hasProductUpdaterOpened = false;
  hasProductEditOpened = false;
  hasOrdersOpened = false;
  hasSupportOpened = false;
  isLoggingOut = false;
  currentSection: 'home' | 'products' | 'orders' | 'support' = 'home';

  supportUsers: string[] = [];
  bannedUsers: string[] = [];
  selectedSupportUserMail = '';
  supportMessages: IChatMessage[] = [];
  supportReplyInput = '';
  editingSupportMessageId: number | null = null;
  supportEditInput = '';
  supportMessageActionInProgressId: number | null = null;
  isSupportLoading = false;
  isSupportSending = false;
  supportError = '';
  supportCustomerQuery = '';
  supportCustomerResults: ISupportCustomerSummary[] = [];
  isSupportCustomerSearchLoading = false;

  private supportPollingIntervalId: ReturnType<typeof setInterval> | null = null;
  private supportRealtimeSub: Subscription | null = null;
  private routeSub: Subscription | null = null;
  private supportCustomerSearchTimer: ReturnType<typeof setTimeout> | null = null;

  private productChangedCallback!: (data: any) => void;
  private editorOpenedCallback!: (id: number) => void;

  constructor(
    private eventManager: EventService,
    private restService: RestService,
    private supportRealtimeService: SupportRealtimeService,
    private authService: AuthService,
    private route: ActivatedRoute,
    private router: Router
  ) {
    this.productChangedCallback = (data: any) => {
      this.productChangedEventHandler(data);
    };
    this.editorOpenedCallback = () => {
      this.editorOpenedEventHandler();
    };
  }

  ngOnInit(): void {
    this.eventManager.on('productChanged', this.productChangedCallback);
    this.eventManager.on('editorOpened', this.editorOpenedCallback);

    this.supportRealtimeService.connect();
    this.supportRealtimeSub = this.supportRealtimeService.events$.subscribe(
      (event: ISupportRealtimeEvent) => {
        if (!this.hasSupportOpened) {
          return;
        }

        this.loadSupportUsers();
        this.loadBannedUsers();

        if (
          this.selectedSupportUserMail &&
          this.selectedSupportUserMail === event.userMail
        ) {
          this.loadSupportMessages();
        }
      }
    );

    this.routeSub = this.route.data.subscribe((data) => {
      const section = (data['section'] ?? 'home') as
        | 'home'
        | 'products'
        | 'orders'
        | 'support';
      this.setSection(section);
    });
  }

  ngOnDestroy(): void {
    this.eventManager.off('productChanged', this.productChangedCallback);
    this.eventManager.off('editorOpened', this.editorOpenedCallback);
    this.stopSupportPolling();
    this.supportRealtimeSub?.unsubscribe();
    this.routeSub?.unsubscribe();

    if (this.supportCustomerSearchTimer) {
      clearTimeout(this.supportCustomerSearchTimer);
      this.supportCustomerSearchTimer = null;
    }
  }

  logoutAdmin() {
    if (this.isLoggingOut) {
      return;
    }

    this.isLoggingOut = true;
    this.stopSupportPolling();
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

  private setSection(section: 'home' | 'products' | 'orders' | 'support') {
    this.currentSection = section;

    this.hasProductsOpened = section === 'products';
    this.hasOrdersOpened = section === 'orders';
    this.hasSupportOpened = section === 'support';

    if (this.hasSupportOpened) {
      this.loadSupportUsers();
      this.loadBannedUsers();
      this.startSupportPolling();
      return;
    }

    this.stopSupportPolling();
  }

  loadSupportUsers() {
    this.restService.getSupportUsersForAdmin().subscribe({
      next: (users: string[]) => {
        this.supportUsers = users;

        if (!users.length) {
          if (!this.selectedSupportUserMail) {
            this.supportMessages = [];
          }
          return;
        }

        if (!this.selectedSupportUserMail) {
          this.selectedSupportUserMail = users[0];
        }

        if (this.selectedSupportUserMail) {
          this.loadSupportMessages();
        }
      },
      error: () => {
        this.supportError = 'Kullanici sohbetleri yuklenemedi.';
      },
    });
  }

  loadBannedUsers() {
    this.restService.getBannedSupportUsersForAdmin().subscribe({
      next: (users: string[]) => {
        this.bannedUsers = users;
      },
      error: () => {
        this.supportError = 'Banli kullanicilar yuklenemedi.';
      },
    });
  }

  onSupportCustomerSearchChange() {
    const query = this.supportCustomerQuery.trim();

    if (this.supportCustomerSearchTimer) {
      clearTimeout(this.supportCustomerSearchTimer);
      this.supportCustomerSearchTimer = null;
    }

    if (!query) {
      this.supportCustomerResults = [];
      this.isSupportCustomerSearchLoading = false;
      return;
    }

    this.supportCustomerSearchTimer = setTimeout(() => {
      this.searchSupportCustomers(query);
    }, 300);
  }

  private searchSupportCustomers(query: string) {
    this.isSupportCustomerSearchLoading = true;

    this.restService.searchSupportCustomersForAdmin(query, 20).subscribe({
      next: (customers: ISupportCustomerSummary[]) => {
        this.isSupportCustomerSearchLoading = false;
        this.supportCustomerResults = customers || [];
      },
      error: () => {
        this.isSupportCustomerSearchLoading = false;
        this.supportError = 'Kullanici aramasi yapilamadi.';
      },
    });
  }

  selectSupportCustomerFromSearch(customer: ISupportCustomerSummary) {
    if (!customer?.userMail) {
      return;
    }

    this.selectedSupportUserMail = customer.userMail;
    this.supportError = '';
    this.loadSupportMessages();
  }

  selectSupportUser(userMail: string) {
    this.selectedSupportUserMail = userMail;
    this.loadSupportMessages();
  }

  loadSupportMessages() {
    if (!this.selectedSupportUserMail) {
      this.supportMessages = [];
      return;
    }

    this.isSupportLoading = true;
    this.supportError = '';

    this.restService
      .getSupportMessagesForAdmin(this.selectedSupportUserMail)
      .subscribe({
        next: (messages: IChatMessage[]) => {
          this.supportMessages = messages;
          this.isSupportLoading = false;
          this.editingSupportMessageId = null;
          this.supportEditInput = '';
          this.supportMessageActionInProgressId = null;
        },
        error: () => {
          this.isSupportLoading = false;
          this.supportError = 'Mesajlar yuklenemedi.';
        },
      });
  }

  startEditSupportMessage(message: IChatMessage) {
    if (this.supportMessageActionInProgressId !== null) {
      return;
    }

    this.editingSupportMessageId = message.id;
    this.supportEditInput = message.message || '';
    this.supportError = '';
  }

  cancelEditSupportMessage() {
    if (this.supportMessageActionInProgressId !== null) {
      return;
    }

    this.editingSupportMessageId = null;
    this.supportEditInput = '';
  }

  saveEditedSupportMessage(message: IChatMessage) {
    if (!this.selectedSupportUserMail || this.supportMessageActionInProgressId !== null) {
      return;
    }

    const nextMessage = this.supportEditInput.trim();
    if (!nextMessage) {
      this.supportError = 'Mesaj bos olamaz.';
      return;
    }

    this.supportMessageActionInProgressId = message.id;
    this.restService
      .editSupportMessageForAdmin(this.selectedSupportUserMail, message.id, nextMessage)
      .subscribe({
        next: () => {
          this.supportMessageActionInProgressId = null;
          this.editingSupportMessageId = null;
          this.supportEditInput = '';
          this.loadSupportMessages();
          this.loadSupportUsers();
        },
        error: (err) => {
          this.supportMessageActionInProgressId = null;
          this.supportError =
            typeof err?.error === 'string' && err.error
              ? err.error
              : 'Mesaj duzenlenemedi.';
        },
      });
  }

  deleteSupportMessage(message: IChatMessage) {
    if (!this.selectedSupportUserMail || this.supportMessageActionInProgressId !== null) {
      return;
    }

    const approved = confirm('Mesaj kalici olarak silinecek. Devam edilsin mi?');
    if (!approved) {
      return;
    }

    this.supportMessageActionInProgressId = message.id;
    this.restService
      .deleteSupportMessageForAdmin(this.selectedSupportUserMail, message.id)
      .subscribe({
        next: () => {
          this.supportMessageActionInProgressId = null;
          if (this.editingSupportMessageId === message.id) {
            this.editingSupportMessageId = null;
            this.supportEditInput = '';
          }
          this.loadSupportMessages();
          this.loadSupportUsers();
        },
        error: (err) => {
          this.supportMessageActionInProgressId = null;
          this.supportError =
            typeof err?.error === 'string' && err.error
              ? err.error
              : 'Mesaj silinemedi.';
        },
      });
  }

  isSupportMessageActionBusy(message: IChatMessage): boolean {
    return this.supportMessageActionInProgressId === message.id;
  }

  sendSupportReply() {
    const message = this.supportReplyInput.trim();
    if (!this.selectedSupportUserMail || !message || this.isSupportSending) {
      return;
    }

    if (this.isSelectedUserBanned()) {
      this.supportError = 'Banli kullaniciya mesaj gonderilemez.';
      return;
    }

    this.isSupportSending = true;
    this.restService
      .sendSupportReplyFromAdmin(this.selectedSupportUserMail, message)
      .subscribe({
        next: () => {
          this.supportReplyInput = '';
          this.isSupportSending = false;
          this.loadSupportMessages();
          this.loadSupportUsers();
        },
        error: (err) => {
          this.isSupportSending = false;
          this.supportError =
            typeof err?.error === 'string' && err.error
              ? err.error
              : 'Yanit gonderilemedi.';
        },
      });
  }

  closeSupportChat() {
    if (!this.selectedSupportUserMail) {
      return;
    }

    const approved = confirm(
      `Sohbet kapatilacak: ${this.selectedSupportUserMail}`
    );
    if (!approved) {
      return;
    }

    this.restService
      .closeSupportChatForAdmin(this.selectedSupportUserMail)
      .subscribe({
        next: () => {
          const closedUser = this.selectedSupportUserMail;
          this.supportUsers = this.supportUsers.filter((u) => u !== closedUser);
          this.selectedSupportUserMail = this.supportUsers.length
            ? this.supportUsers[0]
            : '';
          this.supportMessages = [];
          this.supportReplyInput = '';

          if (this.selectedSupportUserMail) {
            this.loadSupportMessages();
          }
        },
        error: () => {
          this.supportError = 'Sohbet kapatilamadi.';
        },
      });
  }

  banSupportUser() {
    if (!this.selectedSupportUserMail) {
      return;
    }

    const approved = confirm(
      `Kullanici banlanacak: ${this.selectedSupportUserMail}`
    );
    if (!approved) {
      return;
    }

    this.restService
      .banSupportUserFromAdmin(this.selectedSupportUserMail)
      .subscribe({
        next: () => {
          this.supportError = 'Kullanici banlandi.';
          this.loadBannedUsers();
        },
        error: () => {
          this.supportError = 'Kullanici banlanamadi.';
        },
      });
  }

  isSelectedUserBanned(): boolean {
    if (!this.selectedSupportUserMail) {
      return false;
    }

    return this.bannedUsers.includes(this.selectedSupportUserMail);
  }

  unbanSupportUser() {
    if (!this.selectedSupportUserMail) {
      return;
    }

    this.unbanUserByMail(this.selectedSupportUserMail);
  }

  unbanUserByMail(userMail: string) {
    const approved = confirm(`Kullanici bani kaldirilacak: ${userMail}`);
    if (!approved) {
      return;
    }

    this.restService.unbanSupportUserFromAdmin(userMail).subscribe({
      next: () => {
        this.supportError = 'Kullanici bani kaldirildi.';
        this.loadBannedUsers();
      },
      error: () => {
        this.supportError = 'Kullanici ban kaldirma islemi basarisiz.';
      },
    });
  }

  private startSupportPolling() {
    this.stopSupportPolling();
    this.supportPollingIntervalId = setInterval(() => {
      if (this.hasSupportOpened) {
        this.loadSupportUsers();
        this.loadBannedUsers();
      }
    }, 5000);
  }

  private stopSupportPolling() {
    if (this.supportPollingIntervalId) {
      clearInterval(this.supportPollingIntervalId);
      this.supportPollingIntervalId = null;
    }
  }
}
