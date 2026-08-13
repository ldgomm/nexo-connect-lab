package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.AdvanceDurableReceiptCursorRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.AdvanceDurableReceiptCursorResult
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableReceiptCursorRepository
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.LoadDurableReceiptCursorsRequest
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.LoadDurableReceiptCursorsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DurableReceiptCursorService(
    private val repository: DurableReceiptCursorRepository,
    private val messageCoordinator: DurableTextMessageCoordinator?,
) {
    suspend fun advance(
        request: AdvanceDurableReceiptCursorRequest,
    ): AdvanceDurableReceiptCursorResult =
        synchronize(request.conversationRef) {
            withContext(Dispatchers.IO) { repository.advance(request) }
        }

    suspend fun load(
        request: LoadDurableReceiptCursorsRequest,
    ): LoadDurableReceiptCursorsResult = withContext(Dispatchers.IO) { repository.load(request) }

    private suspend fun <T> synchronize(
        conversationRef: String,
        block: suspend () -> T,
    ): T =
        messageCoordinator?.synchronizeConversation(conversationRef, block) ?: block()
}
