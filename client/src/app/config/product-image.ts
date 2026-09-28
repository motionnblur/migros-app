const PRODUCT_IMAGE_PLACEHOLDER_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 160" role="presentation"><rect width="160" height="160" fill="#f4f5f7"/><path d="M40 62h80l-9 58H49z" fill="none" stroke="#c9ccd1" stroke-width="4" stroke-linejoin="round"/><path d="M60 62a20 20 0 0 1 40 0" fill="none" stroke="#c9ccd1" stroke-width="4" stroke-linecap="round"/></svg>`;

/**
 * Inline fallback used while a product image is still loading or when the
 * backend has no image for the product. Keeping it inline avoids a second
 * network request for a decorative placeholder.
 */
export const PRODUCT_IMAGE_PLACEHOLDER = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(
  PRODUCT_IMAGE_PLACEHOLDER_SVG,
)}`;
