# VictorLinux integration (design, planning only)

**Status:** planning only. No feature code is in this PR. Nothing here is
built or **[not device-verified]**. Don asked for this on 2026-11-16.

**The rule: each side stands alone, in both directions.**

- Victorious Mesh must work fully without VictorLinux. No screen, setting,
  test or startup path may need a VictorLinux PC to exist.
- VictorLinux must work fully without Mesh. Nothing here needs a change in
  the VictorLinux repo. Mesh only uses what VictorLinux already documents.

| # | Item | Priority | Size |
|---|---|---|---|
| 1 | Copy a peer's mesh address as a VictorLinux base URL | Medium | Small |
| 2 | Optional reachability check of `/victor/health` (off by default) | Low | Small |

Labels: **[Certain]** = read in code or docs; **[Likely]** = strong reasoning,
not tested; **[Guessing]** = not confirmed.

## What VictorLinux says (checked)

Source: `docs/remote-access.md` and `airootfs/usr/local/lib/victorlinux/gateway.py`
in Victorious93/VictorLinux, `main` at `01fdd30` (2026-11-15, 12:46 AM EST).

- The gateway listens on `127.0.0.1:8080` by default (loopback only). For
  remote use the owner sets a LAN/VPN bind address and creates an API key.
  **[Certain]**
- The recommended transport is a VPN: Tailscale or **self-hosted Headscale**.
  The gateway is bound to the tailnet address (`tailscale ip -4`). **[Certain]**
- The base URL is `http(s)://<pc-address>:<port>/v1`, with port **8080** by
  default. **[Certain]**
- **TLS is opt-in, not the default.** HTTPS uses a self-signed certificate
  made by `victor-tls-cert <hostname-or-ip>`. Clients are told to import
  `cert.pem` or pin it. The gateway requires TLS 1.2 or newer. **[Certain]**
- `GET /victor/health` needs **no API key**. It returns `200
  {"status":"ok","gateway":"ok","auth_required":…}`, or `503` with
  `"status":"model_not_running"`. It contains no secrets. Every other path
  needs `Authorization: Bearer <key>` whenever auth is on. **[Certain]**
- The port can be changed in VictorLinux's *Server & Access* screen, so
  8080 is a default, not a constant. **[Certain]**
- VictorLinux defines **no pairing protocol** and no discovery. **[Certain]**
- Whether the Control Center shows the certificate's SHA-256 fingerprint to
  the owner, so it can be compared on the phone, is not confirmed.
  **[Guessing]**

