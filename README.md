# Babel ZigBee Protocol

A [Babel](https://github.com/pfouto/babel) `GenericProtocol` that exposes the
ParadigmShift [ZigBee coordinator driver](../babel-zigbee-standalone) as Babel
requests/notifications, so that one or many Babel protocols on a Raspberry Pi
gateway can share a single Ember EZSP USB dongle.

**Group ID:** `pt.paradigmshift.babel`
**Artifact ID:** `babel-zigbee-protocol`
**Current version:** `0.1.0`
**Tested with:** `pt.paradigmshift.iot:babel-zigbee:0.0.1` driver and
`pt.paradigmshift.babel:babel-core:1.0.0`.

---

## Multi-protocol sharing

ZigBee is single-coordinator by construction — every joined end device is
reachable through one dongle. To let multiple protocols share that coordinator
without stepping on each other, every send request is tagged with the sender's
16-bit `sourceProto` (its Babel `PROTOCOL_ID`). On the wire, this protocol
prepends two bytes inside the `ZigBeePacket` payload:

```
[ 2 bytes sourceProto (big-endian) ][ user payload ... ]
```

The envelope lives inside `ZigBeePacket.payload` only. The `id` and `val`
fields are not touched, so end-device firmware that uses them for
application-level demultiplexing keeps working unchanged.

Inbound packets are delivered to every protocol that subscribed to
`ZigBeePacketReceivedNotification`; each one filters by its own `PROTOCOL_ID`:

```java
subscribeNotification(ZigBeePacketReceivedNotification.NOTIFICATION_ID, (n, src) -> {
    if (n.getSourceProto() != MY_PROTOCOL_ID) return;
    handlePeerMessage(n.getOrigin(), n.getPayload());
});
```

`sourceProto` here is the *remote* sender's protocol id, carried in the wire
envelope — distinct from the local `sourceProto` parameter Babel passes to
every handler (which is always `ZigBeeProtocol.PROTOCOL_ID` for these
notifications). The naming mirrors `BabelMessage.getSourceProto()`.

---

## Request / notification surface

| Type | ID | Purpose |
|---|---|---|
| `SendZigBeePacketRequest`         | `1200` (request)      | Unicast a payload to a specific IEEE address |
| `ZigBeePacketReceivedNotification`| `1200` (notification) | One per received data packet — fan-out to every subscriber |
| `ZigBeeHeartbeatNotification`     | `1201` (notification) | One per heartbeat write from an end device — unconditional fan-out (no `sourceProto` filter) |
| `ZigBeeSendFailedNotification`    | `1202` (notification) | Synchronous send failure (MTU exceeded, unknown destination, driver throw) |

The protocol itself registers as id `1200`.

`MAX_USER_PAYLOAD_BYTES = 114` (= 116 B driver payload limit − 2 B
`sourceProto` envelope). Requests with a larger payload trigger
`ZigBeeSendFailedNotification`.

### What is *not* exposed

- **No broadcast request.** The underlying driver only supports unicast
  `transmit(IeeeAddress, ZigBeePacket)`. ZigBee NWK-layer broadcast addresses
  (`0xFFFD/E/F`) would need driver support and are not surfaced here. Sending
  the same payload to every known device is a one-line loop at the caller
  site if you really need it, but the resulting semantics are not equivalent
  to a true NWK broadcast.
- **No async delivery confirmation.** The driver returns
  `Future<CommandResult>` from `transmit(...)`, but this protocol does not
  await it (blocking the Babel handler thread would defeat Babel's threading
  model). `ZigBeeSendFailedNotification` therefore covers synchronous
  failures only. End-to-end acknowledgement is the application's job —
  typically via an inbound reply from the end device.

---

## Usage

Add to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>paradigmshift-repository</id>
        <name>ParadigmShift Repository</name>
        <url>https://maven.paradigmshift.pt/releases</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>pt.paradigmshift.babel</groupId>
        <artifactId>babel-zigbee-protocol</artifactId>
        <version>0.1.0</version>
    </dependency>
</dependencies>
```

This artifact pulls in `pt.paradigmshift.iot:babel-zigbee` (the driver) and
`pt.paradigmshift.babel:babel-core` transitively.

### Wiring it up in `Main`

The application owns the `ZigBeeCoordinator` and initialises it before
constructing the protocol. Cross-platform serial-port discovery is built into
the driver — pass an explicit path only when more than one USB-serial device
is connected (see the driver's README for details):

```java
String port = ZigBeeCoordinator.autoDiscoverSerialPort();   // or pass an explicit /dev/ttyUSB0
ZigBeeConfig cfg = new ZigBeeConfig.Builder()
        .serialPort(port)
        .build();

ZigBeeCoordinator coordinator = new ZigBeeCoordinator(cfg);
coordinator.init();
coordinator.permitJoin(254);   // open the network when you are ready to accept devices

Babel babel = Babel.getInstance();
ZigBeeProtocol zigbee = new ZigBeeProtocol(coordinator);
babel.registerProtocol(zigbee);
zigbee.init(props);
babel.start();
```

### Sending from another protocol

> **Destination must be a joined device.** Each `SendZigBeePacketRequest` is
> ultimately fulfilled through the driver's
> `ZigBeeCoordinator.transmit(IeeeAddress, ZigBeePacket)`, which requires the
> destination to be present in `coordinator.getKnownDevices()` (i.e. the
> device has joined and its µBabel endpoint has been registered). If the
> destination is unknown — or its endpoint / cluster is missing — the
> protocol catches the driver's `IllegalStateException` and emits a
> `ZigBeeSendFailedNotification` rather than transmitting. Configuring a
> target before the device has joined is therefore safe (subsequent sends
> succeed automatically once the device appears), but the first few tries
> will fail until that happens.

```java
public class MyControlProtocol extends GenericProtocol {
    public static final short PROTOCOL_ID = 1300;

    public MyControlProtocol() throws HandlerRegistrationException {
        super("MyControl", PROTOCOL_ID);
        subscribeNotification(ZigBeePacketReceivedNotification.NOTIFICATION_ID, this::onZigBeeIn);
        subscribeNotification(ZigBeeHeartbeatNotification.NOTIFICATION_ID,      this::onHeartbeat);
        subscribeNotification(ZigBeeSendFailedNotification.NOTIFICATION_ID,     this::onZigBeeFail);
    }

    private void command(IeeeAddress device, byte[] payload) {
        sendRequest(new SendZigBeePacketRequest(PROTOCOL_ID, device, payload),
                    ZigBeeProtocol.PROTOCOL_ID);
    }

    private void onZigBeeIn(ZigBeePacketReceivedNotification n, short src) {
        if (n.getSourceProto() != PROTOCOL_ID) return;   // not for us
        handleDeviceMessage(n.getOrigin(), n.getPayload());
    }

    private void onHeartbeat(ZigBeeHeartbeatNotification n, short src) {
        // No sourceProto on heartbeats — fan-out is unconditional.
        liveness.put(n.getOrigin(), n.getCounter());
    }

    private void onZigBeeFail(ZigBeeSendFailedNotification n, short src) {
        if (n.getSourceProto() != PROTOCOL_ID) return;
        logger.warn("ZigBee send failed to {}: {}", n.getDestination(), n.getReason());
    }
}
```

Two unrelated protocols can coexist with no further coordination — they stamp
their own `PROTOCOL_ID` as `sourceProto` on every send, filter on
`n.getSourceProto()` in their handlers, and ignore the rest.

---

## Threading note

`ZigBeeCoordinator` invokes its packet- and heartbeat-handler callbacks from
the zsmartsystems command-listener thread. This protocol installs
`this::deliverIncoming` and `this::deliverHeartbeat` as those callbacks, both
of which call `triggerNotification(...)` — safe from any thread, since Babel
delivers each notification through every subscriber's `LinkedBlockingQueue`.
Subscribers therefore observe inbound packets and heartbeats on their normal
protocol event loop.

---

## Building

Requires Java 21 and Maven 3.6+.

```bash
mvn verify    # compile + (no) tests
mvn package   # produces JAR, sources JAR, and Javadoc JAR
mvn install   # install to ~/.m2/
mvn deploy    # publish to maven.paradigmshift.pt (requires REPOSILITE_TOKEN)
```

This library compiles anywhere; running it requires an Ember EZSP USB dongle
(because of the transitive driver dependency).

## Releasing

Push a version tag — CI deploys automatically (mirroring the other
ParadigmShift Maven libs):

```bash
git tag v0.1.0
git push origin v0.1.0
```

---

## License

Copyright (c) 2026 ParadigmShift, Lda. See [LICENSE](LICENSE) for full terms.

Commercial use outside of ParadigmShift requires a written licence.
Contact: [info@paradigmshift.pt](mailto:info@paradigmshift.pt)
