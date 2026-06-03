package pt.paradigmshift.babel.zigbee.notifications;

import pt.paradigmshift.babel.radio.notifications.RadioPacketReceivedNotification;
import pt.paradigmshift.babel.zigbee.ZigBeeAddress;

/**
 * Specialisation of {@link RadioPacketReceivedNotification} for ZigBee.
 *
 * <p>It adds only a typed-origin convenience accessor ({@link #getZigBeeOrigin()}).
 * The former {@code id}/{@code val} fields came from the {@code ubabel_zb_packet_t}
 * framing, which was scrapped (it carried no useful information) — the µBabel
 * ZigBee path now uses the same wrapped {@code ubabel_packet_t} form as LoRa, so
 * there are no ZigBee-specific wire extras to surface. Demultiplexing metadata
 * (recipient, type, …) lives inside the {@code ubabel_packet_t} body the
 * subscriber decodes from {@link #getPayload()}.
 *
 * <p>Shares {@link RadioPacketReceivedNotification#NOTIFICATION_ID} with the base
 * — subscribers of the base ID receive this subclass too. Cast at the handler
 * only if you need the typed origin:
 *
 * <pre>{@code
 * if (n instanceof ZigBeePacketReceivedNotification zb) {
 *     ZigBeeAddress origin = zb.getZigBeeOrigin();
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

    public ZigBeePacketReceivedNotification(short destProto,
                                            ZigBeeAddress origin,
                                            byte[] payload) {
        super(destProto, origin, payload);
    }

    /** Convenience accessor: {@link #getOrigin()} cast to {@link ZigBeeAddress}. */
    public ZigBeeAddress getZigBeeOrigin() { return (ZigBeeAddress) getOrigin(); }
}
