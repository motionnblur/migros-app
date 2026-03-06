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

@Component({
  selector: 'app-admin-panel',
  imports: [
    ProductAdderComponent,
    ProductBodyComponent,
    CommonModule,
    WallComponent,
    ProductUpdaterComponent,
    ProductEditComponent,
    OrderPanelComponent,
    FormsModule,
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

  supportUsers: string[] = [];
  selectedSupportUserMail = '';
  supportMessages: IChatMessage[] = [];
  supportReplyInput = '';
  isSupportLoading = false;
  isSupportSending = false;
  supportError = '';
  private supportPollingIntervalId: ReturnType<typeof setInterval> | null = null;

  private productChangedCallback!: (data: any) => void;
  private editorOpenedCallback!: (id: number) => void;

  constructor(
    private eventManager: EventService,
    private restService: RestService
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
  }

  ngOnDestroy(): void {
    this.eventManager.off('productChanged', this.productChangedCallback);
    this.eventManager.off('editorOpened', this.editorOpenedCallback);
    this.stopSupportPolling();
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

  open_close_Products() {
    this.hasProductsOpened = !this.hasProductsOpened;
    if (this.hasProductsOpened) {
      this.hasOrdersOpened = false;
      this.hasSupportOpened = false;
      this.stopSupportPolling();
    }
  }

  open_close_Orders() {
    this.hasOrdersOpened = !this.hasOrdersOpened;
    if (this.hasOrdersOpened) {
      this.hasProductsOpened = false;
      this.hasSupportOpened = false;
      this.stopSupportPolling();
    }
  }

  open_close_Support() {
    this.hasSupportOpened = !this.hasSupportOpened;

    if (this.hasSupportOpened) {
      this.hasProductsOpened = false;
      this.hasOrdersOpened = false;
      this.loadSupportUsers();
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
          this.selectedSupportUserMail = '';
          this.supportMessages = [];
          return;
        }

        if (!this.selectedSupportUserMail || !users.includes(this.selectedSupportUserMail)) {
          this.selectedSupportUserMail = users[0];
        }

        this.loadSupportMessages();
      },
      error: () => {
        this.supportError = 'Kullanici sohbetleri yuklenemedi.';
      },
    });
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

    this.restService.getSupportMessagesForAdmin(this.selectedSupportUserMail).subscribe({
      next: (messages: IChatMessage[]) => {
        this.supportMessages = messages;
        this.isSupportLoading = false;
      },
      error: () => {
        this.isSupportLoading = false;
        this.supportError = 'Mesajlar yuklenemedi.';
      },
    });
  }

  sendSupportReply() {
    const message = this.supportReplyInput.trim();
    if (!this.selectedSupportUserMail || !message || this.isSupportSending) {
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
        },
        error: () => {
          this.isSupportSending = false;
          this.supportError = 'Yanit gonderilemedi.';
        },
      });
  }

  private startSupportPolling() {
    this.stopSupportPolling();
    this.supportPollingIntervalId = setInterval(() => {
      if (this.hasSupportOpened) {
        this.loadSupportUsers();
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
