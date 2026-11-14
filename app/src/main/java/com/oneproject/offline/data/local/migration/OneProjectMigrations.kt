package com.oneproject.offline.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * กฎข้อ 01 & Phase 02:
 * การอัปเกรดโครงสร้างฐานข้อมูลต้องใช้ Migration ที่ชัดเจน ห้ามใช้ fallbackToDestructiveMigration
 * MIGRATION_1_2 อัปเกรดจาก Phase 01 (rounds, policy_versions, audit_logs) สู่ Full Authoritative Schema ใน Phase 02
 * โดยรักษาข้อมูลเดิมทั้งหมดไว้ครบถ้วน
 */
object OneProjectMigrations {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. Local Actors, Role Grants, Approvals
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `local_actors` (
                    `actor_id` TEXT NOT NULL,
                    `actor_code` TEXT NOT NULL,
                    `display_name_th` TEXT NOT NULL,
                    `primary_role` TEXT NOT NULL,
                    `is_active` INTEGER NOT NULL,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`actor_id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_actors_actor_code` ON `local_actors` (`actor_code`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `local_role_grants` (
                    `grant_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `actor_id` TEXT NOT NULL,
                    `role_code` TEXT NOT NULL,
                    `granted_at_epoch_ms` INTEGER NOT NULL,
                    FOREIGN KEY(`actor_id`) REFERENCES `local_actors`(`actor_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_role_grants_actor_id_role_code` ON `local_role_grants` (`actor_id`, `role_code`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `approval_records` (
                    `approval_id` TEXT NOT NULL,
                    `target_type` TEXT NOT NULL,
                    `target_id` TEXT NOT NULL,
                    `round_id` TEXT,
                    `approved_by_actor_id` TEXT NOT NULL,
                    `reason_th` TEXT NOT NULL,
                    `approved_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`approval_id`),
                    FOREIGN KEY(`approved_by_actor_id`) REFERENCES `local_actors`(`actor_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_approval_records_target_type_target_id` ON `approval_records` (`target_type`, `target_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_approval_records_approved_by_actor_id` ON `approval_records` (`approved_by_actor_id`)")

            // 2. Number Policy Overrides
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `number_policy_overrides` (
                    `override_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `round_id` TEXT NOT NULL,
                    `policy_version` INTEGER NOT NULL,
                    `number_two_digit` TEXT NOT NULL,
                    `side` TEXT NOT NULL,
                    `override_rate_exact` TEXT,
                    `override_receive_cap_minor` INTEGER,
                    `override_payout_cap_minor` INTEGER,
                    `override_is_closed` INTEGER,
                    `override_reason_th` TEXT NOT NULL,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    FOREIGN KEY(`round_id`) REFERENCES `rounds`(`round_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_number_policy_overrides_round_id_policy_version_number_two_digit_side` ON `number_policy_overrides` (`round_id`, `policy_version`, `number_two_digit`, `side`)"
            )

            // 3. Durable Carts, Cart Lines, Quote Snapshots, Durable Operations
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `carts` (
                    `cart_id` TEXT NOT NULL,
                    `round_id` TEXT NOT NULL,
                    `customer_note_th` TEXT NOT NULL,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    `updated_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`cart_id`),
                    FOREIGN KEY(`round_id`) REFERENCES `rounds`(`round_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_carts_round_id` ON `carts` (`round_id`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `cart_lines` (
                    `cart_line_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `cart_id` TEXT NOT NULL,
                    `line_sequence` INTEGER NOT NULL,
                    `number_two_digit` TEXT NOT NULL,
                    `side` TEXT NOT NULL,
                    `amount_minor` INTEGER NOT NULL,
                    `entry_source` TEXT NOT NULL,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    FOREIGN KEY(`cart_id`) REFERENCES `carts`(`cart_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cart_lines_cart_id_line_sequence` ON `cart_lines` (`cart_id`, `line_sequence`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `quote_snapshots` (
                    `quote_id` TEXT NOT NULL,
                    `cart_id` TEXT NOT NULL,
                    `round_id` TEXT NOT NULL,
                    `policy_version` INTEGER NOT NULL,
                    `payload_sha256` TEXT NOT NULL,
                    `total_amount_minor` INTEGER NOT NULL,
                    `line_count` INTEGER NOT NULL,
                    `distinct_number_count` INTEGER NOT NULL,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    `expires_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`quote_id`),
                    FOREIGN KEY(`round_id`) REFERENCES `rounds`(`round_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_quote_snapshots_round_id_policy_version` ON `quote_snapshots` (`round_id`, `policy_version`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_quote_snapshots_payload_sha256` ON `quote_snapshots` (`payload_sha256`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `durable_operations` (
                    `operation_key` TEXT NOT NULL,
                    `operation_type` TEXT NOT NULL,
                    `round_id` TEXT NOT NULL,
                    `policy_version` INTEGER NOT NULL,
                    `quote_id` TEXT,
                    `payload_sha256` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `committed_bill_id` TEXT,
                    `committed_revision_id` TEXT,
                    `human_bill_code` TEXT,
                    `rejection_code` TEXT,
                    `rejection_message_th` TEXT,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    `completed_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`operation_key`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_durable_operations_round_id` ON `durable_operations` (`round_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_durable_operations_committed_bill_id` ON `durable_operations` (`committed_bill_id`)")

            // 4. Bills, Revisions, Persisted Lines, Authoritative Counters
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `bills` (
                    `bill_id` TEXT NOT NULL,
                    `human_bill_code` TEXT NOT NULL,
                    `round_id` TEXT NOT NULL,
                    `active_revision_id` TEXT,
                    `latest_revision_number` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `customer_note_th` TEXT NOT NULL,
                    `cancel_reason_th` TEXT,
                    `cancelled_at_epoch_ms` INTEGER,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    `updated_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`bill_id`),
                    FOREIGN KEY(`round_id`) REFERENCES `rounds`(`round_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bills_human_bill_code` ON `bills` (`human_bill_code`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_round_id_status` ON `bills` (`round_id`, `status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_active_revision_id` ON `bills` (`active_revision_id`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `bill_revisions` (
                    `revision_id` TEXT NOT NULL,
                    `bill_id` TEXT NOT NULL,
                    `revision_number` INTEGER NOT NULL,
                    `round_id` TEXT NOT NULL,
                    `policy_version` INTEGER NOT NULL,
                    `operation_key` TEXT NOT NULL,
                    `total_amount_minor` INTEGER NOT NULL,
                    `total_provisional_payout_minor` INTEGER NOT NULL,
                    `line_count` INTEGER NOT NULL,
                    `distinct_number_count` INTEGER NOT NULL,
                    `revision_reason_th` TEXT NOT NULL,
                    `created_by_actor_id` TEXT NOT NULL,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`revision_id`),
                    FOREIGN KEY(`bill_id`) REFERENCES `bills`(`bill_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`round_id`) REFERENCES `rounds`(`round_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bill_revisions_bill_id_revision_number` ON `bill_revisions` (`bill_id`, `revision_number`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bill_revisions_round_id_policy_version` ON `bill_revisions` (`round_id`, `policy_version`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bill_revisions_operation_key` ON `bill_revisions` (`operation_key`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `bill_number_side_lines` (
                    `bill_line_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `revision_id` TEXT NOT NULL,
                    `bill_id` TEXT NOT NULL,
                    `round_id` TEXT NOT NULL,
                    `line_number` INTEGER NOT NULL,
                    `number_two_digit` TEXT NOT NULL,
                    `side` TEXT NOT NULL,
                    `amount_minor` INTEGER NOT NULL,
                    `rate_snapshot_exact` TEXT NOT NULL,
                    `provisional_payout_minor` INTEGER NOT NULL,
                    `rounding_mode` TEXT NOT NULL,
                    FOREIGN KEY(`revision_id`) REFERENCES `bill_revisions`(`revision_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`bill_id`) REFERENCES `bills`(`bill_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bill_number_side_lines_revision_id_line_number` ON `bill_number_side_lines` (`revision_id`, `line_number`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bill_number_side_lines_bill_id` ON `bill_number_side_lines` (`bill_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bill_number_side_lines_round_id_number_two_digit_side` ON `bill_number_side_lines` (`round_id`, `number_two_digit`, `side`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `number_side_counters` (
                    `round_id` TEXT NOT NULL,
                    `number_two_digit` TEXT NOT NULL,
                    `side` TEXT NOT NULL,
                    `active_receive_total_minor` INTEGER NOT NULL,
                    `active_payout_total_minor` INTEGER NOT NULL,
                    `active_line_count` INTEGER NOT NULL,
                    `updated_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`round_id`, `number_two_digit`, `side`),
                    FOREIGN KEY(`round_id`) REFERENCES `rounds`(`round_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )

            // 5. Result Revisions & Winning Entitlements
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `result_revisions` (
                    `result_revision_id` TEXT NOT NULL,
                    `round_id` TEXT NOT NULL,
                    `revision_number` INTEGER NOT NULL,
                    `winning_top_two_digit` TEXT NOT NULL,
                    `winning_bottom_two_digit` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `approval_id` TEXT,
                    `reason_th` TEXT NOT NULL,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`result_revision_id`),
                    FOREIGN KEY(`round_id`) REFERENCES `rounds`(`round_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_result_revisions_round_id_revision_number` ON `result_revisions` (`round_id`, `revision_number`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_result_revisions_round_id_status` ON `result_revisions` (`round_id`, `status`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `winning_entitlements` (
                    `entitlement_id` TEXT NOT NULL,
                    `round_id` TEXT NOT NULL,
                    `result_revision_id` TEXT NOT NULL,
                    `bill_id` TEXT NOT NULL,
                    `bill_revision_id` TEXT NOT NULL,
                    `bill_line_id` INTEGER NOT NULL,
                    `number_two_digit` TEXT NOT NULL,
                    `side` TEXT NOT NULL,
                    `principal_amount_minor` INTEGER NOT NULL,
                    `rate_snapshot_exact` TEXT NOT NULL,
                    `entitlement_payout_minor` INTEGER NOT NULL,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`entitlement_id`),
                    FOREIGN KEY(`result_revision_id`) REFERENCES `result_revisions`(`result_revision_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`bill_id`) REFERENCES `bills`(`bill_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`bill_revision_id`) REFERENCES `bill_revisions`(`revision_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_winning_entitlements_result_revision_id_bill_line_id` ON `winning_entitlements` (`result_revision_id`, `bill_line_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_winning_entitlements_round_id_bill_id` ON `winning_entitlements` (`round_id`, `bill_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_winning_entitlements_bill_revision_id` ON `winning_entitlements` (`bill_revision_id`)")

            // 6. Ledger Accounts, Transactions, Entries, Payments, Allocations
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ledger_accounts` (
                    `account_id` TEXT NOT NULL,
                    `account_code` TEXT NOT NULL,
                    `account_name_th` TEXT NOT NULL,
                    `account_scope` TEXT NOT NULL,
                    `currency_code` TEXT NOT NULL,
                    `is_demo_chart` INTEGER NOT NULL,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`account_id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ledger_accounts_account_code` ON `ledger_accounts` (`account_code`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ledger_transactions` (
                    `transaction_id` TEXT NOT NULL,
                    `operation_key` TEXT NOT NULL,
                    `round_id` TEXT,
                    `currency_code` TEXT NOT NULL,
                    `account_scope` TEXT NOT NULL,
                    `total_balanced_minor` INTEGER NOT NULL,
                    `reverses_transaction_id` TEXT,
                    `memo_th` TEXT NOT NULL,
                    `posted_by_actor_id` TEXT NOT NULL,
                    `posted_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`transaction_id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_transactions_round_id` ON `ledger_transactions` (`round_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_transactions_reverses_transaction_id` ON `ledger_transactions` (`reverses_transaction_id`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ledger_transactions_operation_key` ON `ledger_transactions` (`operation_key`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ledger_entries` (
                    `entry_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `transaction_id` TEXT NOT NULL,
                    `entry_sequence` INTEGER NOT NULL,
                    `account_id` TEXT NOT NULL,
                    `direction` TEXT NOT NULL,
                    `amount_minor` INTEGER NOT NULL,
                    `currency_code` TEXT NOT NULL,
                    `account_scope` TEXT NOT NULL,
                    FOREIGN KEY(`transaction_id`) REFERENCES `ledger_transactions`(`transaction_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`account_id`) REFERENCES `ledger_accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ledger_entries_transaction_id_entry_sequence` ON `ledger_entries` (`transaction_id`, `entry_sequence`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_entries_account_id` ON `ledger_entries` (`account_id`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `payment_records` (
                    `payment_id` TEXT NOT NULL,
                    `round_id` TEXT NOT NULL,
                    `payment_direction` TEXT NOT NULL,
                    `amount_minor` INTEGER NOT NULL,
                    `allocated_total_minor` INTEGER NOT NULL,
                    `ledger_transaction_id` TEXT NOT NULL,
                    `evidence_note_th` TEXT NOT NULL,
                    `recorded_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`payment_id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_records_round_id` ON `payment_records` (`round_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_records_ledger_transaction_id` ON `payment_records` (`ledger_transaction_id`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `payment_allocations` (
                    `allocation_id` TEXT NOT NULL,
                    `payment_id` TEXT NOT NULL,
                    `target_type` TEXT NOT NULL,
                    `target_id` TEXT NOT NULL,
                    `allocated_amount_minor` INTEGER NOT NULL,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`allocation_id`),
                    FOREIGN KEY(`payment_id`) REFERENCES `payment_records`(`payment_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_allocations_payment_id` ON `payment_allocations` (`payment_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_allocations_target_type_target_id` ON `payment_allocations` (`target_type`, `target_id`)")

            // 7. Counterparties, Contracts, Manual Transfers, Responses, Backup Metadata
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `counterparties` (
                    `counterparty_id` TEXT NOT NULL,
                    `counterparty_code` TEXT NOT NULL,
                    `display_name_th` TEXT NOT NULL,
                    `is_active` INTEGER NOT NULL,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`counterparty_id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_counterparties_counterparty_code` ON `counterparties` (`counterparty_code`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `counterparty_contracts` (
                    `contract_id` TEXT NOT NULL,
                    `counterparty_id` TEXT NOT NULL,
                    `round_id` TEXT NOT NULL,
                    `contract_top_rate_exact` TEXT NOT NULL,
                    `contract_bottom_rate_exact` TEXT NOT NULL,
                    `max_transfer_cap_minor` INTEGER NOT NULL,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`contract_id`),
                    FOREIGN KEY(`counterparty_id`) REFERENCES `counterparties`(`counterparty_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`round_id`) REFERENCES `rounds`(`round_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_counterparty_contracts_counterparty_id_round_id` ON `counterparty_contracts` (`counterparty_id`, `round_id`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `manual_transfer_batches` (
                    `batch_id` TEXT NOT NULL,
                    `round_id` TEXT NOT NULL,
                    `contract_id` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `total_submitted_minor` INTEGER NOT NULL,
                    `total_accepted_minor` INTEGER NOT NULL,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`batch_id`),
                    FOREIGN KEY(`contract_id`) REFERENCES `counterparty_contracts`(`contract_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`round_id`) REFERENCES `rounds`(`round_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_manual_transfer_batches_round_id_status` ON `manual_transfer_batches` (`round_id`, `status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_manual_transfer_batches_contract_id` ON `manual_transfer_batches` (`contract_id`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `manual_transfer_lines` (
                    `transfer_line_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `batch_id` TEXT NOT NULL,
                    `round_id` TEXT NOT NULL,
                    `number_two_digit` TEXT NOT NULL,
                    `side` TEXT NOT NULL,
                    `submitted_amount_minor` INTEGER NOT NULL,
                    `accepted_amount_minor` INTEGER NOT NULL,
                    `contract_rate_exact` TEXT NOT NULL,
                    `accepted_recoverable_payout_minor` INTEGER NOT NULL,
                    `line_status` TEXT NOT NULL,
                    FOREIGN KEY(`batch_id`) REFERENCES `manual_transfer_batches`(`batch_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_manual_transfer_lines_batch_id_number_two_digit_side` ON `manual_transfer_lines` (`batch_id`, `number_two_digit`, `side`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `transfer_responses` (
                    `response_id` TEXT NOT NULL,
                    `batch_id` TEXT NOT NULL,
                    `response_status` TEXT NOT NULL,
                    `manual_reference_note_th` TEXT NOT NULL,
                    `recorded_by_actor_id` TEXT NOT NULL,
                    `recorded_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`response_id`),
                    FOREIGN KEY(`batch_id`) REFERENCES `manual_transfer_batches`(`batch_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfer_responses_batch_id` ON `transfer_responses` (`batch_id`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `backup_metadata` (
                    `archive_id` TEXT NOT NULL,
                    `schema_version` INTEGER NOT NULL,
                    `archive_format_version` INTEGER NOT NULL,
                    `wal_checkpoint_completed` INTEGER NOT NULL,
                    `payload_sha256` TEXT NOT NULL,
                    `action_type` TEXT NOT NULL,
                    `note_th` TEXT NOT NULL,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`archive_id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_backup_metadata_archive_id` ON `backup_metadata` (`archive_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_backup_metadata_created_at_epoch_ms` ON `backup_metadata` (`created_at_epoch_ms`)")
        }
    }
}
