-- V8: a PNR is issued at CONFIRMATION, not at hold time (§6.4, FR-26).
--
-- V4 declared `pnr TEXT NOT NULL`, which is wrong. §6.4 step 3 issues the PNR as
-- part of the HELD -> CONFIRMED transition, so a booking sitting in HELD or
-- PAYMENT_PENDING has no PNR to give. Under V4 the hold path would have had to
-- invent one, which contradicts FR-26's sequence-derived issuance and would burn
-- a PNR for every hold that expires unpaid -- the overwhelming majority during a
-- spike.
--
-- WHY A NEW MIGRATION RATHER THAN EDITING V4. V4 has already been applied to
-- every environment that has run this project, including CI. Editing it changes
-- its checksum, and Flyway then refuses to start against any database that ran
-- the original -- correctly, because "the schema is what the migrations say" is
-- the assumption every invariant check rests on. Applied migrations are
-- immutable; corrections are new migrations. The awkward history is the honest
-- record.

ALTER TABLE bookings ALTER COLUMN pnr DROP NOT NULL;

-- A biconditional, not two one-way checks. A PNR must exist for exactly the
-- states that have one, so this catches both errors: a CONFIRMED booking with no
-- PNR (INV-6 could not verify what is not there), and a HELD booking that was
-- issued one (a PNR consumed by a hold that will probably expire).
--
-- FAILED_REFUNDED is deliberately on the no-PNR side. That path never reached
-- CONFIRMED: payment was captured and returned without the booking ever being
-- seated (FR-24, FR-25), so there is nothing to print on a ticket.
ALTER TABLE bookings
    ADD CONSTRAINT pnr_present_exactly_when_confirmed
    CHECK ((status IN ('CONFIRMED', 'CANCELLED')) = (pnr IS NOT NULL));

COMMENT ON COLUMN bookings.pnr IS
    'NULL until confirmation. Issued from a sequence plus a Luhn check digit '
    '(FR-26) as part of the HELD -> CONFIRMED transition, and retained through '
    'CANCELLED so a cancelled ticket can still be looked up.';
