package com.kolammaster.app

internal fun orderContactConversations(
    conversations: List<ContactConversation>,
    unreadConversationIds: Set<String>
): List<ContactConversation> = conversations.sortedWith(
    compareByDescending<ContactConversation> { it.id in unreadConversationIds }
        .thenByDescending { conversation ->
            conversation.latestMessageCreatedAt
                ?.takeIf(String::isNotBlank)
                ?: conversation.updatedAt.takeIf(String::isNotBlank)
                ?: conversation.createdAt
        }
)