**One change to the brief.** The brief says the copied URL is
`https://<ip>:8080/v1`. A default VictorLinux install serves plain `http`,
so an `https` URL would fail against it. Plan: keep `https` as the default
(the brief's choice) and add a per-device **http / https** choice. Over the
mesh, `http` is still carried inside the encrypted WireGuard tunnel, and
VictorLinux's own doc calls that the recommended setup. Don to confirm the
default. **[Certain]** about the mismatch; the default is a decision for Don.

## Item 1: copy VictorLinux URL (Medium, Small)

### Where it goes

The device list (MANUAL "Device list, search, and pinning") already has two
places for this:

- **Touch and hold** a device opens `DeviceDropdownMenu` in `MainView.kt`.
  Add **Copy VictorLinux URL** after **Copy IPv6**, using the same clipboard
  icon.
- **Device details** (`PeerDetails.kt`) list addresses that you tap to copy.
  Add one row, **VictorLinux URL**, under the addresses.

**A plain tap keeps opening device details.** Changing what a tap does would
break existing behaviour, and Mesh must not act as a VictorLinux client by
default. "Tapping a peer" in the brief is read as a tap on this menu item or
row. **[Likely]** this matches the intent; Don to confirm.

The menu item and the row are always shown, for every peer. They copy text
and nothing more: no network traffic and no detection. A one-line toast
says what was copied, for example: "Copied https://100.64.0.7:8080/v1".

### Settings

**Settings → VictorLinux** is a new, small screen. Everything on it is
optional:

- **Default scheme:** https (default) or http.
- **Default port:** 8080 (1–65535).
- **Per-device override:** a scheme and port saved by device StableID, set
  from the details row ("Edit"). StableID is used instead of IP so a device
  keeps its settings if its mesh address changes, and so no IP is written to
  settings (see Privacy).
- **Reachability check** (item 2), off.

The screen uses the fleet dark neon theme: background `#0B0F1A`, accents
`#00E5FF` (cyan) and `#FF2BD6` (magenta), and existing `AppTheme` tokens
for text. No white surfaces or white text.

### URL rules

- IPv4 first: `https://100.64.0.7:8080/v1`.
- IPv6 only if the peer has no IPv4. IPv6 goes in brackets, and any zone id
  is dropped: `https://[fd7a:115c:a1e0::7]:8080/v1`.
- Only mesh addresses are used (`100.64.0.0/10`, `fd7a:115c:a1e0::/48`).
  Subnet routes and LAN addresses are never used.
- A peer with no mesh address leaves the item disabled, with the reason
  shown.
- **Never** include an API key, a user name or a query string. The API key
  stays in the client app (for example DroidCommand-AI); Mesh never asks for
  it or stores it.
- The MagicDNS name is not used, because a self-signed certificate made for
  an IP would not match the name. **[Likely]**

## Item 2: optional reachability check (Low, Small)

### Off by default, and only when asked

- **Settings → VictorLinux → Reachability check** is **off** by default.
  While it is off, Mesh sends **nothing** to any VictorLinux port.
- When it is on, it only checks devices you **mark** as VictorLinux PCs
  ("Mark as VictorLinux PC" on the details screen). There is no automatic
  detection, scanning or guessing from host names.
- A check runs only when you tap **Check** on that device's details row.
  There are **no background or timed checks** in this plan. A later timed
  check would have to be a separate, opt-in change.

**Detection is optional and never required.** Item 1 works the same with
or without a check. A device that is never marked or checked still gets
**Copy VictorLinux URL**.

### What a check does

1. **Is the mesh up?** If it isn't (or WireGuard mode is on), say "Mesh is
   off" and send nothing. The check never falls back to Wi‑Fi or mobile
   data.
2. **Is it a mesh address?** The destination must be the peer's current
   mesh address. Anything else is refused before any traffic.
3. Send one `GET /victor/health` with **no** `Authorization` header, a 5 s
   timeout, no redirects (a 3xx counts as "unexpected answer"), and a 4 KB
   cap on the reply size.
4. Show a result chip on the details row:

| Answer | Chip |
|---|---|
| 200, `status: ok` | **Gateway up, model running** (cyan) |
| 503, `status: model_not_running` | **Gateway up, no model loaded** (magenta) |
| Connection refused or timeout | **No answer** (magenta). Hint: the gateway may still be loopback-only; bind it to the tailnet address in *Server & Access*. |
| TLS problem | **Certificate not trusted** (magenta), see below |
| Anything else (non-JSON, other code, too big) | **Unexpected answer** (magenta) |

The `auth_required` value is shown as plain text ("API key required: yes"),
which reminds you that the client app needs the key. The result is kept in
memory only. It is not saved to disk or logs.

### Self-signed TLS: no blind trust

When the scheme is `https`:

- **Never trust everything.** There is no "ignore certificate errors"
  switch, and no trust-all `X509TrustManager` or hostname verifier.
- **Normal validation first.** A certificate trusted by Android (for
  example from a reverse proxy with a real certificate) passes as usual.
- **Self-signed: confirm a fingerprint, then pin it.** If normal validation
  fails, close the connection **before sending the request**. Then show the
  certificate's SHA-256 fingerprint in a neon confirm dialog and ask you to
  compare it with the PC (`openssl x509 -in cert.pem -noout -fingerprint
  -sha256` on VictorLinux). The pin is saved only if you tap **Trust this
  certificate**. Pins are stored per device StableID.
- **With a pin,** a certificate is accepted only if it matches exactly
  (SHA-256 of the whole certificate; SPKI pinning is an option for Don). A
  mismatch is a hard failure: **Certificate changed**. You must look again
  and re-confirm; it never falls back to `http`. Expiry is still checked.
  The name is not checked, because VictorLinux certificates are made for
  whatever host or IP the owner typed. **[Likely]**
- **Forget certificate** removes a pin.
- The confirm dialog follows the #39 confirm-dialog hardening: touches are
  ignored while the screen is covered, and the button only works after a
  short delay.

When the scheme is `http`, there is no TLS. The check says "Plain HTTP
inside the mesh tunnel", like Mesh file share.

## Safety: what must not change

- **No new open ports.** Mesh opens no listener for this. Item 1 has no
  traffic at all. Item 2 is one outgoing connection, only when you tap.
  **[Certain]** by design.
- **Kill switch, fail-closed and per-app blocking are unchanged.** The check
  is Mesh's own traffic to a mesh address. If the mesh is down it is not
  sent. It never uses a protected (outside-the-VPN) socket. **[Likely]**
  mesh destinations stay on the mesh and do not go to the forced proxy; the
  implementation PR must test this against `upstreamproxy` and the #38
  guard.
- **No new fleet operations.** None are added. A read-only
  `victorlinux.health.get` (or a field in `node.status.get`) could be
  *proposed* later, but it **needs Fleet Orchestrator ratification** first
  and is not part of this plan. `docs/FLEET_BRIDGE.md` is not changed.
- **No change to VictorLinux** and no pairing protocol. Mesh only uses the
  documented, key-free `/victor/health`.

## Privacy

- Item 1 copies a mesh address and port to the clipboard and nothing else.
  Android 13+ shows a clipboard preview; the address is not marked
  sensitive, because it is a private mesh address and no secret. Other apps
  that read the clipboard can see it, as with **Copy IPv4** today.
  **[Certain]**
- No API key, password or token is ever asked for, stored, logged or put in
  a URL.
- Settings store scheme, port, the "VictorLinux PC" mark and certificate
  pins **by StableID, never by IP**. The backup import rule that refuses
  `ipv4` / `ipv6` / `ips` keys stays as it is, and the export contains no
  IPs.
- Check results stay in memory. The connection log (#36), if on, records at
  most "VictorLinux check: up / no model / no answer", with no IP, as it
  does today.
- Nothing is sent to VictorLinux unless you turn on the check **and** tap
  **Check**.

## Manual, Help and CHANGELOG impact (for the build PRs)

This PR changes no user-facing behaviour, so it does not touch `MANUAL.md`
or `CHANGELOG.md`. Each build PR must:

- **MANUAL / Help** (the Help screen renders `docs/MANUAL.md`): add a
  `### VictorLinux` section after **Remote shell**. It covers the copy item,
  the scheme and port settings, the opt-in check, the TLS fingerprint steps
  and the limits. It must say plainly that VictorLinux is **not required**.
  Also update **Device list, search, and pinning** (new menu item) and
  **Privacy and data handling** (nothing sent unless you opt in; pins by
  StableID).
- **CHANGELOG:** one *Added* entry per item, tagged
  `**[not device-verified]**`.
- **ManualAssetTest:** one test per item checking the section exists, e.g.
  `victorLinuxCopyUrlIsDocumented` and `victorLinuxHealthCheckIsDocumented`.
- Strings use the `victorlinux_*` prefix.

## Test plan (for the build PRs)

JVM unit tests (no device):

- **URL builder:** IPv4; IPv6 with brackets; zone id dropped; per-device
  scheme and port override; port range; no mesh address gives disabled;
  non-mesh addresses are rejected; the output never has user info or a
  query.
- **Off means off:** with the check off (the default), a fake HTTP client
  records **zero** calls, even when Check is triggered.
- **Not marked means no check:** an unmarked device is never checked.
- **Mesh down:** gives "Mesh is off" with zero calls; WireGuard mode
  likewise.
- **Destination guard:** any address outside the mesh ranges is refused
  before a socket is opened.
- **Answer mapping:** 200 ok, 503 model_not_running, 401, 3xx (not
  followed), 500, non-JSON, reply over 4 KB, timeout and refused each give
  the right chip. `auth_required` is read and shown.
- **TLS** against a local test server with a generated self-signed
  certificate:
  - with no pin, the request is never sent and the fingerprint is offered;
  - after confirming, the matching pin passes;
  - a different certificate gives **Certificate changed** with no fallback;
  - an expired certificate fails;
  - a system-trusted certificate passes without a prompt;
  - a source scan finds no trust-all trust manager or hostname verifier.
- **Privacy:** settings and exports contain no IP; pins and marks are keyed
  by StableID; the log line has no IP.
- **Theme:** the new screen uses the `AppTheme` tokens; no `Color.White`.
- **Manifest:** no new exported component and no new permission beyond
  `INTERNET`, which is already held.

Go: none expected, since no Go changes are planned. If the check is routed
through `libtailscale`, add a test that a mesh destination is never sent to
the upstream proxy or out of a protected socket.

On a phone (later, **[not device-verified]** until done):

- copy and paste into DroidCommand-AI, against a VictorLinux PC on
  Headscale;
- check with http and with https plus a confirmed pin;
- check with the kill switch on and the mesh down;
- check with the forced proxy on (the check must not go to the proxy);
- use Mesh with **no** VictorLinux PC anywhere: nothing breaks and no
  traffic goes to port 8080.

## Open questions for Don

1. Default scheme: https (brief) or http (VictorLinux's default install)?
2. Pin the whole certificate (simplest) or the public key (survives
   re-issuing with the same key)?
3. Is a tap on a menu item or row acceptable, or should a plain tap on a
   peer copy the URL? This plan keeps tap = details.
