package com.arkade.utils

private const val RESET = "\u001B[0m"
private const val BLUE = "\u001B[34m"
private const val GREEN = "\u001B[32m"
private const val YELLOW = "\u001B[33m"
private const val RED = "\u001B[31m"

actual fun Log.debug(
    tag: String,
    message: String,
) = println("$GREEN D/$tag: $message$RESET")

actual fun Log.info(
    tag: String,
    message: String,
) = println("I/$tag: $message")

actual fun Log.warning(
    tag: String,
    message: String,
) = println("$YELLOW W/$tag: $message$RESET")

actual fun Log.error(
    tag: String,
    message: String,
) {
    System.err.println("$RED E/$tag: $message$RESET")
}

actual fun Log.verbose(
    tag: String,
    message: String,
) = println("$BLUE V/$tag: $message$RESET")
