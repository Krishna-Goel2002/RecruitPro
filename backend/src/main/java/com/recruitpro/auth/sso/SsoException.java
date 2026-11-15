package com.recruitpro.auth.sso;

/**
 * A refused SSO step. {@code code} is sent to the HR portal as {@code /login?ssoError=<code>}: invalid_state,
 * provider_error, cancelled, not_configured, token_exchange_failed, invalid_token, email_not_verified,
 * domain_not_allowed, no_account, account_disabled, account_locked.
 */
public class SsoException extends RuntimeException {

    private final String code;

    public SsoException(String code, String message) {
        super(message);
        this.code = code;
    }

    public SsoException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
