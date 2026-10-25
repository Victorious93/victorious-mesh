# Phone mesh (design)

This file is the queued design note for phone Wi-Fi mesh. The full
feasibility write-up and the three follow-up PRs are **not started**.
Write feasibility here first; then:

1. Offline mesh
2. Offline chat / files
3. Hotspot sharing (TetherFi-style — see below)

Current order before this work: #16 forced upstream, Batch 1, true
per-app exit/upstream (UID in Go netstack), Batch 2, then this file,
then ESP32.

## Part 3 — hotspot sharing (queued; TetherFi-style)

Don: fold TetherFi-style features into phone-mesh **part 3**, not a
separate product. **Cover feasibility in this doc first. Do not
implement yet.**

Intended scope (all **off by default**):

- No-root **Wi-Fi Direct group** hotspot with a user-set SSID,
  passphrase, and band.
- Built-in **HTTP CONNECT + SOCKS5** proxy on the group interface so
  clients share either the **normal internet** or the **private mesh**
  (user-selectable). Reuse #12 mesh-proxy code (`libtailscale/meshproxy`,
  `proxymux.SplitSOCKSAndHTTP`, EncryptedSharedPreferences auth). Do not
  invent a second proxy stack.
- Connected-client list with **block / allow**.
- Optional **wake lock / keep-alive**.
- A **Quick Settings tile**.
- Optional **hide Wi-Fi** (hidden SSID). Off by default. Feasibility
  must verify whether a non-system app can hide the SSID of a Wi-Fi
  Direct group or `startLocalOnlyHotspot` (**expected: no**). If not,
  implement the fallback: an innocuous / randomized network name,
  rotating passphrase, and a **MAC allowlist** (only allowed devices
  can join). Disclose the limit in the UI, MANUAL, and Help.
- Disclose **carrier and Android limits** (tethering entitlement,
  Wi-Fi Direct vs local-only hotspot, OEM differences, no promise of
  a system-wide SoftAP, likely no hidden SSID).
- MANUAL + Help. **[not device-verified]** until a phone is checked.

Feasibility must answer: Wi-Fi Direct group owner vs
`WifiManager.startLocalOnlyHotspot` vs tether AP; whether the group
interface can bind #12’s listeners without `0.0.0.0`; how mesh vs
underlay egress is chosen; client block/allow APIs; whether a
non-system app can hide SSID (and the randomized-name + rotating
passphrase + MAC-allowlist fallback if not); and what fails on
carrier-locked devices.

## ESP32 relay nodes (placeholder)

Queued at the end, after the current feature batches and the phone-mesh
PRs. **Do not build this.** The phone↔node protocol is a **draft**
(`docs/mesh-relay.md` in Victorious93/victormodem PR #2; that repo may
404 from this workspace). Hardware is **not approved**.

The relay is not a mesh peer and is not added to the fleet allowlist.
No pairing UI, transport, native code, `com.victorious` packages,
certificates, or bridge operations until the protocol is ratified and
the hardware is approved.

### Draft link (phone ↔ node)

- **BLE GATT** service `7a1c0101-5e5f-4d1b-9c3a-56c7f0a0b001`
  - `…0102` RX — write without response
  - `…0103` TX — notify
  - `…0104` INFO
  - ATT MTU 247
- **Wi-Fi** WebSocket `ws://192.168.4.1:81/mesh`

### Draft framing

- Frame: `0xA5` | `LEN` (2 bytes) | `SEQ` | `FLAGS` | payload (≤ 4096) |
  CRC-16-CCITT-FALSE
- Sliding window 4, 3 retries
- Mesh header is 18 bytes
- Node id = first 4 bytes of SHA-256(Ed25519 public key)

### Draft E2E (phone only)

Encryption is on the phone. The ESP32 forwards ciphertext; it must not
be able to read the payload.

- X25519 + HKDF
- XChaCha20-Poly1305
- Ed25519
- QR key exchange: `vmesh://k?…`
