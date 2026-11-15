-- Trust fixes (16 Nov 2026): referee employer domains and badge issuers need independent domain trust.
-- organization_verification_settings: the organisation's own allow-lists (JSON arrays of lower-case domain names);
--   NULL = empty. A listed domain (or a subdomain of it) counts as trusted for that organisation only.
-- claim_verdicts.domain_trust_json: the agent's domain-trust result for the verdict ({check, domain, status,
--   trustedBy, reasons, candidateControlled, cappedFrom, signals}); NULL when no domain check applied.
-- H2-compatible.

ALTER TABLE organization_verification_settings ADD COLUMN trusted_employer_domains_json TEXT NULL;
ALTER TABLE organization_verification_settings ADD COLUMN trusted_badge_issuers_json TEXT NULL;
ALTER TABLE claim_verdicts ADD COLUMN domain_trust_json TEXT NULL;
