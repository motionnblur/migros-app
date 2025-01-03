import { Component, EventEmitter, Output } from '@angular/core';
import { ProductBuyBase } from '../../../../base-components/product-buy.base';
import { CommonModule } from '@angular/common';
import { RestService } from '../../../../services/rest/rest.service';
import { EventService } from '../../../../services/event/event.service';

@Component({
  selector: 'app-product-edit',
  imports: [CommonModule],
  templateUrl: './product-edit.component.html',
  styleUrl: './product-edit.component.css',
})
export class ProductEditComponent extends ProductBuyBase {
  @Output() hasEscapePressed = new EventEmitter<boolean>();
  private boundKeyDownEvent!: (event: KeyboardEvent) => void;
  constructor(
    protected override restService: RestService,
    protected override eventManager: EventService
  ) {
    super(restService, eventManager);
    this.boundKeyDownEvent = this.keyDownEvent.bind(this);
  }
  override ngOnInit() {
    super.ngOnInit();
    document.addEventListener('keydown', this.boundKeyDownEvent);
  }
  ngOnDestroy() {
    document.removeEventListener('keydown', this.boundKeyDownEvent);
  }

  keyDownEvent(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      this.hasEscapePressed.emit(true);
    }
  }
}
