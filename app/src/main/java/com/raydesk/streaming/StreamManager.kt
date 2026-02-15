package com.raydesk.streaming

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Manages streaming connections to host PCs.
 * Wraps Moonlight's NvConnection for a cleaner API.
 */
class StreamManager {

    private val _state = MutableStateFlow<StreamState>(StreamState.Disconnected)

    /**
     * Current connection state.
     */
    val currentState: StreamState
        get() = _state.value

    /**
     * Connect to a streaming server.
     *
     * @param host The server hostname or IP address
     * @param config Streaming configuration
     * @return Flow of connection states
     */
    fun connect(host: String, config: StreamConfig): Flow<StreamState> {
        _state.value = StreamState.Connecting
        return _state.asStateFlow()
    }

    /**
     * Disconnect from the current server.
     */
    fun disconnect() {
        _state.value = StreamState.Disconnected
    }

    /**
     * Get a flow of discovered servers on the network.
     */
    fun getDiscoveredServers(): Flow<List<ServerInfo>> {
        return flowOf(emptyList())
    }
}
