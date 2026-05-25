package pt.paradigmshift.babel.zigbee.notifications;

import pt.paradigmshift.babel.radio.notifications.RadioPacketReceivedNotification;
import pt.paradigmshift.babel.zigbee.ZigBeeAddress;

/**
 * Specialisation of
 * {@link RadioPacketReceivedNotification} carrying the ZigBee-specific
 * {@code id} and {@code val} fields from the {@code ubabel_zb_packet_t}
 * wire format. These are application-level metadata used by end-device
 * firmware for demultiplexing; the Babel protocol layer does not interpret
 * them and surfaces them verbatim.
 *
 * <p>Shares
 * {@link RadioPacketReceivedNotification#NOTIFICATION_ID} with the base —
 * subscribers of the base ID receive this subclass too. Cast at the handler
 * if you need the ZigBee extras:
 *
 * <pre>{@code
 * if (n instanceof ZigBeePacketReceivedNotification zb) {
 *     int id  = zb.getPacketId();
 *     int val = zb.getVal();
 * }
 * }</pre>
 *
 * <p><b>Handler class:</b> notification. <b>ID:</b> inherited
 * {@code NOTIFICATION_ID = 401} from
 * {@link RadioPacketReceivedNotification} (reserved in the
 * {@code babel-radio-api} slot 400). Owning protocol: {@link
 * pt.paradigmshift.babel.zigbee.ZigBeeProtocol} (id 1200).
 */
public class ZigBeePacketReceivedNotification
        extends RadioPacketReceivedNotification {

    private final int packetId;
    private final int val;

    public ZigBeePacketReceivedNotification(short sourceProto,
                                            ZigBeeAddress origin,
                                            int packetId,
                                            int val,
                                            byte[] payload) {
        super(sourceProto, origin, payload);
        this.packetId = packetId & 0xFFFF;
        this.val = val & 0xFFFF;
    }

    public int getPacketId() { return packetId; }

    public int getVal() { return val; }

    /** Convenience accessor: {@link #getOrigin()} cast to {@link ZigBeeAddress}. */
    public ZigBeeAddress getZigBeeOrigin() { return (ZigBeeAddress) getOrigin(); }
}
