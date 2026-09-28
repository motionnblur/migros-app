import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { SupportRealtimeService } from './support-realtime.service';

describe('SupportRealtimeService', () => {
  let service: SupportRealtimeService;
  let socket: WebSocket & { triggerClose: () => void };
  let socketFactory: jasmine.Spy;

  beforeEach(() => {
    socket = {
      readyState: WebSocket.OPEN,
      close: jasmine.createSpy('close'),
      triggerClose: () => socket.onclose?.(new CloseEvent('close')),
    } as unknown as WebSocket & { triggerClose: () => void };
    socketFactory = spyOn(window, 'WebSocket').and.returnValue(socket);
    TestBed.configureTestingModule({});
    service = TestBed.inject(SupportRealtimeService);
  });

  it('reconnects after an unexpected socket close', fakeAsync(() => {
    service.connect('person@example.com');
    expect(socketFactory).toHaveBeenCalledTimes(1);

    socket.triggerClose();
    tick(2999);
    expect(socketFactory).toHaveBeenCalledTimes(1);
    tick(1);

    expect(socketFactory).toHaveBeenCalledTimes(2);
    expect(socketFactory.calls.mostRecent().args[0]).toContain(
      'userMail=person%40example.com',
    );
    service.disconnect();
  }));

  it('does not reconnect after an intentional disconnect', fakeAsync(() => {
    service.connect('person@example.com');
    service.disconnect();
    socket.triggerClose();
    tick(5000);

    expect(socketFactory).toHaveBeenCalledTimes(1);
  }));

  it('cancels a scheduled reconnect when disconnected', fakeAsync(() => {
    service.connect();
    socket.triggerClose();
    service.disconnect();
    tick(5000);

    expect(socketFactory).toHaveBeenCalledTimes(1);
  }));
});
