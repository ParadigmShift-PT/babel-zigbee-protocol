package pt.paradigmshift.babel.zigbee;

import com.zsmartsystems.zigbee.IeeeAddress;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pt.paradigmshift.babel.radio.RadioAddress;
import pt.paradigmshift.babel.radio.frag.RadioFragmenter;
import pt.paradigmshift.babel.radio.frag.RadioReassembler;
import pt.paradigmshift.babel.radio.notifications.RadioSendFailedNotification;
import pt.paradigmshift.babel.radio.requests.BroadcastRadioPacketRequest;
import pt.paradigmshift.babel.radio.requests.SendRadioPacketRequest;
import pt.paradigmshift.babel.zigbee.notifications.ZigBeeHeartbeatNotification;
import pt.paradigmshift.babel.zigbee.notifications.ZigBeePacketReceivedNotification;
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;
import zigbee.ZigBeeCoordinator;
import zigbee.ZigBeePacket;

import java.util.List;
import java.util.Optional;
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
 * <h2>Transparent fragmentation</h2>
 * A message larger than one frame is split by {@link RadioFragmenter} into
 * fragment frames (marked by a reserved sentinel in the destProto position)
 * and rebuilt by a {@link RadioReassembler} on receive, so callers send and
 * receive whole payloads of any size up to {@link #MAX_USER_PAYLOAD_BYTES}. A
 * message that fits one frame is sent verbatim — zero overhead, unchanged wire.
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
     * Radio-payload capacity of a single ZigBee frame, in bytes: the ZCL
     * OCTET_STRING value cap the coordinator accepts
     * ({@link ZigBeeCoordinator#MAX_PACKET_SIZE_BYTES}, 121 B). A message whose
     * enveloped form ([destProto][payload]) exceeds this is transparently
     * fragmented; one that fits is sent verbatim.
     */
    public static final int FRAME_PAYLOAD_CAPACITY =
            ZigBeeCoordinator.MAX_PACKET_SIZE_BYTES;

    /**
     * Maximum user payload (in bytes) a send/broadcast request can carry. With
     * transparent fragmentation this is the fragmented ceiling (up to
     * {@link RadioFragmenter#MAX_FRAGMENTS} frames), not a single-frame limit;
     * a single frame still carries up to {@code FRAME_PAYLOAD_CAPACITY - 2}
     * (119 B) with zero overhead.
     */
    public static final int MAX_USER_PAYLOAD_BYTES =
            RadioFragmenter.MAX_FRAGMENTS
                    * (FRAME_PAYLOAD_CAPACITY - RadioFragmenter.FRAGMENT_HEADER_BYTES)
                    - 2;

    private static final int DEST_PROTO_BYTES = 2;

    private final ZigBeeCoordinator coordinator;
    private final RadioReassembler reassembler = new RadioReassembler();
    private int txMsgId = 0;

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

        List<byte[]> frames;
        try {
            frames = fragment(req.getDestProto(), req.getPayload());
        } catch (IllegalArgumentException e) {
            triggerNotification(new RadioSendFailedNotification(
                    req.getDestProto(), zb,
                    "Payload " + req.getPayload().length + "B too large: "
                            + e.getMessage()));
            return;
        }

        try {
            for (byte[] frame : frames) {
                coordinator.transmit(zb.getIeeeAddress(), buildPacket(frame));
            }
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
        List<byte[]> frames;
        try {
            frames = fragment(req.getDestProto(), req.getPayload());
        } catch (IllegalArgumentException e) {
            // Broadcast has no single destination — pass null.
            triggerNotification(new RadioSendFailedNotification(
                    req.getDestProto(), null,
                    "Payload " + req.getPayload().length + "B too large: "
                            + e.getMessage()));
            return;
        }

        try {
            for (byte[] frame : frames) {
                coordinator.transmit(buildPacket(frame));
            }
        } catch (Exception e) {
            logger.warn("ZigBee broadcast failed for destProto={}: {}",
                        req.getDestProto(), e.toString());
            triggerNotification(new RadioSendFailedNotification(
                    req.getDestProto(), null, e.toString()));
        }
    }

    /**
     * Envelopes the payload with the 2-byte destProto and transparently
     * fragments it to the ZigBee frame capacity, returning one byte[] per
     * frame (a single element when it already fits one frame).
     *
     * @throws IllegalArgumentException if the message exceeds the fragmented
     *         ceiling ({@link RadioFragmenter#MAX_FRAGMENTS} frames)
     */
    private List<byte[]> fragment(short destProto, byte[] payload) {
        byte[] enveloped = new byte[DEST_PROTO_BYTES + payload.length];
        enveloped[0] = (byte) ((destProto >> 8) & 0xFF);
        enveloped[1] = (byte) (destProto & 0xFF);
        System.arraycopy(payload, 0, enveloped, DEST_PROTO_BYTES,
                         payload.length);
        return RadioFragmenter.fragment(enveloped, FRAME_PAYLOAD_CAPACITY,
                                        txMsgId++ & 0xFF);
    }

    private static ZigBeePacket buildPacket(byte[] frame) {
        return new ZigBeePacket.Builder()
                .payload(frame)
                .build();
    }

    private void deliverIncoming(IeeeAddress origin, ZigBeePacket packet) {
        byte[] framePayload = packet.getPayload();
        if (framePayload == null) {
            return;
        }
        // Transparent reassembly: a non-fragmented frame returns immediately;
        // a fragment is buffered (keyed on the origin) until complete.
        Optional<byte[]> assembled = reassembler.offer(origin, framePayload);
        if (assembled.isEmpty()) {
            return; // incomplete message — waiting for more fragments
        }
        byte[] enveloped = assembled.get();
        if (enveloped.length < DEST_PROTO_BYTES) {
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
