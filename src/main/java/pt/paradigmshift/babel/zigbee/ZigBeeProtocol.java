package pt.paradigmshift.babel.zigbee;

import com.zsmartsystems.zigbee.IeeeAddress;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pt.paradigmshift.babel.radio.RadioAddress;
import pt.paradigmshift.babel.radio.notifications.RadioSendFailedNotification;
import pt.paradigmshift.babel.radio.requests.BroadcastRadioPacketRequest;
import pt.paradigmshift.babel.radio.requests.SendRadioPacketRequest;
import pt.paradigmshift.babel.zigbee.notifications.ZigBeeHeartbeatNotification;
import pt.paradigmshift.babel.zigbee.notifications.ZigBeePacketReceivedNotification;
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;
import zigbee.ZigBeeCoordinator;
import zigbee.ZigBeePacket;

import java.util.Properties;

/**
 * Babel protocol that adapts a {@link ZigBeeCoordinator} driver to the
 * shared {@code babel-radio-api} request/notification surface. Multiple
 * Babel protocols on the same gateway can share a single Ember EZSP ZigBee
 * coordinator by tagging each frame with a 2-byte {@code destProto} — the id
 * of the protocol the frame is for (by the symmetric N↔N convention a sender
 * uses its own {@code PROTOCOL_ID}); the protocol writes those two bytes
 * inside the {@code ZigBeePacket} payload and surfaces them again on inbound
 * notifications, where subscribers filter on them.
 *
 * <p>The envelope lives inside {@code ZigBeePacket.payload} only. The
 * {@code id} and {@code val} fields are not touched — end-device firmware
 * that uses them for application-level demultiplexing keeps working
 * unchanged.
 *
 * <h2>Wire layout inside the ZigBee payload</h2>
 * <pre>
 *   [ 2 bytes destProto (big-endian) ][ user payload ... ]
 * </pre>
 *
 * <h2>Inbound notifications</h2>
 * <ul>
 *   <li>{@link ZigBeePacketReceivedNotification} — subclass of
 *   {@link pt.paradigmshift.babel.radio.notifications.RadioPacketReceivedNotification},
 *   adds the µBabel {@code id} and {@code val} fields. Generic subscribers
 *   see only the base type; ZigBee-aware subscribers cast to access the
 *   extras.</li>
 *   <li>{@link ZigBeeHeartbeatNotification} — fires for every µBabel
 *   heartbeat attribute write from an end device. Heartbeats carry no
 *   {@code destProto} envelope (they're a periodic liveness signal, not
 *   application traffic), so the notification fans out unconditionally.
 *   This notification is ZigBee-specific and has no shared counterpart.</li>
 * </ul>
 *
 * <h2>What is <em>not</em> exposed</h2>
 * <ul>
 *   <li>No async transmit acknowledgement — the driver returns
 *   {@code Future<CommandResult>} from unicast and a {@code boolean} from
 *   broadcast, but the protocol does not await/observe them
 *   ({@link RadioSendFailedNotification} fires only on synchronous failure:
 *   MTU, unknown destination, driver throw).</li>
 *   <li>Only the default broadcast scope ({@code BROADCAST_ALL_DEVICES}) is
 *   used for {@link BroadcastRadioPacketRequest}. Other NWK broadcast
 *   scopes are reachable via the driver directly.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * The application owns the {@link ZigBeeCoordinator} and calls
 * {@link ZigBeeCoordinator#init()} before constructing this protocol.
 * {@link #init(Properties)} only wires the inbound callbacks — the
 * coordinator is already running by then.
 *
 * <h2>Identifiers</h2>
 * <p><b>Protocol ID:</b> {@value #PROTOCOL_ID}.
 * <p>This protocol uses the shared {@code babel-radio-api} surface; its
 * inbound packet notification ({@link ZigBeePacketReceivedNotification},
 * subclass of {@link pt.paradigmshift.babel.radio.notifications.RadioPacketReceivedNotification})
 * inherits {@code NOTIFICATION_ID = 401} from the radio-api's reserved
 * slot 400. The ZigBee-specific {@link ZigBeeHeartbeatNotification} is the
 * protocol's own first notification, id {@code 1201}.
 */
public class ZigBeeProtocol extends GenericProtocol {

    private static final Logger logger =
            LogManager.getLogger(ZigBeeProtocol.class);

    public static final String PROTOCOL_NAME = "ZigBee";
    public static final short PROTOCOL_ID = 1200;

    /**
     * Maximum user payload (in bytes) accepted by send/broadcast requests.
     * Derived from the driver's {@link ZigBeeCoordinator#MAX_PAYLOAD_SIZE_BYTES}
     * (116 B) minus the 2-byte destProto envelope this protocol adds.
     */
    public static final int MAX_USER_PAYLOAD_BYTES =
            ZigBeeCoordinator.MAX_PAYLOAD_SIZE_BYTES - 2;

