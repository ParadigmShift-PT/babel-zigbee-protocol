# Babel ZigBee Protocol

A [Babel](https://github.com/pfouto/babel) `GenericProtocol` that exposes the
ParadigmShift [ZigBee coordinator driver](../babel-zigbee-standalone) as Babel
requests/notifications, so that one or many Babel protocols on a Raspberry Pi
gateway can share a single Ember EZSP USB dongle.

**Group ID:** `pt.paradigmshift.babel`
**Artifact ID:** `babel-zigbee-protocol`
**Current version:** `0.5.0`
**Tested with:** `pt.paradigmshift.iot:babel-zigbee:0.2.0` driver,
`pt.paradigmshift.babel:babel-radio-api:0.3.0`, and
`pt.paradigmshift.babel:babel-core:1.0.1`.

> **0.3.0 is a breaking release.** It bumps the transitive
> `babel-radio-api` dependency to `0.2.0`, which renumbered the shared
> radio events into the reserved slot `400` (was `100`/`101`). Subscribers
> recompile against the new IDs; no API names change. See the *Identifiers*
> section below.

> **0.2.0** was the earlier breaking release that moved the request and
> send-failure notification types to the shared `babel-radio-api` library,
> changed the destination type from `IeeeAddress` to `ZigBeeAddress`, and
> added NWK-layer broadcast. See *Migration*.

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
`RadioPacketReceivedNotification`; each one filters by its own `PROTOCOL_ID`.
The ZigBee protocol emits a subclass — `ZigBeePacketReceivedNotification` —
carrying the µBabel `id` and `val` fields. Generic subscribers see only
the base type; ZigBee-aware subscribers cast to the subclass:

```java
subscribeNotification(RadioPacketReceivedNotification.NOTIFICATION_ID, (n, src) -> {
    if (n.getSourceProto() != MY_PROTOCOL_ID) return;
    if (n instanceof ZigBeePacketReceivedNotification zb) {
        handlePeerMessage(zb.getZigBeeOrigin(), zb.getPacketId(), zb.getPayload());
    }
});
```

`sourceProto` here is the *remote* sender's protocol id, carried in the wire
envelope — distinct from the local `sourceProto` parameter Babel passes to
every handler (which is always `ZigBeeProtocol.PROTOCOL_ID` for these
notifications). The naming mirrors `BabelMessage.getSourceProto()`.

---

## Protocol & event identifiers

Follows the ParadigmShift workspace Babel ID convention: protocols at
100-multiples, events numbered `protocol_id + N` per handler class. This
protocol owns slot `1200`. Its packet-received notification inherits its
ID from the shared `babel-radio-api` reserved slot `400` so multi-radio
subscribers can register a single handler; its heartbeat notification is
the protocol's own first notification under slot `1200`.

| Type | Origin | Handler class | ID | Purpose |
|---|---|---|---|---|
| `ZigBeeProtocol`                  | `babel-zigbee-protocol`     | protocol      | `1200` | This protocol's `PROTOCOL_ID` |
| `SendRadioPacketRequest`          | `babel-radio-api`           | request/reply | `401`  | Unicast a payload — `destination` is a `ZigBeeAddress` |
| `BroadcastRadioPacketRequest`     | `babel-radio-api`           | request/reply | `402`  | NWK-layer broadcast (defaults to `BROADCAST_ALL_DEVICES`) |
| `RadioPacketReceivedNotification` | `babel-radio-api`           | notification  | `401`  | Generic inbound packet — emitted as `ZigBeePacketReceivedNotification` (subclass) carrying `id`/`val` |
| `RadioSendFailedNotification`     | `babel-radio-api`           | notification  | `402`  | MTU exceeded, wrong-radio destination, or driver throw |
| `ZigBeeHeartbeatNotification`     | `babel-zigbee-protocol`     | notification  | `1201` | µBabel heartbeat attribute write — unconditional fan-out, no `sourceProto` filter. No LoRa analogue. |
| `ZigBeeAddress`                   | `babel-zigbee-protocol`     | —             | —      | IEEE EUI-64 ZigBee address; `RadioAddress` subclass (carries no event id) |

Routing from generic application code is one call:
`addr.owningProtocolId()` returns `1200` for any `ZigBeeAddress`.

`MAX_USER_PAYLOAD_BYTES = 114` (= 116 B driver payload limit − 2 B
`sourceProto` envelope). Requests with a larger payload trigger
`RadioSendFailedNotification`.

### What is *not* exposed

- **Only the default broadcast scope.** `BroadcastRadioPacketRequest` sends
  via `ZigBeeBroadcastDestination.BROADCAST_ALL_DEVICES`. Targeting
  `BROADCAST_RX_ON` / `BROADCAST_ROUTERS_AND_COORD` / etc. requires using
  the driver directly. ZigBee broadcasts also do not deliver to sleepy end
  devices that are not currently awake.
- **No async delivery confirmation.** The driver returns
  `Future<CommandResult>` from unicast and `boolean` from broadcast, but this
  protocol does not await/observe them (blocking the Babel handler thread
  would defeat Babel's threading model). `RadioSendFailedNotification`
  therefore covers synchronous failures only. End-to-end acknowledgement is
  the application's job — typically via an inbound reply from the end device.

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
        <version>0.5.0</version>
    </dependency>
</dependencies>
```

This artifact pulls in `pt.paradigmshift.iot:babel-zigbee` (the driver),
`pt.paradigmshift.babel:babel-radio-api` (the shared request/notification
types), and `pt.paradigmshift.babel:babel-core` transitively.

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

> **Unicast destinations must be a joined device.** Each unicast
> `SendRadioPacketRequest` is ultimately fulfilled through the driver's
> `ZigBeeCoordinator.transmit(IeeeAddress, ZigBeePacket)`, which requires
> the destination to be present in `coordinator.getKnownDevices()`. If the
> destination is unknown — or its endpoint / cluster is missing — the
> protocol catches the driver's `IllegalStateException` and emits a
> `RadioSendFailedNotification` rather than transmitting. Configuring a
> target before the device has joined is therefore safe (subsequent sends
> succeed automatically once the device appears), but the first few tries
> will fail until that happens. Broadcasts via
> `BroadcastRadioPacketRequest` have no such requirement — they go straight
> to the NCP.

```java
public class MyControlProtocol extends GenericProtocol {
    public static final short PROTOCOL_ID = 1300;

    public MyControlProtocol() throws HandlerRegistrationException {
        super("MyControl", PROTOCOL_ID);
        subscribeNotification(RadioPacketReceivedNotification.NOTIFICATION_ID, this::onRadioIn);
        subscribeNotification(ZigBeeHeartbeatNotification.NOTIFICATION_ID,     this::onHeartbeat);
        subscribeNotification(RadioSendFailedNotification.NOTIFICATION_ID,     this::onRadioFail);
    }

    private void command(ZigBeeAddress device, byte[] payload) {
        // Radio-agnostic routing: the address knows which protocol owns it.
        sendRequest(new SendRadioPacketRequest(PROTOCOL_ID, device, payload),
                    device.owningProtocolId());
    }

    private void announce(byte[] payload) {
        sendRequest(new BroadcastRadioPacketRequest(PROTOCOL_ID, payload),
                    ZigBeeProtocol.PROTOCOL_ID);
    }

    private void onRadioIn(RadioPacketReceivedNotification n, short src) {
        if (n.getSourceProto() != PROTOCOL_ID) return;            // not for us
        if (src != ZigBeeProtocol.PROTOCOL_ID) return;            // not ZigBee
        ZigBeePacketReceivedNotification zb = (ZigBeePacketReceivedNotification) n;
        handleDeviceMessage(zb.getZigBeeOrigin(), zb.getPacketId(), zb.getPayload());
    }

    private void onHeartbeat(ZigBeeHeartbeatNotification n, short src) {
        // No sourceProto on heartbeats — fan-out is unconditional.
        liveness.put(n.getOrigin(), n.getCounter());
    }

    private void onRadioFail(RadioSendFailedNotification n, short src) {
        if (n.getSourceProto() != PROTOCOL_ID) return;
        logger.warn("Radio send failed to {}: {}", n.getDestination(), n.getReason());
    }
}
```

Two unrelated protocols can coexist with no further coordination — they stamp
their own `PROTOCOL_ID` as `sourceProto` on every send, filter on
`n.getSourceProto()` in their handlers, and ignore the rest.

### Migration from 0.1.0

| 0.1.0 type | 0.2.0 replacement |
|---|---|
| `SendZigBeePacketRequest(sp, IeeeAddress dest, payload)` | `SendRadioPacketRequest(sp, new ZigBeeAddress(dest), payload)` |
| (no broadcast existed) | `BroadcastRadioPacketRequest(sp, payload)` — now supported via the driver's NWK-broadcast path |
| `ZigBeePacketReceivedNotification` | still exists, but now `extends RadioPacketReceivedNotification`; subscribe to `RadioPacketReceivedNotification.NOTIFICATION_ID` and `instanceof`-cast to access `id`/`val`. `getOrigin()` now returns `RadioAddress` — use `getZigBeeOrigin()` for the typed accessor. |
| `ZigBeeSendFailedNotification` | `RadioSendFailedNotification`; destination is a `RadioAddress` (cast to `ZigBeeAddress` if you need the IEEE). |
| `ZigBeeHeartbeatNotification` | unchanged in shape but `getOrigin()` now returns `ZigBeeAddress` instead of `IeeeAddress` (call `.getIeeeAddress()` on it for the raw EUI). |

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

Requires Java 17 and Maven 3.6+.

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
git tag v0.3.0
git push origin v0.3.0
```

---

## License

Copyright (c) 2026 ParadigmShift, Lda. See [LICENSE](LICENSE) for full terms.

Commercial use outside of ParadigmShift requires a written licence.
Contact: [info@paradigmshift.pt](mailto:info@paradigmshift.pt)
