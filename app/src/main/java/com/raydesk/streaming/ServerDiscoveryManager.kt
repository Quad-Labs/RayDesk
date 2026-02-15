package com.raydesk.streaming

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.limelight.binding.PlatformBinding
import com.limelight.discovery.DiscoveryService
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.mdns.MdnsComputer
import com.limelight.nvstream.mdns.MdnsDiscoveryListener
import com.raydesk.data.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages discovery of Moonlight/Sunshine streaming servers on the network.
 *
 * Wraps Moonlight's [DiscoveryService] for mDNS discovery and provides manual
 * IP probing via [NvHTTP]. Discovered servers are emitted as a [Flow] that
 * updates in real-time as servers are found or lost.
 *
 * Usage:
 * ```kotlin
 * val manager = ServerDiscoveryManager(context)
 *
 * // Auto-discover servers via mDNS
 * lifecycleScope.launch {
 *     manager.startDiscovery().collect { servers ->
 *         updateServerList(servers)
 *     }
 * }
 *
 * // Manual probe for specific IP
 * val server = manager.probeServer("192.168.1.100")
 *
 * // Stop discovery when done
 * manager.stopDiscovery()
 * ```
 */
class ServerDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "ServerDiscoveryManager"
        private const val DISCOVERY_INTERVAL_MS = 1000
    }

    private val serverRepository = ServerRepository(context)
    private val cryptoProvider by lazy { PlatformBinding.getCryptoProvider(context) }

    // Coroutine scope for background tasks (probing discovered servers)
    // Recreated on each discovery session, null when not discovering
    private var scope: CoroutineScope? = null

    // State tracking
    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    private val serverMap = mutableMapOf<String, DiscoveredServer>()

    // Service binding
    private var discoveryBinder: DiscoveryService.DiscoveryBinder? = null
    private val isBound = AtomicBoolean(false)
    private val discoveryActive = AtomicBoolean(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            discoveryBinder = service as? DiscoveryService.DiscoveryBinder

            discoveryBinder?.setListener(discoveryListener)
            if (discoveryActive.get()) {
                discoveryBinder?.startDiscovery(DISCOVERY_INTERVAL_MS)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            discoveryBinder = null
        }
    }

    private val discoveryListener = object : MdnsDiscoveryListener {
        override fun notifyComputerAdded(computer: MdnsComputer) {
            handleComputerDiscovered(computer)
        }

        override fun notifyDiscoveryFailure(e: Exception) {
            Log.e(TAG, "mDNS discovery failure", e)
        }
    }

    /**
     * Starts mDNS discovery and returns a [Flow] of discovered servers.
     *
     * The flow emits an updated list whenever servers are found or lost.
     * Discovery continues until [stopDiscovery] is called.
     *
     * @return Flow of discovered servers, updated in real-time
     */
    fun startDiscovery(): Flow<List<DiscoveredServer>> = callbackFlow {
        discoveryActive.set(true)

        // Cancel any existing scope from previous discovery session to prevent coroutine leaks
        scope?.cancel()
        // Create fresh scope for this discovery session
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Bind to discovery service
        if (!isBound.get()) {
            val intent = Intent(context, DiscoveryService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            isBound.set(true)
        }

        // Start discovery if already bound
        discoveryBinder?.startDiscovery(DISCOVERY_INTERVAL_MS)

        // Emit current state and subscribe to updates
        trySend(_servers.value)
        val job = scope?.launch {
            _servers.collect { servers ->
                trySend(servers)
            }
        }

        awaitClose {
            job?.cancel()
            scope?.cancel()
            scope = null
        }
    }

    /**
     * Alternative: Get servers as a StateFlow for easier collection.
     *
     * Use [startDiscovery] to begin discovery first.
     */
    fun getServersFlow(): Flow<List<DiscoveredServer>> = _servers.asStateFlow()

    /**
     * Stops mDNS discovery and releases resources.
     *
     * Call this when discovery is no longer needed to conserve battery.
     */
    fun stopDiscovery() {
        discoveryActive.set(false)
        discoveryBinder?.stopDiscovery()

        if (isBound.get()) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                // Service was not bound
                Log.w(TAG, "Service not bound when stopping discovery")
            }
            isBound.set(false)
        }

        // Cancel any pending probes
        scope?.cancel()
        scope = null

    }

    /**
     * Manually probe a specific IP address for a Moonlight/Sunshine server.
     *
     * Use this when mDNS discovery fails (e.g., different subnets or firewalls).
     *
     * @param address IP address or hostname to probe
     * @param port HTTP port (default 47989)
     * @return [DiscoveredServer] if found, null otherwise
     */
    suspend fun probeServer(address: String, port: Int = NvHTTP.DEFAULT_HTTP_PORT): DiscoveredServer? {
        return withContext(Dispatchers.IO) {
            try {

                val addressTuple = ComputerDetails.AddressTuple(address, port)
                val nvHttp = NvHTTP(
                    addressTuple,
                    0,  // httpsPort - will be determined from serverinfo
                    null,  // uniqueId - not needed for probe
                    null,  // serverCert - not paired yet
                    cryptoProvider
                )

                // Get server info via HTTP
                val serverInfo = nvHttp.getServerInfo(false)
                val details = nvHttp.getComputerDetails(serverInfo)

                // Check if we have saved credentials for this server
                val isPaired = serverRepository.getServer(details.uuid) != null

                val server = DiscoveredServer(
                    uuid = details.uuid ?: "",
                    name = details.name ?: "Unknown",
                    address = address,
                    port = port,
                    isOnline = details.state == ComputerDetails.State.ONLINE,
                    isPaired = isPaired
                )

                // Add to discovered servers
                addServer(server)

                server
            } catch (e: Exception) {
                Log.e(TAG, "Failed to probe server at $address:$port", e)
                null
            }
        }
    }

    /**
     * Clears all discovered servers from the list.
     */
    fun clearServers() {
        synchronized(serverMap) {
            serverMap.clear()
            _servers.value = emptyList()
        }
    }

    /**
     * Gets the current list of discovered servers.
     */
    fun getServers(): List<DiscoveredServer> = _servers.value

    // ==================== Private Methods ====================

    private fun handleComputerDiscovered(computer: MdnsComputer) {
        // Get the best address (prefer IPv4)
        val address = computer.localAddress?.hostAddress
            ?: computer.ipv6Address?.hostAddress
            ?: return

        // Create a basic server entry from mDNS data
        // We don't have UUID from mDNS, so use name as temporary key
        val tempUuid = "mdns-${computer.name}-$address"

        val server = DiscoveredServer(
            uuid = tempUuid,
            name = computer.name,
            address = address,
            port = computer.port,
            isOnline = true,
            isPaired = false  // Will be updated after probe
        )

        addServer(server)

        // Schedule a probe to get full details (UUID, pair state)
        scope?.launch {
            try {
                val probed = probeServer(address, computer.port)
                if (probed != null) {
                    // Remove temporary entry and add probed entry
                    removeServer(tempUuid)
                    addServer(probed)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to probe discovered server ${computer.name}", e)
            }
        }
    }

    // Made internal for testing - server management logic
    internal fun addServer(server: DiscoveredServer) {
        synchronized(serverMap) {
            serverMap[server.uuid] = server
            _servers.value = serverMap.values.toList().sortedBy { it.name }
        }
    }

    internal fun removeServer(uuid: String) {
        synchronized(serverMap) {
            serverMap.remove(uuid)
            _servers.value = serverMap.values.toList().sortedBy { it.name }
        }
    }
}
