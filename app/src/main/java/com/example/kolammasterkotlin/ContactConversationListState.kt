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

internal fun shouldRefreshContactConversationList(
    hasPendingPushFallback: Boolean,
    explicitRefreshRequested: Boolean
): Boolean =
    hasPendingPushFallback || explicitRefreshRequested
