import { EventService } from '../services/event/event.service';
import { RestService } from '../services/rest/rest.service';

export class ProductBuyBase {
  protected restService: RestService;
  protected eventManager: EventService;
  constructor(restService: RestService, eventManager: EventService) {
    this.restService = restService;
    this.eventManager = eventManager;
  }
}
