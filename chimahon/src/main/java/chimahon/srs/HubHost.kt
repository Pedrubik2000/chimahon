package chimahon.srs

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import chimahon.srs.hub.HubServer
import chimahon.srs.hub.HubStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * "This device is the main server": kumahon answers kumapie's device sync ([HubServer], port 47823) while the app process lives,
 * announces itself over NSD like kumapie does, and syncs its own cards to itself through the normal [PeerSync] client
 * (hub 127.0.0.1:47823, its own pairing made locally), so its data takes the same merge path as every other device's.
 * The setting lives in the "kumahon_peer" prefs ([HUB_ON]); the merged state in files/hub/hub.json, kept when switched off.
 */
object HubHost {
    private const val HUB_ON = "hub_on"
    private const val HUB_PREV = "hub_prev"
    private const val HUB_PREV_NAME = "hub_prev_name"
    private const val SERVICE = "_kumapiesync._tcp."

    @Volatile private var server: HubServer? = null
    private var nsd: NsdManager.RegistrationListener? = null
    @Volatile private var problem: String? = null

    /** A pairing request waiting for a tap; [known] = that name is already paired (Allow replaces its pairing). */
    data class Ask(val device: String, val code: String, val known: Boolean)

    /** Pairing requests waiting for a tap. The app shows an Allow / Deny dialog for the first. */
    val asking = MutableStateFlow<List<Ask>>(emptyList())

    fun enabled(context: Context): Boolean = AndroidPeerPrefs(context).get(HUB_ON) == "true"

    /** Switches the setting and applies it now (in the background). */
    fun setEnabled(context: Context, on: Boolean) {
        AndroidPeerPrefs(context).put(HUB_ON, on.toString())
        apply(context)
    }

    /** Makes the server match the setting: call at app start and after a change. Never throws, never blocks the caller. */
    fun apply(context: Context) {
        val app = context.applicationContext
        Cards.later { synchronized(this) { if (enabled(app)) start(app) else stop(app) } }
    }

    /** Writes the pairing file: call off the main thread. */
    fun allow(device: String) { server?.allow(device); refresh() }

    fun deny(device: String) { server?.deny(device); refresh() }

    /** Drops requests whose device stopped asking (20 s), so a closed pairing screen on the other side clears the dialog. */
    fun refresh() { server?.let { s -> asking.value = s.waiting().map { Ask(it.device, it.code, s.store.secretOf(it.device) != null) } } }

    /** The setting's subtitle: the address to type into kumapie, or why it is not running. */
    fun subtitle(context: Context): String {
        if (!enabled(context)) return "Off: this device is not a sync server"
        problem?.let { return it }
        val s = server ?: return "Starting..."
        val ip = HubScan.localAddresses().firstOrNull()
        return "Answering kumapie devices at ${ip ?: "this device's address"}:${s.localPort} as \"${s.store.hubName}\" (while kumahon is open)"
    }

    private fun start(app: Context) {
        if (server != null) return
        val peer = Cards.peer(app)
        val name = peer.deviceName()
        val store = HubStore(File(app.filesDir, "hub/hub.json").also { it.parentFile?.mkdirs() }, name) { Log.w("kumahon", it) }
        val s = HubServer(store, onPairRequest = { _, _ -> refresh() }, log = { Log.i("kumahon", it) })
        try {
            s.start()
        } catch (e: Exception) {
            Log.w("kumahon", "hub: can't listen on ${HubServer.PORT}: $e")
            problem = "Can't start: port ${HubServer.PORT} is taken (kumapie on this device answering other devices? Turn that off there). ${e.message.orEmpty()}".trim()
            return
        }
        problem = null
        server = s
        register(app, name, s.localPort)
        // kumahon's own cards sync to its own hub through PeerSync, paired HERE and never over HTTP (the hub's name is public: the server
        // refuses /pair under it). Same secret as before when there is one (kumapie knew it too), else a new one.
        val prefs = AndroidPeerPrefs(app)
        if (!PeerSync.isLocalHub(peer.hub())) { prefs.put(HUB_PREV, peer.hub()); prefs.put(HUB_PREV_NAME, prefs.get(PeerSync.NAME)) }
        peer.setHub("127.0.0.1:${s.localPort}")
        val secret = prefs.get(PeerSync.SECRET) ?: ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.put(PeerSync.SECRET, secret)
        store.addPeer(name, secret)
        prefs.put(PeerSync.PAIRED, "true"); prefs.put(PeerSync.NAME, name)
    }

    private fun stop(app: Context) {
        server?.stop(); server = null; problem = null; asking.value = emptyList()
        nsd?.let { r -> runCatching { app.getSystemService(NsdManager::class.java).unregisterService(r) } }
        nsd = null
        // back to the hub kumahon had before (kumapie's address), if it had one
        val prefs = AndroidPeerPrefs(app)
        prefs.get(HUB_PREV)?.let {
            Cards.peer(app).setHub(it); prefs.put(PeerSync.NAME, prefs.get(HUB_PREV_NAME)); prefs.put(HUB_PREV, null); prefs.put(HUB_PREV_NAME, null)
        }
    }

    private fun register(app: Context, name: String, port: Int) {
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Log.w("kumahon", "hub: nsd $e") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        val info = NsdServiceInfo().apply { serviceName = name; serviceType = SERVICE; this.port = port }
        runCatching { app.getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, reg); nsd = reg }
    }
}
