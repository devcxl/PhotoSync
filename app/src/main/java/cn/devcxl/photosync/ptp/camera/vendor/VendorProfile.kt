package cn.devcxl.photosync.ptp.camera.vendor

import cn.devcxl.photosync.ptp.camera.PtpSession
import cn.devcxl.photosync.ptp.protocol.PtpDeviceException
import cn.devcxl.photosync.ptp.protocol.PtpEvent
import cn.devcxl.photosync.ptp.protocol.PtpException
import cn.devcxl.photosync.ptp.protocol.PtpTransportException
import timber.log.Timber

/**
 * What differs between camera families, as seen from the sync loop.
 *
 * A profile only names the vendor's event operation and the handshake its firmware needs.
 * Neither the trigger decision nor the object import has a vendor branch.
 */
internal interface VendorProfile {
    /** Name for logs. */
    val name: String

    /** Operation the vendor event channel polls, or null when the camera has none. */
    val eventChannelOperation: Int?

    /**
     * Runs once per session, before any storage is read.
     *
     * Best effort by contract: a body that refuses the vendor handshake still syncs over the
     * object list, so a rejection here must not cost the connection.
     */
    fun onSessionStart(session: PtpSession) = Unit

    /** Runs after the storage ids were read and accepted. Best effort, like [onSessionStart]. */
    fun afterStorageEnumeration(session: PtpSession, storageIds: IntArray) = Unit

    /** Builds the event channel when this vendor has one. */
    fun createEventChannel(session: PtpSession): CameraEventChannel? = null
}

/**
 * Runs a vendor handshake step, treating a camera rejection as "this body does not do that".
 *
 * Only protocol-level failures are swallowed: a broken USB link still propagates, because
 * continuing on it would turn one clean error into a stream of timeouts.
 */
private inline fun ignoreRejection(step: () -> Unit) {
    try {
        step()
    } catch (e: PtpTransportException) {
        throw e
    } catch (e: PtpException) {
        Timber.d(e, "Vendor handshake step rejected")
    }
}

/** Cameras that speak plain PTP: object-list polling, no vendor handshake. */
internal object GenericProfile : VendorProfile {
    override val name: String = "PTP standard"
    override val eventChannelOperation: Int? = null
}

/**
 * Canon EOS bodies, which announce new files on their own event operation.
 *
 * The body only reports events after it is put into remote mode and then into event mode;
 * both are best effort, because a body that refuses them still syncs through the poll loop.
 */
internal class CanonEosProfile : VendorProfile {
    override val name: String = "Canon EOS"
    override val eventChannelOperation: Int = OP_GET_EVENT

    override fun onSessionStart(session: PtpSession) {
        // Remote mode is what stops the body from going to sleep mid-transfer, and the
        // object-list poll path needs it just as much as the event channel does. A body that
        // rejects it still transfers, so this is not allowed to fail the session.
        ignoreRejection { session.transact(OP_SET_REMOTE_MODE, 1) }
    }

    override fun createEventChannel(session: PtpSession): CameraEventChannel =
        CanonEosEventChannel(session)

    companion object {
        const val OP_GET_EVENT = 0x9116
        const val OP_SET_REMOTE_MODE = 0x9114
        const val OP_SET_EVENT_MODE = 0x9115
    }
}

/** Polls `EosGetEvent` and decodes the records it returns. */
internal class CanonEosEventChannel(private val session: PtpSession) : CameraEventChannel {
    override val description: String = "Canon EOS GetEvent 0x9116"

    override fun prepare() {
        // Only an event-mode session asks the body to queue events. A body that refuses
        // stays silent on the channel, which the loop reads as a quiet camera and then
        // degrades after three failed polls.
        ignoreRejection { session.transact(CanonEosProfile.OP_SET_EVENT_MODE, 1) }
    }

    override fun poll(): List<CameraEvent> {
        val exchange = session.transactForData(CanonEosProfile.OP_GET_EVENT)
        if (!exchange.response.isOk) {
            throw PtpDeviceException(exchange.response.code, "EosGetEvent rejected")
        }
        val payload = exchange.data ?: return emptyList()
        return CanonEosEventReader.read(payload)
    }
}

/**
 * Nikon bodies, whose `CheckEvent` returns events accumulated since the last poll.
 *
 * This is the only vendor channel that carries the standard `ObjectAdded` code as well as
 * its own: a card capture arrives as `0x4002`, a capture held in camera memory as `0xC101`.
 */
internal class NikonProfile : VendorProfile {
    override val name: String = "Nikon"
    override val eventChannelOperation: Int = OP_CHECK_EVENT

    override fun createEventChannel(session: PtpSession): CameraEventChannel =
        NikonEventChannel(session)

    companion object {
        const val OP_CHECK_EVENT = 0x90c7
        const val OBJECT_ADDED_STANDARD = PtpEvent.OBJECT_ADDED
    }
}

/** Polls Nikon `CheckEvent` (0x90C7). */
internal class NikonEventChannel(private val session: PtpSession) : CameraEventChannel {
    override val description: String = "Nikon CheckEvent 0x90C7"

    override fun poll(): List<CameraEvent> {
        val exchange = session.transactForData(NikonProfile.OP_CHECK_EVENT)
        if (!exchange.response.isOk) {
            throw PtpDeviceException(exchange.response.code, "CheckEvent rejected")
        }
        val payload = exchange.data ?: return emptyList()
        return NikonEventReader.read(payload)
    }
}

/**
 * Sony bodies, which need an SDIO handshake before their storages appear.
 *
 * Sony has no usable event channel for this app: the card contents only become visible
 * after the handshake, and the handshake is repeated around every enumeration.
 */
internal class SonyProfile : VendorProfile {
    override val name: String = "Sony"
    override val eventChannelOperation: Int? = null

    override fun onSessionStart(session: PtpSession) {
        connect(session, SDIO_MODE_CONNECT)
        connect(session, SDIO_MODE_CONNECT_2)
        requestExtendedDeviceInfo(session)
    }

    override fun afterStorageEnumeration(session: PtpSession, storageIds: IntArray) {
        connect(session, SDIO_MODE_ENUMERATED)
    }

    private fun connect(session: PtpSession, mode: Int) {
        // A body that does not answer the handshake still enumerates on its own; failing
        // the whole session here would take a working camera offline.
        ignoreRejection { session.transactForData(OP_SDIO_CONNECT, mode) }
    }

    private fun requestExtendedDeviceInfo(session: PtpSession) {
        // Nothing reads the answer yet; the request is part of the vendor's handshake order.
        ignoreRejection { session.transactForData(OP_GET_EXTENDED_DEVICE_INFO, EXTENDED_INFO_PARAM) }
    }

    companion object {
        const val OP_SDIO_CONNECT = 0x9201
        const val OP_GET_EXTENDED_DEVICE_INFO = 0x9202

        private const val EXTENDED_INFO_PARAM = 0xc8
        private const val SDIO_MODE_CONNECT = 0x01
        private const val SDIO_MODE_CONNECT_2 = 0x02
        private const val SDIO_MODE_ENUMERATED = 0x03
    }
}
