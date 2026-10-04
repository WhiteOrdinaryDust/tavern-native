package com.tavern.domain.util

/**
 * Python 的 `round()` 是「四舍六入五成双」（银行家舍入）。
 * 统计百分比要和 Flet 版逐位一致，所以这里照搬同样的语义
 * （`Math.rint` 就是 IEEE 的 round-half-even）。
 */
fun pyRound(value: Double): Int = Math.rint(value).toInt()

fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0
