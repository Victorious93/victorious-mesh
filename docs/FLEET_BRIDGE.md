# Fleet bridge (droidcommand-AI control)

Victorious Mesh can be controlled by the fleet controller app **droidcommand-AI**
(`ai.droidcommand.app`). The controller can check the mesh status, read a
status-tray snapshot (`node.status.get`), read this phone's mesh IP addresses,
and turn the mesh on or off.

Transport is unchanged from v1.0. Protocol version is **major 1, minor 1**.
Minor 1 adds the FROZEN remote-control envelope (`capabilities.get`,
`remote.command`, `macro.step`, `macro.run`) on the same Messenger. Existing
`mesh.*` operations stay backward compatible.

## Turning it on

External control is **off by default**. To allow it:

1. Open Victorious Mesh at least once and approve the VPN permission.
2. Go to **Settings** and switch on **Allow fleet controller (droidcommand-AI)**.

Turn the switch off at any time to block the controller again.

While the switch is off, everything except capability discovery
(`capabilities.get` and `mesh.capabilities.get`) returns
`external_control_disabled`.

## Who is allowed

Authorization is **only** the calling app's package name plus signing certificate,
taken from `Message.sendingUid`. **Never** from any envelope field, including
`trigger.*`, `destructive`, `actionRef`, or `targetNodeRef`.

The calling app must be one of these, signed with the matching certificate
(SHA-256):

| Package | Allowed signing certs (SHA-256) |
|---|---|
| `ai.droidcommand.app` | debug `88:00:51:A6:D9:5D:38:DD:8D:81:E1:93:1E:17:05:48:03:87:D5:93:C3:09:43:53:99:68:E6:EF:11:A0:C8:4D` and release `9E:A7:B3:14:94:5B:0A:62:97:15:6F:F7:06:E7:0C:0A:B1:B7:24:DE:06:D5:09:9C:A5:12:8A:B4:43:F7:2D:8D` |
| `ai.droidcommand.app.debug` | same two certs |

The release cert is the shared fleet release key that droidcommand-AI signs with.
Victorious Mesh itself is signed with its own, separate release key.

## How it talks

- Bind to the service with intent action `com.victorious.mesh.action.BRIDGE`
  (package `com.victorious.mesh`; debug and release share that
  `applicationId`). There is no `FileProvider` / `android:authorities` in this
  app. Manifest component names are fully qualified `com.tailscale.ipn.*` so
  they stay valid after the Gradle namespace change (`com.victorious.mesh`).
  Kotlin sources remain `com.tailscale.ipn` (gomobile / upstream-merge
  deferral; see README).
- Send a `Message` with `what = 1`, a Bundle with key `"request"` holding a JSON string,
  and `replyTo` set to your own Messenger.
- The answer comes back as `what = 2`, Bundle key `"response"` (JSON string).

JSON, `protocol_major` 1, `protocol_minor` 1. A request that contains top-level
`op` is a v1.1 remote envelope (camelCase fields). A request that contains
`operation` (and no `op`) is a legacy `mesh.*` BridgeRequest. Responses keep the
snake_case wrapper (`protocol_major`, `protocol_minor`, `request_id`, `node_id`).

## Node id

Each install makes one id like `mesh-` followed by 32 lowercase hex characters and keeps it.

- Legacy: put it in `target_node_id`.
- Envelope: put it in `targetNodeRef`.

Any response except `unauthorized` / `unauthorized_caller` includes `node_id`, so
the controller can learn it. Envelope `targetNodeRef` is classified as:
blank or malformed (`mesh-` + 32 lowercase hex) → `invalid_parameters`; a
well-formed id this phone does not know → `not_found`; this phone's id when
the sender is not paired with it → `not_paired`. Legacy `target_node_id`
mismatch is still `node_mismatch`.

## Legacy operations (`mesh.*`)

Still accepted. `parameters` must be `{}`.

