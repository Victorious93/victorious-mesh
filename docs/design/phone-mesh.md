# Phone mesh (design)

This file is the queued design note for phone Wi-Fi mesh (offline mesh,
offline chat/files, hotspot). The feasibility write-up and the three
follow-up PRs are **not started**.

## ESP32 relay nodes (placeholder)

Queued at the end, after the current feature batches and the phone-mesh
PRs. **Build nothing until VictorModem sends the phone↔node protocol.**

VictorModem is designing ESP32 LoRa / ESP-NOW relay nodes. When the
protocol arrives, this section will specify the handshake, framing, and
what the phone may implement.

Until then, the intended shape is only:

- **Link:** BLE and/or Wi-Fi between this phone and the relay node. The
  node is not a mesh peer and is not added to the fleet allowlist.
- **Payloads:** end-to-end encrypted. The relay forwards ciphertext; it
  must not be able to read the contents.
- **No fleet ops, pairing UI, transport, or native code** until the
  protocol is written here.

Do not add `com.victorious` packages, certificates, or bridge operations
for these nodes in the meantime.
