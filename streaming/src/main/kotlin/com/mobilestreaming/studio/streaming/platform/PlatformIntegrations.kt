package com.mobilestreaming.studio.streaming.platform

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Platform APIs are deliberately outside the generic RTMP engine. */
data class PlatformChatMessage(
    val id: String,
    val author: String,
    val text: String,
    val sentAtEpochMs: Long,
)

sealed interface PlatformConnectionState {
    data object Disconnected : PlatformConnectionState
    data object RequiresAuthorization : PlatformConnectionState
    data class Connected(val accountName: String) : PlatformConnectionState
    data class Error(val message: String) : PlatformConnectionState
}

interface StreamPlatformIntegration {
    val connectionState: StateFlow<PlatformConnectionState>
    val chatMessages: SharedFlow<PlatformChatMessage>
    val viewerCount: StateFlow<Long?>

    /** Implementations must use an official API, approved OAuth flow and documented scopes. */
    suspend fun disconnect()
}