| Operation | What it does | Result |
|---|---|---|
| `mesh.capabilities.get` | Lists what this node supports (allowed even when the Settings switch is off) | `operations` (string list), `dca_required: false`, `fleet_class: "external_node"`, `protocol_major`, `protocol_minor` |
| `mesh.status` | Current state | `backend_state` (e.g. `Running`, `Stopped`, `NeedsLogin`), `connected`, `control_url`, `ips`. While a WireGuard profile is connected this reports the mesh down (`connected: false`, empty `ips`). |
| `mesh.up` | Turns the mesh on | `requested: "up"` (the start is asynchronous; poll `mesh.status`). Refused if WireGuard mode is active. |
| `mesh.down` | Turns the mesh off | `requested: "down"`. No-op if WireGuard mode is already active (the mesh is already down). Legacy `mesh.down` still runs immediately (backward compatible). The same action via `remote.command` is gated (see below). |
| `mesh.ip.get` | This phone's mesh addresses | `ipv4`, `ipv6` (null if not connected) |
| `node.status.get` | Status-tray snapshot for this node (canonical standalone read op) | `NodeStatusSnapshot` (see below). Envelope fields live in `parameters`. |

## Remote-control envelope v1.1

Top-level flat fields (camelCase):

| Field | Meaning |
|---|---|
| `op` | `capabilities.get` \| `remote.command` \| `macro.step` \| `macro.run` |
| `requestId` | Correlation id (reused as `request_id` on the response) |
| `targetNodeRef` | This phone's node id |
| `actionRef` | Mesh action for `remote.command` / `macro.step` (e.g. `mesh.status`) |
| `params` | Must be `{}` for mesh actions except the `node.status.get` alias, which may carry `args` and ignores unknown keys |
| `nonce` | 128-bit unique value (32 hex chars, or a UUID) |
| `timestampMs` | Epoch milliseconds |
| `ttlMs` | Default `120000`, max `300000` (above max is **clamped**; `<= 0` is `invalid_parameters`) |
| `destructive` | Advisory only. Real destructiveness comes from the capability catalog. |
| `trigger` | Optional object. **Informational only. Never authorizes anything.** |

`macro.step` also requires `{macroId, stepIndex, onError}` where `onError` is
`abort` or `continue`.

`macro.run` also requires `{macroId, steps, dryRun}`. Each step is
`{actionRef, params, onError}`. `dryRun: true` validates only (no mesh
start/stop, no confirmation notification).

`remote.command` dispatches `actionRef` to a mesh action. Every macro step goes
through the same auth, gating, and capability checks as a standalone command.

### Replay and expiry

An envelope is `expired` when `now > timestampMs + ttlMs`. A timestamp more
than 120 seconds in the future (`timestampMs > now + 120000`) is also
`expired`. There is no past-skew window, so a full 300 second TTL is usable.

A duplicate nonce keyed by `(sourceNodeRef, nonce)` is `replay_detected`.
`sourceNodeRef` is the **caller identity** from `Message.sendingUid` (package
name), not an envelope field. The nonce cache keeps entries at least 10 minutes
and is bounded (oldest entries evicted first).

### Capabilities

`capabilities.get` returns each mesh action as
`{name, kind, remoteAllowed, requiresConfirm}`:

| name | kind | remoteAllowed | requiresConfirm |
|---|---|---|---|
| `mesh.capabilities.get` | `read` | true | false |
| `mesh.status` | `read` | true | false |
| `mesh.ip.get` | `read` | true | false |
| `node.status.get` | `read` | true | false |
| `mesh.up` | `action` | true | false |
| `mesh.down` | `destructive` | true | true |

`mesh.down` is destructive because it cuts the network for every fleet app on
this phone.

### Destructive gate

Destructive actions (`mesh.down` via `remote.command` / macros) **always**
return `confirmation_required` and post a notification. The user must Approve or
Deny **on this phone**. Nothing auto-approves a remote trigger — not
`trigger.autoApprove`, not `destructive: false`.

If notification permission is denied, the request is still
`confirmation_required` (never silently run).

### `node.status.get` (status tray)

Frozen STATUS-TRAY snapshot (`STATUS_TRAY.md`). **Canonical path** is a
`BridgeRequest` with `operation: "node.status.get"` and the frozen v1.1
envelope fields (`nonce`, `timestampMs`, `ttlMs`, `requestId`,
`targetNodeRef`, …) inside `parameters` — not a top-level `op`. It is a
standalone read-only Bridge op: same package+cert auth, replay, and
expiry as remote control. It does **not** enter `remote.command`
confirmation gating. External control matches other read ops
(`mesh.status`): the Settings switch still applies.

`remote.command` with `actionRef: "node.status.get"` is kept only as a
cheap compatible alias (already replay-checked; never confirmation-gated).

On success `result` is one camelCase `NodeStatusSnapshot`:

