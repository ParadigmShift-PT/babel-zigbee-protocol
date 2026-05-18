package pt.paradigmshift.babel.zigbee.notifications;

import com.zsmartsystems.zigbee.IeeeAddress;
import pt.unl.fct.di.novasys.babel.generic.ProtoNotification;

/**
 * Delivered to every protocol that subscribes to it, for every ZigBee data
 * packet received by the coordinator. Subscribers are expected to filter on
 * {@link #getSourceProto()} to keep only the traffic addressed to their
 * protocol — by convention the remote sender stamps its own
 * {@code PROTOCOL_ID} there.
 *
 * <p>The {@code id} and {@code val} fields are application-level metadata
 * carried by the underlying {@code ZigBeePacket}; the Babel protocol layer
 * does not interpret them and surfaces them verbatim for end-device
 * demultiplexing.
 */
public class ZigBeePacketReceivedNotification extends ProtoNotification {

    public static final short NOTIFICATION_ID = 1200;

    private final short sourceProto;
    private final IeeeAddress origin;
    private final int id;
    private final int val;
    private final byte[] payload;

    public ZigBeePacketReceivedNotification(short sourceProto,
                                            IeeeAddress origin,
                                            int id,
                                            int val,
                                            byte[] payload) {
        super(NOTIFICATION_ID);
        this.sourceProto = sourceProto;
        this.origin = origin;
        this.id = id & 0xFFFF;
        this.val = val & 0xFFFF;
        this.payload = payload;
    }

    public short getSourceProto() { return sourceProto; }

    public IeeeAddress getOrigin() { return origin; }

    public int getPacketId() { return id; }

    public int getVal() { return val; }

    public byte[] getPayload() { return payload; }
}
