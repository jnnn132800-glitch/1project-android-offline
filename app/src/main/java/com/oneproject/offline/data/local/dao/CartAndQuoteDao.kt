package com.oneproject.offline.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.oneproject.offline.data.local.entity.CartEntity
import com.oneproject.offline.data.local.entity.CartLineEntity
import com.oneproject.offline.data.local.entity.QuoteSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CartAndQuoteDao {

    @Query("SELECT * FROM carts WHERE cart_id = :cartId LIMIT 1")
    suspend fun getCartById(cartId: String): CartEntity?

    @Query("SELECT * FROM carts WHERE round_id = :roundId ORDER BY updated_at_epoch_ms DESC LIMIT 1")
    fun observeCartForRound(roundId: String): Flow<CartEntity?>

    @Query("SELECT * FROM cart_lines WHERE cart_id = :cartId ORDER BY line_sequence ASC")
    fun observeCartLines(cartId: String): Flow<List<CartLineEntity>>

    @Query("SELECT * FROM cart_lines WHERE cart_id = :cartId ORDER BY line_sequence ASC")
    suspend fun getCartLines(cartId: String): List<CartLineEntity>

    @Query("SELECT COALESCE(MAX(line_sequence), 0) FROM cart_lines WHERE cart_id = :cartId")
    suspend fun getMaxLineSequence(cartId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCart(cart: CartEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCartLines(lines: List<CartLineEntity>)

    @Query(
        """
        UPDATE cart_lines
        SET amount_minor = :newAmountMinor
        WHERE cart_id = :cartId AND cart_line_id = :cartLineId
        """
    )
    suspend fun updateCartLineAmount(
        cartId: String,
        cartLineId: Long,
        newAmountMinor: Long
    )

    @Query("DELETE FROM cart_lines WHERE cart_id = :cartId AND cart_line_id = :cartLineId")
    suspend fun deleteCartLine(cartId: String, cartLineId: Long)

    @Query("DELETE FROM cart_lines WHERE cart_id = :cartId")
    suspend fun clearCartLines(cartId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertQuoteSnapshot(quote: QuoteSnapshotEntity)

    @Query("SELECT * FROM quote_snapshots WHERE quote_id = :quoteId LIMIT 1")
    suspend fun getQuoteSnapshotById(quoteId: String): QuoteSnapshotEntity?

    /**
     * เพิ่มรายการลงตะกร้าแบบทนต่อการปิดแอป (Durable Cart Append)
     * กฎ D03: รายการซ้ำในตะกร้าไม่ถูกยุบรวมเงียบๆ แต่เก็บแยกบรรทัดตามลำดับที่ผู้ใช้เพิ่ม
     */
    @Transaction
    suspend fun appendLinesToDurableCart(
        cartId: String,
        roundId: String,
        customerNoteTh: String,
        newLines: List<CartLineDraft>,
        nowEpochMs: Long
    ) {
        val existingCart = getCartById(cartId)
        if (existingCart == null) {
            upsertCart(
                CartEntity(
                    cartId = cartId,
                    roundId = roundId,
                    customerNoteTh = customerNoteTh,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs
                )
            )
        } else {
            upsertCart(
                existingCart.copy(
                    customerNoteTh = customerNoteTh,
                    updatedAtEpochMs = nowEpochMs
                )
            )
        }

        val baseSeq = getMaxLineSequence(cartId)
        val entities = newLines.mapIndexed { idx, draft ->
            require(draft.amountMinor > 0L) { "ยอดเงินต่อรายการต้องมากกว่า 0 สตางค์" }
            CartLineEntity(
                cartId = cartId,
                lineSequence = baseSeq + idx + 1,
                numberTwoDigit = draft.numberTwoDigit,
                side = draft.side,
                amountMinor = draft.amountMinor,
                entrySource = draft.entrySource,
                createdAtEpochMs = nowEpochMs
            )
        }
        insertCartLines(entities)
    }
}

data class CartLineDraft(
    val numberTwoDigit: String,
    val side: String,
    val amountMinor: Long,
    val entrySource: String
)
