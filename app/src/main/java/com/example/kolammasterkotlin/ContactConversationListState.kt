package com.kolammaster.app

internal fun shouldRevealContactConversationPromotion(
    previousOrder: List<String>?,
    currentOrder: List<String>
): Boolean =
    previousOrder != null &&
        previousOrder.isNotEmpty() &&
        currentOrder.isNotEmpty() &&
        previousOrder != currentOrder &&
        (
            previousOrder.filter(currentOrder::contains) !=
                currentOrder.filter(previousOrder::contains) ||
                previousOrder.first() != currentOrder.first()
            )
