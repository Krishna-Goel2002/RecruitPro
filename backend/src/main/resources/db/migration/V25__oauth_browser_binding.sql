-- Review fix (16 Nov 2026): GitHub OAuth login-CSRF / account linking. The state row is now bound to the browser that
-- started the flow (SHA-256 hex of a random nonce kept in an httpOnly cookie) and carries the PKCE (S256) verifier,
-- encrypted with SecretCipher (context "voauth:<stateHash>"). Both are NULL on rows created before this migration;
-- such rows are refused by the callback (they expire after 10 minutes anyway). H2-compatible.

ALTER TABLE verification_oauth_states ADD COLUMN browser_binding_hash VARCHAR(64) NULL;
ALTER TABLE verification_oauth_states ADD COLUMN code_verifier_enc VARCHAR(500) NULL;
