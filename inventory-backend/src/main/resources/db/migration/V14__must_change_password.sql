-- Invited users get a temporary password and must replace it on first sign-in.
ALTER TABLE users
    ADD COLUMN must_change_password boolean NOT NULL DEFAULT false;