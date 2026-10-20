# Phone mesh (design)

This file is the queued design note for phone Wi-Fi mesh (offline mesh,
offline chat/files, hotspot). The feasibility write-up and the three
follow-up PRs are **not started**.

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
