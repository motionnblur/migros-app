export class EventManager {
  private events: {
    [eventName: string]: ((data: any) => void)[];
  } = {};

  public on(eventName: string, callback: (data: any) => void): void {
    if (!this.events[eventName]) {
      this.events[eventName] = [];
    }
    this.events[eventName].push(callback);
  }

  public off(eventName: string, callback: (data: any) => void): void {
    if (this.events[eventName]) {
      const index = this.events[eventName].indexOf(callback);
      if (index > -1) {
        this.events[eventName].splice(index, 1);
      }
    }
  }

  public trigger(eventName: string, data?: any): void {
    if (this.events[eventName]) {
      this.events[eventName].forEach((callback) => callback(data));
    }
  }
}
