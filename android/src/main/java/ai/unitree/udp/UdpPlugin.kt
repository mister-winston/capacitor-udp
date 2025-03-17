package ai.unitree.udp

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Base64
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.NativePlugin
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import org.json.JSONException
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketException
import java.net.StandardSocketOptions
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.Collections
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

@NativePlugin
class UdpPlugin : Plugin() {
    private val sockets: MutableMap<Int, UdpSocket> = ConcurrentHashMap()
    private val selectorMessages: BlockingQueue<SelectorMessage?> = LinkedBlockingQueue()
    private var nextSocket = 0
    private var selector: Selector? = null
    private var selectorThread: SelectorThread? = null


    private val dataForwardReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val socketId = intent.getIntExtra("socketId", -1)
            val address = intent.getStringExtra("address")
            val port = intent.getIntExtra("port", -1)
            val data = intent.getByteArrayExtra("data")
            try {
                val socket = obtainSocket(socketId)
                if (!socket.isBound) throw Exception("Not bound yet")
                socket.addSendPacket(address, port, data, null)
                addSelectorMessage(socket, SelectorMessageType.SO_ADD_WRITE_INTEREST, null)
            } catch (e: Exception) {
            }
        }
    }

    override fun handleOnStart() {
        startSelectorThread()
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(dataForwardReceiver, IntentFilter("capacitor-udp-forward"))
    }

    override fun handleOnStop() {
        Log.i("lifecycle", "stop")
        stopSelectorThread()
    }

    override fun handleOnRestart() {
        Log.i("lifecycle", "restart")
        startSelectorThread()
    }


    @PluginMethod
    fun create(call: PluginCall) {
        try {
            val properties = call.getObject("properties", JSObject())!!
            val socket = UdpSocket(nextSocket++, properties)
            sockets[socket.socketId] = socket
            val ret = JSObject()
            ret.put("socketId", socket.socketId)
            ret.put("ipv4", socket.ipv4Address.hostAddress)
            val ipv6 = socket.ipv6Address.hostAddress ?: ""
            val ip6InterfaceIndex = ipv6.indexOf("%")
            if (ip6InterfaceIndex > 0) {
                ret.put("ipv6", ipv6.substring(0, ip6InterfaceIndex))
            } else {
                ret.put("ipv6", ipv6)
            }

            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("create error", e)
        }
    }

    @Throws(Exception::class)
    private fun obtainSocket(socketId: Int): UdpSocket {
        val socket = sockets[socketId]
            ?: throw Exception("No socket with socketId $socketId")
        return socket
    }

    @PluginMethod
    fun update(call: PluginCall) {
        try {
            val socketId = call.getInt("socketId")!!
            val properties = call.getObject("properties")
            val socket = obtainSocket(socketId)
            socket.setProperties(properties)
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message)
        }
    }

    @PluginMethod
    fun setPaused(call: PluginCall) {
        val socketId = call.getInt("socketId")!!
        val paused = call.getBoolean("paused")!!
        try {
            val socket = obtainSocket(socketId)
            socket.setPaused(paused)
            if (paused) {
                // Read interest will be removed when socket is readable on selector thread.
                call.resolve()
            } else {
                addSelectorMessage(socket, SelectorMessageType.SO_ADD_READ_INTEREST, call)
            }
        } catch (e: Exception) {
            call.reject(e.message)
        }
    }

    @PluginMethod
    fun bind(call: PluginCall) {
        val socketId = call.getInt("socketId")!!
        val address = call.getString("address")
        val port = call.getInt("port")!!
        try {
            val socket = obtainSocket(socketId)
            socket.bind(address, port)
            addSelectorMessage(socket, SelectorMessageType.SO_BIND, call)
        } catch (e: Exception) {
            call.reject(e.message)
        }
    }

    @PluginMethod
    fun send(call: PluginCall) {
        try {
            val socketId = call.getInt("socketId")!!
            val address = call.getString("address")
            val port = call.getInt("port")!!
            val bufferString = call.getString("buffer")
            val data = Base64.decode(bufferString, Base64.DEFAULT)
            val socket = obtainSocket(socketId)
            if (!socket.isBound) throw Exception("Not bound yet")
            socket.addSendPacket(address, port, data, call)
            addSelectorMessage(socket, SelectorMessageType.SO_ADD_WRITE_INTEREST, null)
        } catch (e: Exception) {
            call.reject(e.message)
        }
    }

    @PluginMethod
    fun closeAllSockets(call: PluginCall) {
        try {
            for (socket in sockets.values) {
                addSelectorMessage(socket, SelectorMessageType.SO_CLOSE, null)
                sockets.remove(socket.socketId)
            }
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message)
        }
    }

    @PluginMethod
    fun close(call: PluginCall) {
        try {
            val socketId = call.getInt("socketId")!!
            val socket = obtainSocket(socketId)
            addSelectorMessage(socket, SelectorMessageType.SO_CLOSE, call)
            sockets.remove(socket.socketId)
        } catch (e: Exception) {
            call.reject(e.message)
        }
    }

    @PluginMethod
    fun getInfo(call: PluginCall) {
        try {
            val socketId = call.getInt("socketId")!!
            val socket = obtainSocket(socketId)
            call.resolve(socket.info)
        } catch (e: Exception) {
            call.reject(e.message)
        }
    }

    @PluginMethod
    fun getSockets(call: PluginCall) {
        try {
            val results = JSArray()
            for (socket in sockets.values) {
                results.put(socket.info)
            }
            val ret = JSObject()
            ret.put("sockets", results)
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject(e.message)
        }
    }

    @PluginMethod
    fun joinGroup(call: PluginCall) {
        try {
            val socketId = call.getInt("socketId")!!
            val address = call.getString("address")
            val socket = obtainSocket(socketId)
            socket.joinGroup(address)
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message)
        }
    }

    @PluginMethod
    fun leaveGroup(call: PluginCall) {
        try {
            val socketId = call.getInt("socketId")!!
            val address = call.getString("address")
            val socket = obtainSocket(socketId)
            socket.leaveGroup(address)
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message)
        }
    }

    @PluginMethod
    fun setMulticastTimeToLive(call: PluginCall) {
        try {
            val socketId = call.getInt("socketId")!!
            val ttl = call.getInt("ttl")!!
            val socket = obtainSocket(socketId)
            socket.setMulticastTimeToLive(ttl)
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message)
        }
    }

    @PluginMethod
    fun setBroadcast(call: PluginCall) {
        try {
            val socketId = call.getInt("socketId")!!
            val enabled = call.getBoolean("enabled")!!
            val socket = obtainSocket(socketId)
            socket.setBroadcast(enabled)
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message)
        }
    }

    @PluginMethod
    fun setMulticastLoopbackMode(call: PluginCall) {
        try {
            val socketId = call.getInt("socketId")!!
            val enabled = call.getBoolean("enabled")!!
            val socket = obtainSocket(socketId)
            socket.setMulticastLoopbackMode(enabled, call)
        } catch (e: Exception) {
            call.reject(e.message)
        }
    }

    @PluginMethod
    fun getJoinedGroups(call: PluginCall) {
        try {
            val socketId = call.getInt("socketId")!!
            val socket = obtainSocket(socketId)

            val results: JSArray = JSArray(socket.joinedGroups)
            val ret = JSObject()
            ret.put("groups", results)
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject(e.message)
        }
    }


    private fun sendReceiveErrorEvent(socketId: Int, code: Int, message: String?) {
        val error = JSObject()
        try {
            error.put("socketId", socketId)
            error.put("message", message ?: "")
            error.put("resultCode", code)
            notifyListeners("receiveError", error, false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // This is a synchronized method because regular read and multicast read on
    // different threads, and we need to send data and metadata in serial in order
    // to decode the receive event correctly. Alternatively, we can send Multipart
    // messages.
    @Synchronized
    private fun sendReceiveEvent(data: ByteArray, socketId: Int, address: String, port: Int) {
        val ret = JSObject()
        try {
            ret.put("socketId", socketId)
            val ip6InterfaceIndex = address.indexOf("%")
            if (ip6InterfaceIndex > 0) {
                ret.put("remoteAddress", address.substring(0, ip6InterfaceIndex))
            } else {
                ret.put("remoteAddress", address)
            }
            ret.put("remotePort", port)
            val bufferString = String(Base64.encode(data, Base64.DEFAULT))
            ret.put("buffer", bufferString)
            notifyListeners("receive", ret, false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private fun startSelectorThread() {
        if (selectorThread != null) return
        selectorThread = SelectorThread(selectorMessages, sockets)
        selectorThread!!.start()
    }

    private fun stopSelectorThread() {
        if (selectorThread == null) return

        addSelectorMessage(null, SelectorMessageType.T_STOP, null)
        try {
            selectorThread!!.join()
            selectorThread = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun addSelectorMessage(
        socket: UdpSocket?, type: SelectorMessageType, call: PluginCall?
    ) {
        try {
            selectorMessages.put(SelectorMessage(socket, type, call))
            if (selector != null) selector!!.wakeup()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private enum class SelectorMessageType {
        SO_BIND,
        SO_CLOSE,
        SO_ADD_READ_INTEREST,
        SO_ADD_WRITE_INTEREST,
        T_STOP
    }

    private inner class SelectorMessage(
        val socket: UdpSocket?,
        val type: SelectorMessageType,
        val call: PluginCall?
    )

    private inner class SelectorThread(
        private val selectorMessages: BlockingQueue<SelectorMessage?>,
        private val sockets: MutableMap<Int, UdpSocket>
    ) : Thread() {
        private var running = true

        private fun processPendingMessages() {
            while (selectorMessages.peek() != null) {
                var msg: SelectorMessage? = null
                try {
                    msg = selectorMessages.take()
                    when (msg!!.type) {
                        SelectorMessageType.SO_BIND -> {
                            msg.socket!!.register(selector, SelectionKey.OP_READ)
                            msg.socket!!.isBound = true
                        }

                        SelectorMessageType.SO_CLOSE -> {
                            msg.socket!!.close()
                            sockets.remove(msg.socket!!.socketId)
                        }

                        SelectorMessageType.SO_ADD_READ_INTEREST -> msg.socket!!.addInterestSet(
                            SelectionKey.OP_READ
                        )

                        SelectorMessageType.SO_ADD_WRITE_INTEREST -> msg.socket!!.addInterestSet(
                            SelectionKey.OP_WRITE
                        )

                        SelectorMessageType.T_STOP -> running = false
                    }
                    if (msg.call != null) msg.call!!.resolve()
                } catch (_: InterruptedException) {
                } catch (e: IOException) {
                    if (msg!!.call != null) {
                        msg.call!!.reject(e.message)
                    }
                }
            }
        }

        override fun run() {
            try {
                if (selector == null) selector = Selector.open()
            } catch (e: IOException) {
                throw RuntimeException(e)
            }

            // process possible messages that send during opening the selector
            // before select.
            processPendingMessages()

            var it: MutableIterator<SelectionKey>

            while (running) {
                try {
                    selector!!.select()
                } catch (e: IOException) {
                    continue
                }

                it = selector!!.selectedKeys().iterator()

                while (it.hasNext()) {
                    val key = it.next()
                    it.remove()

                    if (!key.isValid) {
                        continue
                    }

                    val socket = key.attachment() as UdpSocket

                    if (key.isReadable) {
                        socket.read()
                    }

                    if (key.isWritable) {
                        socket.dequeueSend()
                    }
                } // while next


                processPendingMessages()
            }
        }
    }


    @SuppressLint("NewApi")
    private inner class UdpSocket(val socketId: Int, properties: JSObject) {
        private val channel: DatagramChannel

        private var multicastSocket: MulticastSocket?

        private val sendPackets: BlockingQueue<UdpSendPacket?> = LinkedBlockingQueue()
        private val multicastGroups: MutableSet<String?> = HashSet()
        private var key: SelectionKey? = null
        var isBound: Boolean

        private var paused: Boolean
        private var pausedMulticastPacket: DatagramPacket? = null

        private var name: String?
        private var bufferSize: Int

        private var multicastReadThread: MulticastReadThread?
        private var multicastLoopback: Boolean
        val ipv4Address: InetAddress = getIPAddress(true)
        val ipv6Address: InetAddress = getIPAddress(false)
        private val networkInterface: NetworkInterface?

        init {
            this.networkInterface = this.getNetworkInterface()
            channel = DatagramChannel.open()
            channel.configureBlocking(false)
            channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, this.networkInterface)
            multicastSocket = null

            // set socket default options
            paused = false
            bufferSize = 4096
            name = ""

            multicastReadThread = null
            multicastLoopback = true

            isBound = false

            setProperties(properties)
            setBufferSize()
        }

        // Only call this method on selector thread
        fun addInterestSet(interestSet: Int) {
            if (key != null && key!!.isValid) {
                key!!.interestOps(key!!.interestOps() or interestSet)
                key!!.selector().wakeup()
            }
        }

        // Only call this method on selector thread
        fun removeInterestSet(interestSet: Int) {
            if (key != null && key!!.isValid) {
                key!!.interestOps(key!!.interestOps() and interestSet.inv())
                key!!.selector().wakeup()
            }
        }


        @Throws(IOException::class)
        fun register(selector: Selector?, interestSets: Int) {
            key = channel.register(selector, interestSets, this)
        }

        @Throws(JSONException::class, SocketException::class)
        fun setProperties(properties: JSObject) {
            if (!properties.isNull("name")) name = properties.getString("name")

            if (!properties.isNull("bufferSize")) {
                bufferSize = properties.getInt("bufferSize")
                setBufferSize()
            }
        }

        @Throws(SocketException::class)
        fun setBufferSize() {
            channel.socket().sendBufferSize = bufferSize
            channel.socket().receiveBufferSize = bufferSize
        }

        private fun sendMulticastPacket(packet: DatagramPacket) {
            var out = packet.data

            // Truncate the buffer if the message was shorter than it.
            if (packet.length != out.size) {
                val temp = ByteArray(packet.length)
                for (i in 0 until packet.length) {
                    temp[i] = out[i]
                }
                out = temp
            }

            sendReceiveEvent(out, socketId, packet.address.hostAddress, packet.port)
        }

        @Throws(SocketException::class)
        private fun bindMulticastSocket() {
            multicastSocket!!.bind(InetSocketAddress(channel.socket().localPort))

            if (!paused) {
                multicastReadThread = MulticastReadThread(socketId, multicastSocket)
                multicastReadThread!!.start()
            }
        }

        // Upgrade the normal datagram socket to multicast socket. All incoming
        // packet will be received on the multicast read thread. There is no way to
        // downgrade the same socket back to a normal datagram socket.
        @Throws(IOException::class)
        private fun upgradeToMulticastSocket() {
            if (multicastSocket == null) {
                multicastSocket = MulticastSocket(null)
                multicastSocket!!.reuseAddress = true
                multicastSocket!!.loopbackMode = false


                if (channel.socket().isBound) {
                    bindMulticastSocket()
                }
            }
        }

        private fun resumeMulticastSocket() {
            if (pausedMulticastPacket != null) {
                sendMulticastPacket(pausedMulticastPacket!!)
                pausedMulticastPacket = null
            }

            if (multicastSocket != null && multicastReadThread == null) {
                multicastReadThread = MulticastReadThread(socketId, multicastSocket)
                multicastReadThread!!.start()
            }
        }

        fun setPaused(paused: Boolean) {
            this.paused = paused
            if (!this.paused) {
                resumeMulticastSocket()
            }
        }

        fun addSendPacket(address: String?, port: Int, data: ByteArray?, call: PluginCall?) {
            val sendPacket = UdpSendPacket(address, port, data, call)
            try {
                sendPackets.put(sendPacket)
            } catch (e: Exception) {
                call!!.reject(e.message)
            }
        }

        @Throws(SocketException::class)
        fun bind(address: String?, port: Int) {
            channel.socket().reuseAddress = true
            channel.socket().bind(InetSocketAddress(port))

            if (multicastSocket != null) {
                bindMulticastSocket()
            }
        }

        // This method can be only called by selector thread.
        fun dequeueSend() {
            if (sendPackets.peek() == null) {
                removeInterestSet(SelectionKey.OP_WRITE)
                return
            }

            var sendPacket: UdpSendPacket? = null
            try {
                sendPacket = sendPackets.take()
                val ret = JSObject()
                val bytesSent = channel.send(sendPacket!!.data, sendPacket.address)
                ret.put("bytesSent", bytesSent)
                if (sendPacket.call != null) sendPacket.call!!.resolve(ret)
            } catch (_: InterruptedException) {
            } catch (e: IOException) {
                if (sendPacket!!.call != null) sendPacket.call!!.reject(e.message)
            }
        }


        @Throws(IOException::class)
        fun close() {
            if (key != null && channel.isRegistered) key!!.cancel()

            channel.close()

            if (multicastSocket != null) {
                multicastSocket!!.close()
                multicastSocket = null
            }

            if (multicastReadThread != null) {
                multicastReadThread!!.cancel()
                multicastReadThread = null
            }
        }

        @get:Throws(JSONException::class)
        val info: JSObject
            get() {
                val info = JSObject()
                info.put("socketId", socketId)
                info.put("bufferSize", bufferSize)
                info.put("name", name)
                info.put("paused", paused)
                if (channel.socket().localAddress != null) {
                    info.put("localAddress", channel.socket().localAddress.hostAddress)
                    info.put("localPort", channel.socket().localPort)
                }
                return info
            }

        @Throws(IOException::class)
        fun joinGroup(address: String?) {
            upgradeToMulticastSocket()

            if (multicastGroups.contains(address)) {
                Log.e(LOG_TAG, "Attempted to join an already joined multicast group.")
                return
            }

            multicastGroups.add(address)
            multicastSocket!!.joinGroup(
                InetSocketAddress(
                    InetAddress.getByName(address),
                    channel.socket().localPort
                ), networkInterface
            )
        }

        @Throws(UnknownHostException::class, IOException::class)
        fun leaveGroup(address: String?) {
            if (multicastGroups.contains(address)) {
                multicastGroups.remove(address)
                multicastSocket!!.leaveGroup(InetAddress.getByName(address))
            }
        }

        @Throws(IOException::class)
        fun setMulticastTimeToLive(ttl: Int) {
            upgradeToMulticastSocket()
            multicastSocket!!.timeToLive = ttl
        }

        @Throws(IOException::class)
        fun setMulticastLoopbackMode(enabled: Boolean, call: PluginCall) {
            upgradeToMulticastSocket()
            multicastSocket!!.loopbackMode = !enabled
            multicastLoopback = enabled
            val ret = JSObject()
            ret.put("enabled", !multicastSocket!!.loopbackMode)
            call.resolve(ret)
        }

        @Throws(IOException::class)
        fun setBroadcast(enabled: Boolean) {
            channel.socket().broadcast = enabled
        }

        val joinedGroups: Collection<String?>
            get() = multicastGroups

        // This method can be only called by selector thread.
        fun read() {
            if (paused) {
                // Remove read interests to avoid selector wakeup when readable.
                removeInterestSet(SelectionKey.OP_READ)
                return
            }

            val recvBuffer = ByteBuffer.allocate(bufferSize)
            recvBuffer.clear()

            try {
                val address = channel.receive(recvBuffer) as InetSocketAddress

                recvBuffer.flip()
                val recvBytes = ByteArray(recvBuffer.limit())
                recvBuffer[recvBytes]
                if (address.address.hostAddress?.contains(":") == true && multicastSocket != null) {
                    return
                }
                sendReceiveEvent(recvBytes, socketId, address.address.hostAddress, address.port)
            } catch (e: IOException) {
                sendReceiveErrorEvent(socketId, -2, e.message)
            }
        }

        private fun getNetworkInterface(): NetworkInterface? {
            try {
                val interfaces: List<NetworkInterface> =
                    Collections.list(NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    val addrs: List<InetAddress> = Collections.list(intf.inetAddresses)
                    if (addrs.size < 2) continue
                    if (addrs[0].isLoopbackAddress) continue
                    return intf
                }
            } catch (ignored: java.lang.Exception) {
            } // for now eat exceptions

            return null
        }

        private fun getIPAddress(useIPv4: Boolean): InetAddress {
            try {
                val interfaces: List<NetworkInterface> =
                    Collections.list(NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    val addrs: List<InetAddress> = Collections.list(intf.inetAddresses)
                    if (addrs.size < 2) continue
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress) {
                            val sAddr = addr.hostAddress
                            //boolean isIPv4 = InetAddressUtils.isIPv4Address(sAddr);
                            val isIPv4 = sAddr.indexOf(':') < 0
                            if (useIPv4) {
                                if (isIPv4) return addr
                                //return sAddr;
                            } else {
                                if (!isIPv4) {
                                    val delim = sAddr.indexOf('%') // drop ip6 zone suffix
                                    return addr
                                    //return delim<0 ? sAddr.toUpperCase() : sAddr.substring(0, delim).toUpperCase();
                                }
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {
            } // for now eat exceptions

            return InetAddress.getLoopbackAddress()
        }

        private inner class MulticastReadThread(
            private val socketId: Int,
            private val socket: MulticastSocket?
        ) : Thread() {
            override fun run() {
                while (!currentThread().isInterrupted) {
                    if (paused) {
                        // Terminate the thread if the socket is paused
                        multicastReadThread = null
                        return
                    }

                    try {
                        val out = ByteArray(socket!!.receiveBufferSize)
                        val packet = DatagramPacket(out, out.size)
                        socket.receive(packet)
                        if (!multicastLoopback) {
                            val fromAddress = packet.address.hostAddress
                            val ip4 = ipv4Address.hostAddress
                            val ip6 = ipv6Address.hostAddress

                            if (fromAddress.equals(
                                    ip4,
                                    ignoreCase = true
                                ) || fromAddress.equals(ip6, ignoreCase = true)
                            ) {
                                continue
                            }
                        }
                        if (paused) {
                            pausedMulticastPacket = packet
                        } else {
                            sendMulticastPacket(packet)
                        }
                    } catch (e: IOException) {
                        sendReceiveErrorEvent(socketId, -2, e.message)
                    }
                }
            }

            fun cancel() {
                interrupt()
            }
        }

        private inner class UdpSendPacket(
            address: String?,
            port: Int,
            data: ByteArray?,
            val call: PluginCall?
        ) {
            val address: SocketAddress = InetSocketAddress(address, port)
            val data: ByteBuffer = ByteBuffer.wrap(data)
        }
    }

    companion object {
        private const val LOG_TAG = "CapacitorUDP"
    }
}
