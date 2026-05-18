package pt.paradigmshift.babel.zigbee.notifications;

import com.zsmartsystems.zigbee.IeeeAddress;
import pt.unl.fct.di.novasys.babel.generic.ProtoNotification;

/**
 * Delivered when a send request could not be transmitted. Subscribers filter
 * on {@link #getSourceProto()} (the requester's {@code PROTOCOL_ID}) just as
 * for inbound packets.
 *
 * <p>This covers <em>synchronous</em> failures only: payload exceeding the
 * MTU, destination not in the coordinator's known-devices set, or the driver
 * throwing on transmit. Asynchronous ZCL-layer failures (timeouts, NACKs) are
 * not surfaced — the driver returns a {@code Future<CommandResult>} the
 * protocol does not currently await, so callers that need delivery
 * confirmation should observe the inbound traffic for an end-device
 * acknowledgement.
 */
public class ZigBeeSendFailedNotification extends ProtoNotification {

    public static final short NOTIFICATION_ID = 1202;

    private final short sourceProto;
    private final IeeeAddress destination;
    private final String reason;

    public ZigBeeSendFailedNotification(short sourceProto,
                                        IeeeAddress destination,
                                        String reason) {
        super(NOTIFICATION_ID);
        this.sourceProto = sourceProto;
        this.destination = destination;
        this.reason = reason;
    }

    public short getSourceProto() { return sourceProto; }

    public IeeeAddress getDestination() { return destination; }

    public String getReason() { return reason; }
}
