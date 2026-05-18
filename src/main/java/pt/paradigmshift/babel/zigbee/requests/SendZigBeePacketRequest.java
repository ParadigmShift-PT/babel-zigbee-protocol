package pt.paradigmshift.babel.zigbee.requests;

import com.zsmartsystems.zigbee.IeeeAddress;
import pt.unl.fct.di.novasys.babel.generic.ProtoRequest;

/**
 * Asks the ZigBee protocol to transmit a payload to a specific end device,
 * identified by its IEEE address. Multiple Babel protocols can share the
 * coordinator safely by tagging each request with their own {@code sourceProto}
 * (their numeric {@code PROTOCOL_ID}) — receivers see that identifier on the
 * inbound
 * {@link pt.paradigmshift.babel.zigbee.notifications.ZigBeePacketReceivedNotification}
 * and filter accordingly.
 *
 * <p>There is no broadcast counterpart: the underlying driver exposes only
 * unicast {@code transmit(IeeeAddress, ZigBeePacket)}. ZigBee network-layer
 * broadcasts ({@code 0xFFFD/E/F}) would need driver support and are not
 * exposed here.
 */
public class SendZigBeePacketRequest extends ProtoRequest {

    public static final short REQUEST_ID = 1200;

    private final short sourceProto;
    private final IeeeAddress destination;
    private final byte[] payload;

    /**
     * @param sourceProto numeric ID of the sending protocol ({@code PROTOCOL_ID})
     * @param destination IEEE address of the target end device; must already
     *                    be in the coordinator's known-devices set
     * @param payload     bytes to transmit; must fit within the ZigBee MTU
     */
    public SendZigBeePacketRequest(short sourceProto, IeeeAddress destination,
                                   byte[] payload) {
        super(REQUEST_ID);
        this.sourceProto = sourceProto;
        this.destination = destination;
        this.payload = payload;
    }

    public short getSourceProto() { return sourceProto; }

    public IeeeAddress getDestination() { return destination; }

    public byte[] getPayload() { return payload; }
}
