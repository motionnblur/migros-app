/** Owns one object URL and releases it before replacing it or on teardown. */
export class ObjectUrlManager {
  private currentUrl: string | null = null;

  create(blob: Blob): string {
    this.release();
    this.currentUrl = URL.createObjectURL(blob);
    return this.currentUrl;
  }

  release(): void {
    if (this.currentUrl !== null) {
      URL.revokeObjectURL(this.currentUrl);
      this.currentUrl = null;
    }
  }
}
