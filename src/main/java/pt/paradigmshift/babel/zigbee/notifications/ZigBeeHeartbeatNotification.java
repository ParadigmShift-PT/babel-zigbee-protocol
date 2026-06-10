package pt.paradigmshift.babel.zigbee.notifications;

import pt.paradigmshift.babel.zigbee.ZigBeeAddress;
import pt.unl.fct.di.novasys.babel.generic.ProtoNotification;

/**
 * Delivered for every heartbeat written by an end device to the µBabel
 * heartbeat attribute. Heartbeats carry no protocol-specific application
 * payload — there is no {@code destProto} envelope on them — so this
 * notification fans out to every subscriber unconditionally. Filter at the
 * subscriber side if you only care about a subset of devices.
 *
 * <p>This notification is ZigBee-specific (LoRa has no analogue) and lives
 * in {@code babel-zigbee-protocol} rather than {@code babel-radio-api}.
 *
 * <p><b>Dormant at present.</b> The heartbeat is the one µBabel value still
 * carried as a ZCL attribute write (DATA/DISCOVERY moved to custom commands
 * in {@code babel-zigbee:0.5.0}), and the current µBabel firmware has no
 * heartbeat sender ({@code zb_send_heartbeat} was removed) — so this
 * notification never fires until the firmware regains one. The receive path
 * stays wired end-to-end (driver {@code heartbeatHandler} → this
 * notification) so it resumes working with no Java-side change.
 *
 * <p><b>Handler class:</b> notification. <b>ID:</b> {@value #NOTIFICATION_ID}
 * — first notification under {@link
 * pt.paradigmshift.babel.zigbee.ZigBeeProtocol} (id 1200).
 */
public class ZigBeeHeartbeatNotification extends ProtoNotification {

    public static final short NOTIFICATION_ID = 1201;

    private final ZigBeeAddress origin;
    private final int counter;

    public ZigBeeHeartbeatNotification(ZigBeeAddress origin, int counter) {
        super(NOTIFICATION_ID);
        this.origin = origin;
        this.counter = counter & 0xFFFF;
    }

    public ZigBeeAddress getOrigin() { return origin; }

    public int getCounter() { return counter; }
}
