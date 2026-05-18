package pt.paradigmshift.babel.zigbee;

import com.zsmartsystems.zigbee.IeeeAddress;
import pt.paradigmshift.babel.radio.RadioAddress;

/**
 * ZigBee peer identifier: the 64-bit IEEE EUI-64 of a joined end device.
 * Wraps {@link IeeeAddress} so it fits into the protocol-agnostic
 * {@link RadioAddress} surface.
 */
public final class ZigBeeAddress extends RadioAddress {

    private final IeeeAddress ieee;

    public ZigBeeAddress(IeeeAddress ieee) {
        if (ieee == null) {
            throw new IllegalArgumentException("IeeeAddress must not be null");
        }
        this.ieee = ieee;
    }

    /** The underlying zsmartsystems {@link IeeeAddress}. */
    public IeeeAddress getIeeeAddress() { return ieee; }

    @Override
    protected Object key() { return ieee; }

    @Override
    public short owningProtocolId() { return ZigBeeProtocol.PROTOCOL_ID; }

    @Override
    public String toString() {
        return "ZigBeeAddress[" + ieee + "]";
    }
}
