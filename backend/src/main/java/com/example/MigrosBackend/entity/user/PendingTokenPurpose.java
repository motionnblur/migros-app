package com.example.MigrosBackend.entity.user;

/**
 * The single purpose a pending one-time token was issued for.
 *
 * <p>Signup confirmation tokens and password-reset tokens are both stored in
 * {@code pending_signup_entity} and are both keyed by an opaque random string.
 * Without an explicit purpose the two flows can consume each other's tokens: a
 * signup token could reset a password and a reset token could confirm an
 * account. Recording the purpose at issue time and requiring the consuming
 * endpoint to match it makes each token single-use for exactly one action.
 */
public enum PendingTokenPurpose {
    /** Issued by signup; only {@code /user/signup/confirm} may consume it. */
    SIGNUP,
    /** Issued by forgot-password; only password reset may consume it. */
    PASSWORD_RESET
}
