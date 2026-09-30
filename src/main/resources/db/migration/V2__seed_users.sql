-- Synthetic test users with static bearer tokens.
-- This is a QA portfolio system; static tokens are a deliberate simplification
-- so tests and manual exploration need no auth ceremony. Never a production pattern.
INSERT INTO app_user (id, name, token)
VALUES ('11111111-1111-1111-1111-111111111111', 'alice', 'qa-token-alice'),
       ('22222222-2222-2222-2222-222222222222', 'bob', 'qa-token-bob');