```json
{
  "nodeId": "mesh-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "packageName": "com.victorious.mesh",
  "connectionClass": "EXTERNAL_NODE",
  "updatedAtMs": 1760920000000,
  "domains": [
    { "name": "mesh", "state": "ok", "detail": "headscale up" },
    { "name": "vpn", "state": "ok", "detail": "mesh tunnel up" },
    { "name": "node_health", "state": "ok" }
  ]
}
```

This node always returns **mesh**, **vpn**, and **node_health**. `state` is
`ok` | `degraded` | `down` | `unknown` | `off`. `detail` is optional, at
most 200 characters, and must not contain secrets, keys, IPs, or personal
data — the codec refuses (`invalid_parameters`) rather than silently
stripping. A down mesh or tunnel still answers `ok: true` with
`state: "down"` (that is data, not an error).

Optional `args.nodeId`: omit or match this node; a well-formed id for
another node is `not_found`; blank or malformed is `invalid_parameters`.
Unknown parameter (and `args`) keys are **ignored** for forward
compatibility — they are not `invalid_parameters`.

**[not device-verified]** Snapshot contents on a real phone have not been
checked.

### WireGuard mode

Unchanged: while a WireGuard profile is connected, `mesh.status` /
`mesh.ip.get` (and the same actions via `remote.command`) report the mesh down,
and `mesh.up` is refused (`safety_refused`). `mesh.down` does not tear the
WireGuard tunnel down. `node.status.get` reports `mesh` as `down` and
`vpn` as `ok` ("WireGuard up") while that tunnel is active.

## Errors

Legacy `mesh.*` BridgeRequests still use the v1.0 categories
(`unauthorized_caller`, `malformed_request`, `protocol_mismatch`,
`node_mismatch`, `unsupported_operation`, `safety_refused`, …).

Remote envelope v1.1 uses:

| Category | Meaning |
|---|---|
| `invalid_parameters` | Missing/bad envelope fields, blank or malformed `targetNodeRef`, bad `ttlMs` / `nonce`, unexpected `params`, bad `onError`. |
| `not_paired` | `targetNodeRef` is this (known) node, but it is not paired with the sender. |
| `not_found` | Unknown / unresolvable `targetNodeRef`, unknown `actionRef`, or no matching pending confirmation. |
| `internal` | Something went wrong inside the app. |
| `unauthorized` | Calling app is not on the allowlist (wrong package or certificate). |
| `replay_detected` | This caller already used this nonce. |
| `expired` | `now > timestampMs + ttlMs`, or `timestampMs` is more than 120 seconds in the future. |
| `external_control_disabled` | The Settings switch is off (everything except `capabilities.get`). |
| `confirmation_required` | Destructive action waiting for an on-phone Approve. |
| `unsupported_op` | `op` is not one of the four envelope operations. |
| `capability_off` | `actionRef` is known but `remoteAllowed` is false. |

(`verify_failed` is part of the shared spec but is not produced by this node.)

Responses never include keys, auth keys, tokens or other credentials.

## Example (legacy)

Request:

```json
{"protocol_major":1,"request_id":"dca-6f1c0f2e-1d8b-4a5e-9a51-2a8f3c1e7b10",
 "target_node_id":"mesh-3f9a1c0b7d2e4f6a8b1c2d3e4f5a6b7c","operation":"mesh.status",
 "timeout_ms":10000,"parameters":{}}
```

Response:

```json
{"protocol_major":1,"protocol_minor":1,"request_id":"dca-6f1c0f2e-1d8b-4a5e-9a51-2a8f3c1e7b10",
 "node_id":"mesh-3f9a1c0b7d2e4f6a8b1c2d3e4f5a6b7c","ok":true,
 "result":{"backend_state":"Running","connected":true,
   "control_url":"https://controlplane.tailscale.com",
   "ips":["100.101.102.103","fd7a:115c:a1e0::1"]},
 "error":null,"progress":null}
```

## Example (envelope v1.1)

Request:

```json
{"protocol_major":1,"protocol_minor":1,"op":"remote.command",
 "requestId":"dca-6f1c0f2e-1d8b-4a5e-9a51-2a8f3c1e7b10",
 "targetNodeRef":"mesh-3f9a1c0b7d2e4f6a8b1c2d3e4f5a6b7c",
 "actionRef":"mesh.status","params":{},
 "nonce":"0123456789abcdef0123456789abcdef",
 "timestampMs":1760928000000,"ttlMs":120000,
 "destructive":false,"trigger":{}}
```

Device behaviour (Messenger bind, notification Approve/Deny, live WireGuard +
envelope) is **[not device-verified]**.
