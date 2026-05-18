package pt.paradigmshift.babel.zigbee.notifications;

import com.zsmartsystems.zigbee.IeeeAddress;
import pt.unl.fct.di.novasys.babel.generic.ProtoNotification;

/**
 * Delivered for every heartbeat written by an end device to the µBabel
 * heartbeat attribute. Heartbeats carry no protocol-specific application
 * payload — there is no {@code sourceProto} envelope on them — so this
 * notification fans out to every subscriber unconditionally. Filter at the
 * subscriber side if you only care about a subset of devices.
 */
public class ZigBeeHeartbeatNotification extends ProtoNotification {

    public static final short NOTIFICATION_ID = 1201;

    private final IeeeAddress origin;
    private final int counter;

    public ZigBeeHeartbeatNotification(IeeeAddress origin, int counter) {
        super(NOTIFICATION_ID);
        this.origin = origin;
        this.counter = counter & 0xFFFF;
    }

    public IeeeAddress getOrigin() { return origin; }

    public int getCounter() { return counter; }
}
