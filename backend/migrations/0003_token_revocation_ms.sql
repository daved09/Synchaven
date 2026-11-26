-- "Sign out everywhere" and new passwords end sessions in milliseconds instead of whole seconds: with seconds,
-- a token issued in the same second as the sign-out stayed valid. Tokens carry their issue time in milliseconds
-- (`iat_ms`) from now on; older tokens count as issued at the start of their second.
UPDATE users SET tokens_valid_after = tokens_valid_after * 1000 WHERE tokens_valid_after IS NOT NULL;
