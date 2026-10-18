# Fleet bridge (droidcommand-AI control)

Victorious Mesh can be controlled by the fleet controller app **droidcommand-AI**
(`ai.droidcommand.app`). The controller can check the mesh status, read this phone's
mesh IP addresses, and turn the mesh on or off. Nothing else.

## Turning it on

It is **off by default**. To allow it:

1. Open Victorious Mesh at least once and approve the VPN permission.
2. Go to **Settings** and switch on **Allow fleet controller (droidcommand-AI)**.

Turn the switch off at any time to block the controller again.

## Who is allowed

Every request is checked. The calling app must be one of these, signed with the matching
certificate (SHA-256):

| Package | Signing cert SHA-256 |
|---|---|
| `ai.droidcommand.app` | `88:00:51:A6:…:A0:C8:4D` (debug cert) |
| `ai.droidcommand.app.debug` | same |

The release certificate is not known yet and is **not** allowed until it is added in
`CallerAllowlist` (`FleetProtocol.kt`, marked `TODO(release cert)`).

## How it talks

- Bind to the service with intent action `com.victorious.mesh.action.BRIDGE`
  (package `com.victorious.mesh`).
- Send a `Message` with `what = 1`, a Bundle with key `"request"` holding a JSON string,
  and `replyTo` set to your own Messenger.
- The answer comes back as `what = 2`, Bundle key `"response"` (JSON string).

The order of checks is: caller allowed → control switched on → valid JSON → `protocol_major`
is 1 → `target_node_id` is this phone → run the operation.

## Node id

Each install makes one id like `mesh-` followed by 32 lowercase hex characters and keeps it.
The controller must put it in `target_node_id`. (Any response except `unauthorized_caller`
includes `node_id`, so the controller can learn it, e.g. via `mesh.capabilities.get`.)

## Operations

| Operation | What it does | Result |
|---|---|---|
| `mesh.capabilities.get` | Lists what this node supports | `operations`, `dca_required: false`, `fleet_class: "external_node"`, `protocol_major` |
| `mesh.status` | Current state | `backend_state` (e.g. `Running`, `Stopped`, `NeedsLogin`), `connected`, `control_url`, `ips` |
| `mesh.up` | Turns the mesh on | `requested: "up"` (the start is asynchronous; poll `mesh.status`) |
| `mesh.down` | Turns the mesh off | `requested: "down"` |
| `mesh.ip.get` | This phone's mesh addresses | `ipv4`, `ipv6` (null if not connected) |

`parameters` must be `{}` for all of these.

## Errors

| Category | Meaning |
|---|---|
| `unauthorized_caller` | The calling app isn't on the allowlist (wrong package or certificate). |
| `external_control_disabled` | The Settings switch is off. |
| `malformed_request` | Not valid JSON or missing required fields. |
| `protocol_mismatch` | `protocol_major` is not 1. |
| `node_mismatch` | `target_node_id` is not this phone. |
| `unsupported_operation` | Unknown operation. |
| `invalid_parameters` | Unexpected `parameters` or bad `timeout_ms`. |
| `safety_refused` | `mesh.up` without VPN permission: the user must open the app once. |
| `internal` | Something went wrong inside the app. |

(`verify_failed` is part of the shared spec but is not produced by this node.)

Responses never include keys, auth keys, tokens or other credentials.

## Example

Request:

```json
{"protocol_major":1,"request_id":"dca-6f1c0f2e-1d8b-4a5e-9a51-2a8f3c1e7b10",
 "target_node_id":"mesh-3f9a1c0b7d2e4f6a8b1c2d3e4f5a6b7c","operation":"mesh.status",
 "timeout_ms":10000,"parameters":{}}
```

Response:

```json
{"protocol_major":1,"request_id":"dca-6f1c0f2e-1d8b-4a5e-9a51-2a8f3c1e7b10",
 "node_id":"mesh-3f9a1c0b7d2e4f6a8b1c2d3e4f5a6b7c","ok":true,
 "result":{"backend_state":"Running","connected":true,
   "control_url":"https://controlplane.tailscale.com",
   "ips":["100.101.102.103","fd7a:115c:a1e0::1"]},
 "error":null,"progress":null}
```
