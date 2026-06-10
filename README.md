# Babel ZigBee Protocol

A [Babel](https://github.com/pfouto/babel) `GenericProtocol` that exposes the
ParadigmShift [ZigBee coordinator driver](../babel-zigbee-standalone) as Babel
requests/notifications, so that one or many Babel protocols on a Raspberry Pi
gateway can share a single Ember EZSP USB dongle.

**Group ID:** `pt.paradigmshift.babel`
**Artifact ID:** `babel-zigbee-protocol`
**Current version:** `0.9.0`
**Tested with:** `pt.paradigmshift.iot:babel-zigbee:0.5.0` driver,
`pt.paradigmshift.babel:babel-radio-api:0.5.0`, and
`pt.paradigmshift.babel:babel-core:1.0.1`.

> **0.9.0 — driver transport moved to ZCL custom commands (no surface
> change).** The driver dependency moved to `babel-zigbee:0.5.0`, where
> DATA/DISCOVERY traffic rides as ZCL cluster-specific custom commands
> (ids `0x0003`/`0x0005` on cluster `0xFF00`, bidirectional) instead of
> attribute writes — matching the 2026-06 µBabel firmware, which went deaf
> to attribute-write traffic. The µBabel heartbeat stays an attribute write
> but is **dormant** (the firmware currently has no heartbeat sender;
> `ZigBeeHeartbeatNotification` never fires until it returns — the path
> stays wired). This protocol's requests, notifications, `destProto`
> envelope, and fragmentation wire format are all unchanged: consumers
> recompile against the new version with no code change.

> **0.8.0 — transparent fragmentation.** Sends/broadcasts larger than one
> ZigBee frame are split by `RadioFragmenter` (from `babel-radio-api:0.5.0`)
> and rebuilt on receive by a `RadioReassembler` keyed on the origin
> `IeeeAddress`; a message that fits one frame is sent verbatim (unchanged
> wire). See *Protocol & event identifiers* for the new payload ceiling.

> **0.7.0 is a breaking release.** `ZigBeePacketReceivedNotification` no longer
> carries `getPacketId()`/`getVal()` — the `ubabel_zb_packet_t` framing those
> came from was scrapped (it held no useful information), so the ZigBee path now
> uses the **same wrapped `ubabel_packet_t` as LoRa**. The driver dep moved to
> `babel-zigbee:0.4.0`. Subscribers that read `getPacketId()`/`getVal()` must
> drop those calls; the demux metadata they were used for now lives in the
> `ubabel_packet_t` body decoded from `getPayload()`.

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
without stepping on each other, every send request is tagged with a 16-bit
`destProto` — the id of the protocol the frame is for (by Babel's symmetric
N↔N convention a sender passes its own `PROTOCOL_ID`). On the wire, this
protocol prepends two bytes inside the `ZigBeePacket` payload:

```
[ 2 bytes destProto (big-endian) ][ user payload ... ]
```

The envelope is the leading 2 bytes of the OCTET_STRING value — identical to the
LoRa side. (Since `babel-zigbee:0.4.0` the value is a bare wrapped
`ubabel_packet_t`; the scrapped `ubabel_zb_packet_t` `id`/`val` header is gone.
Since `babel-zigbee:0.5.0` the value travels as the payload of a ZCL
cluster-specific custom command rather than an attribute write — a
driver-internal transport detail this protocol never sees.)

Inbound packets are delivered to every protocol that subscribed to
`RadioPacketReceivedNotification`; each one keeps only frames addressed to it
(`getDestProto() == its PROTOCOL_ID`). The ZigBee protocol emits a subclass —
`ZigBeePacketReceivedNotification` — that adds only a typed-origin accessor.
Generic subscribers see only the base type; ZigBee-aware subscribers cast to
the subclass:

```java
subscribeNotification(RadioPacketReceivedNotification.NOTIFICATION_ID, (n, src) -> {
    if (n.getDestProto() != MY_PROTOCOL_ID) return;   // not addressed to us
    if (n instanceof ZigBeePacketReceivedNotification zb) {
        handlePeerMessage(zb.getZigBeeOrigin(), zb.getPayload());
    }
});
```

`getDestProto()` is the destination protocol id carried in the wire envelope —
distinct from the `src` parameter Babel passes to every handler (which here is
always `ZigBeeProtocol.PROTOCOL_ID`, i.e. the delivering bridge). The field was
named `sourceProto` before `babel-radio-api 0.4.0`; the rename makes the
receive-side meaning (the addressee) read correctly.

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
| `RadioPacketReceivedNotification` | `babel-radio-api`           | notification  | `401`  | Generic inbound packet — emitted as `ZigBeePacketReceivedNotification` (subclass adds only `getZigBeeOrigin()`) |
| `RadioSendFailedNotification`     | `babel-radio-api`           | notification  | `402`  | MTU exceeded, wrong-radio destination, or driver throw |
| `ZigBeeHeartbeatNotification`     | `babel-zigbee-protocol`     | notification  | `1201` | µBabel heartbeat attribute write — unconditional fan-out, no `destProto` filter. No LoRa analogue. **Dormant:** the current µBabel firmware has no heartbeat sender; the path stays wired. |
| `ZigBeeAddress`                   | `babel-zigbee-protocol`     | —             | —      | IEEE EUI-64 ZigBee address; `RadioAddress` subclass (carries no event id) |

Routing from generic application code is one call:
`addr.owningProtocolId()` returns `1200` for any `ZigBeeAddress`.

Since `0.8.0` fragmentation is transparent: a single ZigBee frame carries
`FRAME_PAYLOAD_CAPACITY = 121` B (the driver's OCTET_STRING cap), so a message
whose enveloped form (`destProto` + payload) fits 121 B — i.e. up to 119 B of
user payload — is sent verbatim with zero overhead. Larger messages are split
into up to `RadioFragmenter.MAX_FRAGMENTS = 16` fragment frames (5 B header
each), giving `MAX_USER_PAYLOAD_BYTES = 16 × (121 − 5) − 2 = 1854` B. Requests
with a larger payload trigger `RadioSendFailedNotification`.

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
        <version>0.9.0</version>
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
> destination is unknown — or its endpoint is missing (the µBabel cluster
> itself is attached on demand since `babel-zigbee:0.5.0`) — the
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
        if (n.getDestProto() != PROTOCOL_ID) return;              // not for us
        if (src != ZigBeeProtocol.PROTOCOL_ID) return;            // not ZigBee
        ZigBeePacketReceivedNotification zb = (ZigBeePacketReceivedNotification) n;
        handleDeviceMessage(zb.getZigBeeOrigin(), zb.getPayload());
    }

    private void onHeartbeat(ZigBeeHeartbeatNotification n, short src) {
        // No destProto on heartbeats — fan-out is unconditional.
        liveness.put(n.getOrigin(), n.getCounter());
    }

    private void onRadioFail(RadioSendFailedNotification n, short src) {
        if (n.getDestProto() != PROTOCOL_ID) return;
        logger.warn("Radio send failed to {}: {}", n.getDestination(), n.getReason());
    }
}
```

Two unrelated protocols can coexist with no further coordination — they stamp
their own `PROTOCOL_ID` as `destProto` on every send, filter on
`n.getDestProto()` in their handlers, and ignore the rest.

### Migration from 0.1.0

| 0.1.0 type | 0.2.0 replacement |
|---|---|
| `SendZigBeePacketRequest(sp, IeeeAddress dest, payload)` | `SendRadioPacketRequest(sp, new ZigBeeAddress(dest), payload)` |
| (no broadcast existed) | `BroadcastRadioPacketRequest(sp, payload)` — now supported via the driver's NWK-broadcast path |
| `ZigBeePacketReceivedNotification` | still exists, but now `extends RadioPacketReceivedNotification`; subscribe to `RadioPacketReceivedNotification.NOTIFICATION_ID` and `instanceof`-cast for the typed `getZigBeeOrigin()`. (In `0.7.0` the `getPacketId()`/`getVal()` accessors were removed — the `ubabel_zb_packet_t` framing was scrapped.) `getOrigin()` returns `RadioAddress`. |
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
git tag v0.9.0
git push origin v0.9.0
```

---

## License

Copyright (c) 2026 ParadigmShift, Lda. See [LICENSE](LICENSE) for full terms.

Commercial use outside of ParadigmShift requires a written licence.
Contact: [info@paradigmshift.pt](mailto:info@paradigmshift.pt)
