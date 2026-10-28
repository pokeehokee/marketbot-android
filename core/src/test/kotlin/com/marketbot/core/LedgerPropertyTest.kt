package com.marketbot.core

import com.marketbot.core.store.LedgerChecks
import com.marketbot.core.store.inMemoryStores
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerPropertyTest {
    @Test fun `random histories never mix modes, double count or realise hypothetical P&L`() = runTest {
        for (seed in 1..40) {
            val l = inMemoryStores().ledger
            val ids = LedgerChecks.randomHistory(l, seed)
            assertEquals(emptyList(), LedgerChecks.violations(l, ids), "seed $seed")
        }
    }
}
