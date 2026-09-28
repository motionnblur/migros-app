import { TestBed } from '@angular/core/testing';

import { EventService } from './event.service';

describe('EventService', () => {
  let service: EventService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(EventService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('dispatches typed product events and preserves undefined payloads', () => {
    const productChanged = jasmine.createSpy<(productId: number) => void>();
    const productAdded = jasmine.createSpy<(event: undefined) => void>();
    const editorOpened = jasmine.createSpy<(event: undefined) => void>();

    service.on('productChanged', productChanged);
    service.on('productAdded', productAdded);
    service.on('editorOpened', editorOpened);

    service.trigger('productChanged', 42);
    service.trigger('productAdded');
    service.trigger('editorOpened');

    expect(productChanged).toHaveBeenCalledOnceWith(42);
    expect(productAdded).toHaveBeenCalledOnceWith(undefined);
    expect(editorOpened).toHaveBeenCalledOnceWith(undefined);

    service.off('productAdded', productAdded);
    service.trigger('productAdded');
    expect(productAdded).toHaveBeenCalledOnceWith(undefined);
  });
});
