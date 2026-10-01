import { Component, EventEmitter, Input, Output } from '@angular/core';

@Component({
  selector: 'app-support-fab',
  standalone: true,
  templateUrl: './support-fab.component.html',
  styleUrl: './support-fab.component.css',
})
export class SupportFabComponent {
  /**
   * Whether a modal dialog is currently open somewhere on the page.
   *
   * <p>The launcher is a control on the page *behind* the `modal` outlet, and a
   * dialog is a promise that the rest of the page is out of reach: `aria-modal`
   * tells a screen reader it is gone, and the dialog's own Tab handling keeps the
   * keyboard inside it. A launcher that merely paints underneath the scrim
   * contradicts both - it is still in the accessibility tree, still in the tab
   * order, and still a floating orange target on top of the dialog's own footer
   * action on a phone-sized viewport.
   *
   * <p>So it is not rendered at all while a dialog is open, rather than dimmed.
   * Removal is the only version of this that is true in the accessibility tree as
   * well as on screen, and there is nothing to lose by it: the dialog is a
   * deliberate step the customer took, and it closes on its own terms.
   */
  @Input() isSuppressed = false;

  @Output() supportRequested = new EventEmitter<void>();

  public openSupport(): void {
    this.supportRequested.emit();
  }
}
