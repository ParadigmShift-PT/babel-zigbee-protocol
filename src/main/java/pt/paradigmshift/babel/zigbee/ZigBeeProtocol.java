package pt.paradigmshift.babel.zigbee;

import com.zsmartsystems.zigbee.IeeeAddress;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pt.paradigmshift.babel.zigbee.notifications.ZigBeeHeartbeatNotification;
import pt.paradigmshift.babel.zigbee.notifications.ZigBeePacketReceivedNotification;
import pt.paradigmshift.babel.zigbee.notifications.ZigBeeSendFailedNotification;
import pt.paradigmshift.babel.zigbee.requests.SendZigBeePacketRequest;
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;
import zigbee.ZigBeeCoordinator;
import zigbee.ZigBeePacket;

import java.util.Properties;

/**
 * Babel protocol that adapts a {@link ZigBeeCoordinator} driver to the Babel
 * request/notification surface, allowing any number of Babel protocols on the
 * same gateway to share a single Ember EZSP ZigBee coordinator.
 *
 * <h2>How sharing works</h2>
 * <p>Each sender stamps its outbound traffic with its own 16-bit
 * {@code sourceProto} (its {@code PROTOCOL_ID}) on
 * {@link SendZigBeePacketRequest}; the protocol prepends a two-byte
 * big-endian envelope inside the {@code ZigBeePacket} payload so the receiver
 * side can recover that identifier. On reception, every subscriber of
 * {@link ZigBeePacketReceivedNotification} sees every packet — each protocol
 * filters by checking {@code n.getSourceProto() == MY_PROTOCOL_ID}.
 *
 * <p>The envelope lives inside {@code ZigBeePacket.payload} only. The
 * {@code id} and {@code val} fields are not touched, so end-device firmware
 * that uses them for application-level demultiplexing keeps working
 * unchanged.
 *
 * <h2>Wire format inside the ZigBee payload</h2>
 * <pre>
 *   [ 2 bytes sourceProto (big-endian) ][ user payload ... ]
 * </pre>
 *
 * <h2>What is <em>not</em> exposed</h2>
 * <ul>
 *   <li>No broadcast request — the driver only supports unicast
 *   {@link ZigBeeCoordinator#transmit(IeeeAddress, ZigBeePacket)}. ZigBee
 *   NWK-layer broadcast addresses are not surfaced.</li>
 *   <li>No async transmit acknowledgement — the driver returns
 *   {@code Future<CommandResult>} but the protocol does not await it;
 *   {@link ZigBeeSendFailedNotification} fires only on synchronous failure
 *   (MTU, unknown destination, driver throw).</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <p>The application owns the {@link ZigBeeCoordinator} and calls
 * {@link ZigBeeCoordinator#init()} before constructing this protocol.
 * {@link #init(Properties)} only wires the inbound callbacks — the
 * coordinator is already running by then.
 */
public class ZigBeeProtocol extends GenericProtocol {

    private static final Logger logger =
            LogManager.getLogger(ZigBeeProtocol.class);

    public static final String PROTOCOL_NAME = "ZigBee";
    public static final short PROTOCOL_ID = 1200;

    /**
     * Maximum user payload (in bytes) accepted by send requests. Derived
     * from the driver's {@link ZigBeeCoordinator#MAX_PAYLOAD_SIZE_BYTES} (116
     * B) minus the 2-byte {@code sourceProto} envelope this protocol adds.
     */
    public static final int MAX_USER_PAYLOAD_BYTES =
            ZigBeeCoordinator.MAX_PAYLOAD_SIZE_BYTES - 2;

    private static final int SOURCE_PROTO_ENVELOPE_BYTES = 2;

    private final ZigBeeCoordinator coordinator;

    /**
     * @param coordinator a fully constructed and initialised
     *                    {@link ZigBeeCoordinator}
     */
    public ZigBeeProtocol(ZigBeeCoordinator coordinator)
            throws HandlerRegistrationException {
        super(PROTOCOL_NAME, PROTOCOL_ID);
        this.coordinator = coordinator;

        registerRequestHandler(SendZigBeePacketRequest.REQUEST_ID,
                               this::uponSendRequest);
    }

    @Override
    public void init(Properties props) {
        // The coordinator's command-listener threads are already running;
        // wire them into Babel. triggerNotification is safe from foreign
        // threads (Babel posts to each subscriber's LinkedBlockingQueue).
        coordinator.setPacketHandler(this::deliverIncoming);
        coordinator.setHeartbeatHandler(this::deliverHeartbeat);
    }

    private void uponSendRequest(SendZigBeePacketRequest req,
                                 short sourceProto) {
        transmit(req.getDestination(), req.getSourceProto(), req.getPayload());
    }

    private void transmit(IeeeAddress destination, short sourceProto,
                          byte[] payload) {
        if (payload.length > MAX_USER_PAYLOAD_BYTES) {
            triggerNotification(new ZigBeeSendFailedNotification(
                    sourceProto, destination,
                    "Payload " + payload.length + "B exceeds MTU "
                            + MAX_USER_PAYLOAD_BYTES + "B"));
            return;
        }

        byte[] enveloped =
                new byte[SOURCE_PROTO_ENVELOPE_BYTES + payload.length];
        enveloped[0] = (byte) ((sourceProto >> 8) & 0xFF);
        enveloped[1] = (byte) (sourceProto & 0xFF);
        System.arraycopy(payload, 0, enveloped,
                         SOURCE_PROTO_ENVELOPE_BYTES, payload.length);

        try {
            ZigBeePacket packet = new ZigBeePacket.Builder()
                    .id(0)
                    .val(0)
                    .payload(enveloped)
                    .build();
            coordinator.transmit(destination, packet);
        } catch (Exception e) {
            logger.warn("ZigBee transmit failed for sourceProto={} dest={}: {}",
                        sourceProto, destination, e.toString());
            triggerNotification(new ZigBeeSendFailedNotification(
                    sourceProto, destination, e.toString()));
        }
    }

    private void deliverIncoming(IeeeAddress origin, ZigBeePacket packet) {
        byte[] enveloped = packet.getPayload();
        if (enveloped == null
                || enveloped.length < SOURCE_PROTO_ENVELOPE_BYTES) {
            // Foreign sender that doesn't speak our envelope — silently drop.
            return;
        }
        short sourceProto = (short) (((enveloped[0] & 0xFF) << 8)
                                     | (enveloped[1] & 0xFF));
        byte[] payload = new byte[enveloped.length
                                  - SOURCE_PROTO_ENVELOPE_BYTES];
        System.arraycopy(enveloped, SOURCE_PROTO_ENVELOPE_BYTES,
                         payload, 0, payload.length);

        triggerNotification(new ZigBeePacketReceivedNotification(
                sourceProto, origin, packet.getId(), packet.getVal(),
                payload));
    }

    private void deliverHeartbeat(IeeeAddress origin, Integer counter) {
        triggerNotification(new ZigBeeHeartbeatNotification(origin, counter));
    }
}
