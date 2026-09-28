export interface EventMap {
  productAdded: undefined;
  productChanged: number;
  editorOpened: undefined;
}

export type EventName = keyof EventMap;
export type EventCallback<K extends EventName> = (data: EventMap[K]) => void;

export class EventManager {
  private events = new Map<EventName, ((data: never) => void)[]>();

  public on<K extends EventName>(eventName: K, callback: EventCallback<K>): void {
    const callbacks = this.events.get(eventName) ?? [];
    callbacks.push(callback as (data: never) => void);
    this.events.set(eventName, callbacks);
  }

  public off<K extends EventName>(eventName: K, callback: EventCallback<K>): void {
    const callbacks = this.events.get(eventName);
    if (callbacks) {
      const index = callbacks.indexOf(callback as (data: never) => void);
      if (index > -1) {
        callbacks.splice(index, 1);
      }
    }
  }

  public trigger(eventName: 'productChanged', data: number): void;
  public trigger(eventName: 'productAdded' | 'editorOpened', data?: undefined): void;
  public trigger(eventName: EventName, data?: number): void {
    this.events.get(eventName)?.forEach((callback) => callback(data as never));
  }
}