    private static final int DEST_PROTO_BYTES = 2;

    private final ZigBeeCoordinator coordinator;

    /**
     * @param coordinator a fully constructed and initialised
     *                    {@link ZigBeeCoordinator}
     */
    public ZigBeeProtocol(ZigBeeCoordinator coordinator)
            throws HandlerRegistrationException {
        super(PROTOCOL_NAME, PROTOCOL_ID);
        this.coordinator = coordinator;

        registerRequestHandler(SendRadioPacketRequest.REQUEST_ID,
                               this::uponSendRequest);
        registerRequestHandler(BroadcastRadioPacketRequest.REQUEST_ID,
                               this::uponBroadcastRequest);
    }

    @Override
    public void init(Properties props) {
        // The coordinator's command-listener threads are already running;
        // wire them into Babel. triggerNotification is safe from foreign
        // threads (Babel posts to each subscriber's LinkedBlockingQueue).
        coordinator.setPacketHandler(this::deliverIncoming);
        coordinator.setHeartbeatHandler(this::deliverHeartbeat);
    }

    private void uponSendRequest(SendRadioPacketRequest req, short ignored) {
        RadioAddress dst = req.getDestination();
        if (!(dst instanceof ZigBeeAddress zb)) {
            triggerNotification(new RadioSendFailedNotification(
                    req.getDestProto(), dst,
                    "ZigBeeProtocol received non-ZigBeeAddress destination: "
                            + (dst == null ? "null" : dst.getClass().getName())));
            return;
        }

        byte[] enveloped = envelope(req.getDestProto(), req.getPayload());
        if (enveloped == null) {
            triggerNotification(new RadioSendFailedNotification(
                    req.getDestProto(), zb,
                    "Payload " + req.getPayload().length + "B exceeds MTU "
                            + MAX_USER_PAYLOAD_BYTES + "B"));
            return;
        }

        try {
            coordinator.transmit(zb.getIeeeAddress(),
                                 buildPacket(enveloped));
        } catch (Exception e) {
            logger.warn(
                    "ZigBee transmit failed for destProto={} dest={}: {}",
                    req.getDestProto(), zb, e.toString());
            triggerNotification(new RadioSendFailedNotification(
                    req.getDestProto(), zb, e.toString()));
        }
    }

    private void uponBroadcastRequest(BroadcastRadioPacketRequest req,
                                      short ignored) {
        byte[] enveloped = envelope(req.getDestProto(), req.getPayload());
        if (enveloped == null) {
            // Broadcast has no single destination — pass null.
            triggerNotification(new RadioSendFailedNotification(
                    req.getDestProto(), null,
                    "Payload " + req.getPayload().length + "B exceeds MTU "
                            + MAX_USER_PAYLOAD_BYTES + "B"));
            return;
        }

        try {
            coordinator.transmit(buildPacket(enveloped));
        } catch (Exception e) {
            logger.warn("ZigBee broadcast failed for destProto={}: {}",
                        req.getDestProto(), e.toString());
            triggerNotification(new RadioSendFailedNotification(
                    req.getDestProto(), null, e.toString()));
        }
    }

    /** Returns the source-proto-prefixed payload, or {@code null} if the
     *  user payload exceeds the MTU. */
    private static byte[] envelope(short destProto, byte[] payload) {
        if (payload.length > MAX_USER_PAYLOAD_BYTES) return null;
        byte[] enveloped = new byte[DEST_PROTO_BYTES + payload.length];
        enveloped[0] = (byte) ((destProto >> 8) & 0xFF);
        enveloped[1] = (byte) (destProto & 0xFF);
        System.arraycopy(payload, 0, enveloped, DEST_PROTO_BYTES,
                         payload.length);
        return enveloped;
    }

    private static ZigBeePacket buildPacket(byte[] enveloped) {
        return new ZigBeePacket.Builder()
                .payload(enveloped)
                .build();
    }

    private void deliverIncoming(IeeeAddress origin, ZigBeePacket packet) {
        byte[] enveloped = packet.getPayload();
        if (enveloped == null
                || enveloped.length < DEST_PROTO_BYTES) {
            // Foreign sender that doesn't speak our envelope — silently drop.
            return;
        }
        short destProto = (short) (((enveloped[0] & 0xFF) << 8)
                                     | (enveloped[1] & 0xFF));
        byte[] payload = new byte[enveloped.length - DEST_PROTO_BYTES];
        System.arraycopy(enveloped, DEST_PROTO_BYTES, payload, 0,
                         payload.length);

        triggerNotification(new ZigBeePacketReceivedNotification(
                destProto, new ZigBeeAddress(origin), payload));
    }

    private void deliverHeartbeat(IeeeAddress origin, Integer counter) {
        triggerNotification(new ZigBeeHeartbeatNotification(
                new ZigBeeAddress(origin), counter));
    }
}
