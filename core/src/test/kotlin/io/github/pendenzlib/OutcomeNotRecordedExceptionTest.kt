package io.github.pendenzlib

import io.github.pendenzlib.store.Outcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.BatchUpdateException
import java.sql.SQLException
import java.time.Instant

class OutcomeNotRecordedExceptionTest {
    private val foreignKey =
        SQLException(
            "ERROR: insert or update on table \"billing_task\" violates foreign key constraint \"invoice_fk\"\n" +
                "  Detail: Key (invoice_id)=(4711) is not present in table \"invoice\".",
            "23503",
        )

    @Nested
    inner class Refused {
        @ParameterizedTest
        @ValueSource(strings = ["23503", "23514", "23502", "23505", "22001", "22P02", "42P01", "42501"])
        fun `refused - SQL state of class 22, 23 or 42 - true`(state: String) {
            val notRecorded = OutcomeNotRecordedException.of(Outcome.Succeeded(), SQLException("refused", state))

            assertThat(notRecorded.refused).describedAs(state).isTrue()
        }

        @ParameterizedTest
        @ValueSource(strings = ["P0001", "40001", "40P01", "08006", "53300", "57014"])
        fun `refused - SQL state of another class - false`(state: String) {
            val notRecorded = OutcomeNotRecordedException.of(Outcome.Succeeded(), SQLException("failed", state))

            assertThat(notRecorded.refused).describedAs(state).isFalse()
        }

        @Test
        fun `refused - no SQL exception in the chain, or one without a state - false`() {
            val poolExhausted =
                OutcomeNotRecordedException.of(
                    Outcome.Succeeded(),
                    IllegalStateException("pool exhausted"),
                )
            val stateless = OutcomeNotRecordedException.of(Outcome.Succeeded(), SQLException("connection closed"))

            // Assert
            assertThat(poolExhausted.refused).isFalse()
            assertThat(stateless.refused).isFalse()
        }

        @Test
        fun `refused - violation wrapped by the store's own exception - true`() {
            val wrapped = IllegalStateException("could not write", foreignKey)

            val notRecorded = OutcomeNotRecordedException.of(Outcome.Succeeded(), wrapped)

            assertThat(notRecorded.refused).isTrue()
        }
    }

    @Nested
    inner class Message {
        @Test
        fun `message - constraint violation with a detail line - names outcome and constraint, not the key`() {
            val notRecorded = OutcomeNotRecordedException.of(Outcome.Succeeded(), foreignKey)

            assertThat(notRecorded.message)
                .startsWith("outcome SUCCEEDED could not be recorded: java.sql.SQLException: ERROR: insert or update")
                .contains("invoice_fk")
                .doesNotContain("4711")
        }

        @Test
        fun `message - batch that quotes its statement around the violation - reports the violation alone`() {
            val batch =
                BatchUpdateException(
                    "Batch entry 0 INSERT INTO billing_task (invoice_id) VALUES (4711) was aborted",
                    "23503",
                    0,
                    intArrayOf(),
                    foreignKey,
                )

            val notRecorded = OutcomeNotRecordedException.of(Outcome.Succeeded(), batch)

            // Assert
            assertThat(notRecorded.message).contains("invoice_fk").doesNotContain("Batch entry", "4711")
            assertThat(notRecorded.cause).isSameAs(batch)
        }

        @Test
        fun `message - deferral and hand-over - named as deferral and QUARANTINED`() {
            val failure = IllegalStateException("pool exhausted")
            val until = Instant.parse("2026-11-17T12:00:00Z")

            val deferral = OutcomeNotRecordedException.of(Outcome.Deferred(until, "partner is down"), failure)
            val handOver = OutcomeNotRecordedException.of(Outcome.HandedOver("needs a person"), failure)

            // Assert
            assertThat(deferral.message).startsWith("outcome deferral could not be recorded: ")
            assertThat(handOver.message).startsWith("outcome QUARANTINED could not be recorded: ")
        }
    }
}
