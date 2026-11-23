package com.arkade.core

import com.ionspin.kotlin.bignum.decimal.DecimalMode
import com.ionspin.kotlin.bignum.decimal.RoundingMode
import com.ionspin.kotlin.bignum.decimal.toBigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class UtilsTest {
    @Test
    fun should_normalize_mixed_decimal_modes_to_unlimited_decimal_precision_in_sum_of_big_decimals() {
        val decimalMode = DecimalMode(roundingMode = RoundingMode.FLOOR, scale = 2)
        val bigDecimals = listOf(21.212.toBigDecimal(decimalMode = decimalMode), 0.445.toBigDecimal())
        val sum = bigDecimals.sumOf { it }
        assertEquals(21.655.toBigDecimal(), sum)
    }
}
