export function scrollToFragment(fragmentId: string): void {
  requestAnimationFrame(() => {
    const target = document.getElementById(fragmentId);
    if (!target) {
      return;
    }

    const prefersReducedMotion = window.matchMedia(
      '(prefers-reduced-motion: reduce)'
    ).matches;

    target.scrollIntoView({
      behavior: prefersReducedMotion ? 'auto' : 'smooth',
      block: 'start',
    });
    target.focus({ preventScroll: true });
  });
}
