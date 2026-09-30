package com.example.MigrosBackend.helper;

/**
 * The name of the response header that carries the product version an admin edit
 * actually produced.
 *
 * <p>It is additive: {@code POST /admin/panel/updateProduct} keeps its body, its
 * status and its path, and this rides along beside them. It exists because an
 * open edit form has to learn the version of its <em>own</em> successful write
 * before it can save again, and the only version it is entitled to hold is the
 * one its write produced. Any version obtained from a separate later read is a
 * different version, obtained after an arbitrary amount of time during which a
 * checkout may have reserved stock - and pairing that newer version with the
 * older form values is what lets a stale absolute stock count be written back.
 *
 * <p>So the version is produced inside the editing transaction, after the change
 * has been flushed, and travels back on the same response that reported the
 * success.
 *
 * <p>This constant is the single owner of the name because two unrelated places
 * must agree on it: the controller that writes the header, and the CORS
 * configuration that has to expose it. A response header that is not listed in
 * {@code Access-Control-Expose-Headers} is invisible to browser JavaScript on a
 * cross-origin request, so without the second the first silently delivers nothing
 * to a split-origin deployment and the editor loses its version.
 */
public final class ProductEditVersionHeader {

    public static final String NAME = "X-Product-Version";

    private ProductEditVersionHeader() {
    }
}
