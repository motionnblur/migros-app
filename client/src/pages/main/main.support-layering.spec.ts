import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Route, Router, RouterOutlet, provideRouter } from '@angular/router';
import { Subject } from 'rxjs';

import { ISupportRealtimeEvent } from '../../interfaces/support/ISupportRealtimeEvent';
import { MainComponent } from './main.component';
import { SupportRealtimeService } from '../../services/support-realtime/support-realtime.service';

/** A page behind the modals, so the default outlet has something to render. */
@Component({ standalone: true, template: '<p>storefront</p>' })
class StorefrontStubComponent {}

/** A stand-in for any of the real modals; only its presence on the outlet matters. */
@Component({
  standalone: true,
  template: '<div data-testid="modal-stub" role="dialog">dialog</div>',
})
class ModalStubComponent {}

/**
 * The shell under test has to be *routed* for its own outlet to work: a
 * `RouterOutlet` only activates a component when the router has attached it to an
 * activated route, which it cannot do for a component TestBed created by hand. So
 * the shell is reached the way the application reaches it, through a root outlet
 * and the same route table shape - a page plus modals on a named outlet.
 */
@Component({ standalone: true, imports: [RouterOutlet], template: '<router-outlet></router-outlet>' })
class RootHostComponent {}

const TEST_ROUTES: Route[] = [
  {
    path: '',
    component: MainComponent,
    children: [
      { path: '', pathMatch: 'full', component: StorefrontStubComponent },
      { path: 'cart', outlet: 'modal', component: ModalStubComponent },
      { path: 'support', outlet: 'modal', component: ModalStubComponent },
    ],
  },
];

/**
 * The support launcher against a modal dialog.
 *
 * <p>The launcher is rendered by the shell, outside the `modal` outlet, so nothing
 * about the dialog itself stopped it floating over the dialog. It used to: it
 * declared a z-index above every dialog, which on a 390px phone - where the cart
 * fills all but 8px of the viewport - put the orange launcher on top of the
 * cart's own checkout button.
 *
 * <p>These assertions are about the launcher not existing while a dialog does,
 * rather than about paint order. A launcher underneath a translucent scrim is
 * still in the tab order and still in the accessibility tree, so a customer using
 * a keyboard or a screen reader would still meet the control the dialog says is
 * gone.
 */
describe('the support launcher while a modal dialog is open', () => {
  let fixture: ComponentFixture<RootHostComponent>;
  let element: HTMLElement;
  let router: Router;
  let httpMock: HttpTestingController;

  function launcher(): HTMLButtonElement | null {
    return element.querySelector('.support-fab');
  }

  function modalOpen(): boolean {
    return element.querySelector('[data-testid="modal-stub"]') !== null;
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    const supportStub = {
      events$: new Subject<ISupportRealtimeEvent>(),
      connect: jasmine.createSpy('connect'),
      disconnect: jasmine.createSpy('disconnect'),
    };

    await TestBed.configureTestingModule({
      imports: [RootHostComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(TEST_ROUTES),
        { provide: SupportRealtimeService, useValue: supportStub },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    httpMock = TestBed.inject(HttpTestingController);

    fixture = TestBed.createComponent(RootHostComponent);
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await router.navigateByUrl('/');
    await settle();
    httpMock.match(() => true).forEach((request) => {
      if (!request.cancelled) {
        request.flush('');
      }
    });
    await settle();
  });

  afterEach(() => {
    fixture.destroy();
    httpMock.match(() => true).forEach((request) => {
      if (!request.cancelled) {
        request.flush('');
      }
    });
    httpMock.verify({ ignoreCancelled: true });
  });

  it('offers the launcher while no dialog is open', () => {
    expect(modalOpen()).toBeFalse();
    expect(launcher()).toBeTruthy();
    expect(launcher()!.getAttribute('aria-label')).toBe('Canlı Destek');
  });

  it('withdraws the launcher as soon as a dialog takes the outlet', async () => {
    await router.navigate([{ outlets: { modal: ['cart'] } }]);
    await settle();

    expect(modalOpen()).toBeTrue();
    expect(launcher()).toBeNull();
  });

  it('brings the launcher back when the dialog leaves', async () => {
    await router.navigate([{ outlets: { modal: ['cart'] } }]);
    await settle();
    expect(launcher()).toBeNull();

    await router.navigate([{ outlets: { modal: null } }]);
    await settle();

    expect(modalOpen()).toBeFalse();
    expect(launcher()).toBeTruthy();
  });

  /**
   * One modal replaces another on the same outlet, so the outlet emits
   * `deactivate` and then `activate` in the same navigation. A launcher that
   * counted modals - or that only watched for activation - would be left hidden
   * for the rest of the session, taking the only support entry point with it.
   */
  it('keeps the launcher withdrawn but not lost when one modal replaces another', async () => {
    await router.navigate([{ outlets: { modal: ['cart'] } }]);
    await settle();
    expect(launcher()).toBeNull();

    await router.navigate([{ outlets: { modal: ['support'] } }]);
    await settle();

    expect(modalOpen()).toBeTrue();
    expect(launcher()).toBeNull();

    await router.navigate([{ outlets: { modal: null } }]);
    await settle();
    expect(launcher()).toBeTruthy();
  });

  /**
   * The state is driven by the outlet's own events rather than by reading the URL,
   * so it is a fact about what is on screen. A deep link that opens a dialog
   * directly - the Back button, a shared cart URL - has to withdraw the launcher
   * just as opening one from the header does.
   */
  it('withdraws it for a dialog arrived at by URL rather than by press', async () => {
    await router.navigateByUrl('/(modal:cart)');
    await settle();

    expect(modalOpen()).toBeTrue();
    expect(launcher()).toBeNull();
  });
});
