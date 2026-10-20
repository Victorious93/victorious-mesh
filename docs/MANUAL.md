![Victorious Mesh](images/manual/header.svg)

# Victorious Mesh user manual

This is the guide for the Victorious Mesh app on your phone. Screenshots are not in the files yet — each picture below is a placeholder until they are added.

## What it is and its fleet role

Victorious Mesh is the VPN on this phone. It joins Don's private network, which is run by a Headscale server he hosts himself. Once it is connected, your phone can reach the other devices on that network, and other fleet apps on this phone ride the same private path.

Android only allows **one** VPN at a time. Victorious Mesh takes that single VPN slot. If another VPN app is already on, this one cannot start until you turn the other one off. Inside this app, the **mesh** and a **WireGuard profile** also cannot run together — connecting one turns the other off.

In the fleet, this app is an **external node**. The controller app **droidcommand-AI** (`ai.droidcommand.app`) can look at status and turn the mesh on or off — but only after you switch that on in Settings. Other fleet apps do not run their own VPN; they use this one.

There is no built-in public login server. You point the app at your Headscale URL first.

## Install and first run

![Install and first run](images/manual/first-run.png)

*Screenshot pending.*

1. **Install the APK.** Get a debug build from the project's CI artifact named `victorious-mesh-debug-apk`, or install a build you made yourself. Open the file on the phone and follow Android's install prompts. You may need to allow installing from that source.
2. **Open the app.** The first time, a short welcome screen explains that this is a mesh VPN. Tap **Get Started**.
3. **Set the Headscale URL.** Before you can log in, go to **Settings → Control server** (from the main screen, the gear icon or your avatar opens Settings). Type the server address, for example `https://headscale.example.com`. It must be **HTTPS on port 443**. If you use another port, the app shows a warning. Tap **Save**. There is no default server.
4. **Log in.**
   - **In a browser:** On the main screen tap **Log in**. The app opens a browser page on your Headscale server so you can sign in. On a TV with no browser, a QR code is shown instead so you can finish login on another device.
   - **With a pre-auth key:** On the Control server screen, after the URL is saved, tap **Log in with a pre-auth key**. Paste a key created on the server (`headscale preauthkeys create --user USER`). You can also reach this from **Settings →** your account row **→** the menu **→ Use an auth key**.
5. **Grant VPN permission.** Android asks to allow Victorious Mesh to set up a VPN connection. Accept it. This is required; the mesh cannot run without it. If another VPN is already active, the app explains that only one VPN can run and offers to open system VPN settings.

After login and permission, use the switch at the top of the main screen to connect.

## Features

This section covers what the app can do today, in the order you will usually meet it. Features that are **not** built yet are listed at the end under **Coming soon**.

### Welcome screen

![Welcome](images/manual/welcome.png)

*Screenshot pending.*

The first launch shows a short intro and **Get Started**. After that, the app remembers you have seen it.

### Main screen: connect and status

![Main screen](images/manual/main.png)

*Screenshot pending.*

- The **switch** at the top starts or stops the mesh VPN.
- The line under the network name shows status such as **Connected**, **Not connected**, **Starting…**, **Login required**, or **Authorization required**.
- A **red health icon** next to the status opens the health screen, which lists connection problems. You can copy a message by tapping and holding it.
- The **gear** (when you are logged out) or your **avatar** (when you are logged in) opens Settings.
- A **help** icon on the main screen opens this manual inside the app.

If you are not connected yet, the main screen shows **Connect** or **Log in**, depending on whether you still need an account. If the Headscale URL is empty, **Log in** takes you to Control server first.

If an administrator must approve this phone, you will see **Admin approval required** and a button to open the admin page.

If your sign-in is about to expire, a banner asks you to reauthenticate. Tap it to log in again.

### Control server

![Control server](images/manual/control-server.png)

*Screenshot pending.*

**Settings → Control server**

1. Enter the Headscale URL (`https://…` on port 443).
2. Tap **Save**.
3. Optionally tap **Log in with a pre-auth key** once a URL is saved.

The new URL is used the next time you log in. A work policy named Login URL, if present, overrides what you type.

### Device list, search, and pinning

When the mesh is **Connected**, the main screen lists other devices.

- **Search:** On Android 13 and newer, tap the search bar to open a full search screen. On older Android, type in the search field on the main screen. Search is hidden on Android TV.
- Devices are grouped by person. **Pinned** devices sit at the top.
- **Tap** a device to open its details (addresses, name, and other info). Tap an address to copy it.
- **Touch and hold** a device for **Ping**, **Copy Mesh DNS hostname**, **Copy IPv4**, **Copy IPv6**, and pin or unpin.

### Ping

From the device menu or the ping icon on a device's details screen, Ping measures how long a round trip takes and shows a small chart. Close the sheet when you are done.

### Exit nodes

![Exit nodes](images/manual/exit-nodes.png)

*Screenshot pending.*

An exit node sends your general internet traffic out through another device on the mesh.

1. On the main screen, tap the **exit node** row.
2. Choose **None** to stop using an exit node, or pick a device that is offering one.
3. **Allow LAN access** keeps local network access while an exit node is on (unless a work policy hides this).
4. **Run as exit node** makes *this* phone an exit node for others. Confirm on that screen; the phone then advertises a default route.
5. If **Mullvad VPN** exit nodes are present on your network, a **Mullvad VPN** row lists countries and cities. If none are set up, an info screen explains Mullvad instead.

If a work policy forces an exit node, you cannot pick a different one. If that node is offline, a warning explains that internet traffic is blocked.

Use **Enable** / **Disable** on the main-screen exit node row to turn the chosen exit node on or off without opening the picker.

### DNS

**Settings → DNS settings**

- See whether mesh DNS is on, plus resolvers, search domains, and split DNS routes (tap a value to copy it).
- **Use Victorious Mesh DNS** turns mesh DNS on or off, unless a work policy hides the switch.

### WireGuard

![WireGuard](images/manual/wireguard.png)

*Screenshot pending.*

You can run a standard WireGuard tunnel **instead of** the mesh. They never run at the same time.

1. Open **Settings → WireGuard**.
2. At the top, a **mode banner** shows **Tunnel mode**: **Mesh** or **WireGuard · profile name**, plus **Connected** or **Disconnected**.
3. Add a profile in one of three ways:
   - **Import .conf** — pick a wg-quick file from the system picker.
   - **Paste** — paste the `[Interface]` / `[Peer]` text (you can give it a name).
   - **QR image** — pick a screenshot or photo of a WireGuard QR code.
4. On each profile card you can **Rename**, **Delete**, or **Connect**. Delete asks you to confirm and removes the profile and its keys from this phone. If that profile is the one currently connected, it is disconnected first.
5. Tap **Connect**. Approve the Android VPN permission if asked. The mode banner shows **WireGuard · profile name** and **Connected**. The main screen also shows a banner: **WireGuard is connected** — *The mesh is off. Open WireGuard profiles to disconnect or switch back.* Tap that banner to return to this screen.
6. Tap **Disconnect** on the active card to stop the tunnel, or **Switch back to mesh mode** to return the app to the mesh. Connecting the mesh from the main screen also turns WireGuard off first.

**One tunnel at a time.** Android allows one VPN. Mesh and WireGuard never run together.

While a WireGuard profile is connected, the fleet controller sees the **mesh as down** (not connected, no mesh IPs). Asking it to turn the mesh on (`mesh.up`) is **refused** until you switch back to mesh mode in the app. Turning the mesh off remotely does nothing extra in this mode, because the mesh is already off.

### Proxy

![Proxy](images/manual/proxy.png)

*Screenshot pending.*

**Settings → Proxy**

Two independent features, both **off by default**. **[not device-verified]** — live bind, notification, and leak behaviour on a real phone have not been checked.

#### Share this phone as a mesh proxy

Other devices on the mesh can send **HTTP CONNECT** or **SOCKS5** traffic through this phone, so this phone is an exit for that traffic. Both protocols share one port (the same userspace listeners the mesh engine already has).

1. Connect the mesh first. The listener needs this phone's mesh IPv4.
2. Open **Settings → Proxy**.
3. Turn on **Listen on the mesh IP**.
4. Optionally set a port (1024–65535, default 1080).
5. Optionally set a username and password. Use both or neither. They are stored only in this app's encrypted preferences (Android Keystore).
6. A persistent notification stays up while the listener is running.

**Bind rules.** The listener binds only this phone's own mesh IPv4 (the `100.64`–`100.127` address). It never binds `0.0.0.0`, never Wi‑Fi, and never cellular. Other mesh nodes reach it at `100.x.y.z:port`. IPv6-only nodes cannot start the listener yet.

**While WireGuard mode is on**, the mesh proxy stays off.

There is **no fleet operation** for this. The Fleet Orchestrator must ratify any later remote control.

#### Send this phone through an upstream proxy

This is the other direction: apps on this phone would send internet traffic through an external HTTP or SOCKS5 server.

**What is implemented**

- **HTTP on Android 10 and newer:** the VPN tells Android about that proxy (`VpnService.Builder.setHttpProxy`). That is **advisory**. Apps that ignore the system HTTP proxy do **not** go through it.
- Username and password for an upstream proxy are stored encrypted, but Android's API has **no credential field**, so they cannot be applied.
- If the proxy host is a **literal public or LAN IP**, Android 13+ excludes that IP from the VPN so the phone can still reach the proxy on Wi‑Fi or cellular (avoids a routing loop). A **hostname** cannot be excluded that way. A **mesh IP** upstream is left inside the VPN on purpose.
- **SOCKS5 (advisory):** settings are saved only unless you turn on **Force all traffic**.
- **Force all traffic (off by default):** the mesh engine sends app TCP from the VPN through the proxy (HTTP CONNECT or SOCKS5) and applies username/password. UDP uses SOCKS5 UDP ASSOCIATE when the proxy supports it; otherwise UDP is blocked. Mesh `100.x` / `fd7a:…` addresses stay on the mesh. The proxy host is excluded from the VPN so the phone can still reach it (loop prevention). **[not device-verified]**
- **DNS (when Force all traffic is on):** public DNS (port 53) is sent through the proxy — SOCKS5 UDP ASSOCIATE when the proxy supports it, otherwise DNS over TCP (RFC 7766) via CONNECT or SOCKS5. Mesh / MagicDNS names stay on the mesh. DNS is fail-closed: if the proxy is down, public lookups are blocked instead of going out on Wi‑Fi or cellular. The Proxy screen shows the live DNS path (waiting / UDP / TCP / blocked). **[not device-verified]**
- **IPv6 (when Force all traffic is on):** IPv6 is sent through the proxy when the upstream supports it; otherwise IPv6 is blocked and is never leaked on the underlay. The Proxy screen shows the live IPv6 path (checking / through the proxy / blocked). **[not device-verified]**
- **Fail closed (off by default):** if the proxy is down, those flows are blocked instead of going around it. Public DNS is always fail-closed, even when this switch is off.

**Gaps that remain.** ICMP cannot go through SOCKS/CONNECT and is dropped. Apps set to **Direct** in Per-app routing skip the VPN and this path. WireGuard mode does not use the forced path. After you change these settings, disconnect and reconnect the mesh.

### Per-app routing

![Per-app routing](images/manual/per-app-routing.png)

*Screenshot pending.*

**Settings → Per-app routing**

Pick a route for each installed app. **[not device-verified]**

1. **Direct** — that app skips the VPN (Wi‑Fi or cellular).
2. **Mesh only** — that app uses this phone's mesh tunnel.
3. **Exit node** — you can pick a Headscale exit node to remember, but Android has **one** VPN. The exit node is device-wide. The app still uses the same tunnel as Mesh only.
4. **Upstream proxy** — remembered only. The proxy in **Settings → Proxy** is device-wide.

**What IP and country sites see** fetches a public IP (and country if the JSON has one) from a URL you set, on demand only. The request is made by Victorious Mesh itself, so it shows *this* app's path, not the other app's Direct path.

**Limits.** One tunnel cannot give each app a different exit node or proxy. Per-app traffic totals are not tracked (that would need a usage-access permission this screen does not ask for). No GPS spoofing (VictorSpoof owns that). MDM split-tunnel lists still win over these choices. There is **no fleet operation** for this.

### Kill switch

![Kill switch](images/manual/kill-switch.png)

*Screenshot pending.*

**Settings → Kill switch**

**Off by default.** **[not device-verified]**

When this is on and you have started the mesh or WireGuard, the app treats that as “the tunnel should stay up”:

1. An unexpected drop (service killed, TUN closed, Always-on restart) holds a **blocking** VPN so apps cannot fall back to Wi‑Fi or cellular, then tries to reconnect.
2. Turn the **main-screen switch** or the Quick Settings tile **off** to leave. That is an explicit disconnect and tears the hold down.
3. A link opens Android’s VPN settings. **Always-on VPN** plus **Block connections without VPN** is the stronger, system-level lock. Use that if you need the OS to refuse traffic whenever this app is not the VPN.

**Limits.** Another app taking the VPN slot (`onRevoke`) cannot be held — the system gives that app the interface. Apps set to **Direct** in Per-app routing still bypass. The blackhole uses documentation addresses (`192.0.2.1` / `2001:db8::1`) and is replaced when the real tunnel comes back. IPv6 hold can fail on some devices. There is **no fleet operation** for this.

### Auto-connect

![Auto-connect](images/manual/auto-connect.png)

*Screenshot pending.*

**Settings → Auto-connect**

**Off by default.** **[not device-verified]**

When this is on, the last tunnel mode (mesh, or the last WireGuard profile) starts:

1. When you open the app.
2. After the phone finishes booting (after the first unlock).
3. Immediately when you turn this switch on, if VPN permission is already granted.

**Limits.** Android 12 and newer may block a start from boot. **Always-on VPN** in Android settings is more reliable for that. This does not start before the first unlock. It does not start if you have never granted VPN permission (`isAbleToStartVPN` is false). Turning the main switch off still disconnects; the next app open or reboot will start again while this setting is on. There is **no fleet operation** for this.

### Leak checker

![Leak checker](images/manual/leak-checker.png)

*Screenshot pending.*

**Settings → Leak checker**

**Off by default** — nothing runs until you tap **Run check**. **[not device-verified]**

The report covers this app only:

1. Whether the VPN is up.
2. Whether the kill switch is on.
3. How many apps are set to **Direct** (those skip the tunnel on purpose).
4. Mesh DNS on or off.
5. A public IP from the URL in **Settings → Per-app routing** (this app's path, not another app's).

**Limits.** This cannot prove other apps are leak-free. WebRTC/STUN, IPv6 bypass, and sockets opened before the VPN are not tested. There is **no fleet operation** for this.

### VictorGPS stub

![VictorGPS stub](images/manual/victorgps.png)

*Screenshot pending.*

**Settings → VictorGPS stub**

**Off by default.** **[not device-verified]**

This is a **local room profile** only (latitude, longitude, optional target-app package), matching the profile shape in Victorious93/victorgps PR #1. It does **not** mock GPS. VictorSpoof owns mock location.

Expected pairing app: `com.victorious.gps`. Debug cert SHA-256 `38:AE:B4:F2:B9:A6:40:C5:46:FB:5D:DC:45:EA:79:BE:8E:89:BC:2C:76:9D:3C:87:B6:4B:F9:7A:27:B0:38:49`. Release cert TBD.

**Not on the fleet allowlist.** VictorGPS cannot call the fleet bridge. There is **no fleet operation** for this.

### App split tunneling

**Settings → App split tunneling**

Choose which apps may use the mesh VPN.

1. Tick apps in the list.
2. Open the menu to switch between “selected apps use the mesh” and “selected apps skip the mesh and use the internet directly.”
3. A work policy can lock this list; the screen then explains that only certain apps are included or excluded.

### Mesh network lock

**Settings → Mesh network lock** (shown when the work policy allows it)

This lets devices check keys from the coordination server before trusting them. The screen shows whether lock is on, plus copyable **node key** and **mesh network lock key** values, and a link to Headscale docs.

### Subnet routing

**Settings → Subnet routing** (shown when you are allowed to decide)

- **Use Victorious Mesh subnets** uses routes advertised by other devices.
- You can add, edit, or delete CIDR routes this phone advertises. Routes still need approval on the server. An info button opens Headscale routing docs.

### Permissions, notifications, and Mesh Drop folder

**Settings → Permissions** (not shown on Android TV)

- **Notifications:** See whether they are on, read a short explanation, and jump to system notification settings.
- **Mesh Drop directory:** Pick the folder where incoming files are saved. The first incoming transfer may ask you to pick a folder.

On Android 12 and older, the app may also ask for storage access so Mesh Drop can save files.

### Mesh Drop (send and receive files)

**Receive:** When someone sends you files, a banner or notification appears. Open the sheet to handle items, copy text, or open the Mesh Drop folder.

**Send:** From another app, use Android **Share** and pick Victorious Mesh. Choose a device (recent or all). Sharing needs the mesh to be connected; otherwise you are asked to connect first.

### Accounts

**Settings →** tap your user row (after VPN permission has been granted)

- Switch to another saved account.
- **Add an account** starts a browser login.
- **Reauthenticate** logs in again for the current account.
- **Log out** signs this phone out.
- **Delete** opens a confirmation. That dialog currently sends you to a support web page rather than deleting the network itself.
- The **⋮** menu: **Use an auth key**, or log in to an **alternate server** (a different control URL).

### Quick Settings tile

Add the **Victorious Mesh** tile in Android Quick Settings to toggle the VPN without opening the app. You still need VPN permission from the first run.

### About

**Settings → About Victorious Mesh** shows the version (tap the version to copy the full string) and acknowledgements. A short note credits the open-source client this app is based on.

### Debug options

On **debug** builds only, Settings includes **MDM settings**, a list of work-policy values. Release builds do not show this.

### Help

This manual is also in the app: tap **Help** on the main screen, or **Settings → Help / Manual**.

### Coming soon

These are **not** in the app yet:

- **Ad blocker**
- **OpenWRT router dashboard**

## Permissions

Victorious Mesh asks for the following. Android's VPN permission is separate from the list in the app store page; you grant it in a system dialog.

| Permission | Why it is there |
|---|---|
| VPN service | Creates the one device-wide VPN so mesh traffic can flow. Without this, the app cannot connect. |
| Internet | Talks to your Headscale server and to other devices on the mesh. |
| Network state | Notices when Wi‑Fi or mobile data changes so the mesh can recover. |
| Wi‑Fi state | Lets the mesh see Wi‑Fi changes (declared for the VPN stack). |
| Change network state | Lets the VPN attach to the system network (declared for the VPN stack). |
| Notifications | Alerts for connection health, sign-in expiry, Mesh Drop, and the persistent mesh-proxy notification while that listener is on. You can leave the VPN status notification off in system settings. |
| Storage (Android 10 and older) | Saves incoming Mesh Drop files on older Android versions. |
| Boot completed | After you turn on Auto-connect, the app may try to start the last tunnel when the phone finishes booting. Android 12+ can still refuse that start. |
| Foreground service | Keeps the VPN running while the app is in the background. |
| Query all packages | Builds the list of installed apps for split tunneling. |

The VPN service itself is not exported to other apps. The fleet bridge service *is* exported, but every call is checked (see Fleet integration). Android backup is turned **off** for this app.

## Fleet integration

The controller **droidcommand-AI** (`ai.droidcommand.app`) can:

- Read what this node supports
- Read connection status (running or stopped, control server URL, mesh IP addresses)
- Read a short **status tray** snapshot (`node.status.get`): mesh, VPN, and whether this app is alive. That answer does not include keys or IP addresses. The controller asks with a normal bridge request; turning the mesh off remotely still needs the on-phone tap, but this status read does not.
- **Export** this app's non-secret settings (`config.export`): control server URL, tunnel mode, ordinary prefs, and WireGuard profile names. Never keys, tokens, or passwords.
- **Import** a settings backup (`config.import`) after you tap Approve on this phone. A dry run only shows what would change. Import never turns on fleet remote control.
- Turn the mesh **on**
- Turn the mesh **off**
- Read this phone's mesh IPv4 and IPv6

It cannot read keys, auth keys, or other secrets. It cannot log you in. It cannot start or stop a WireGuard profile, and a settings import never copies WireGuard private keys.

**Victorious Browser** (`com.victorious.browser`) is a read-only fleet app. After you switch on **Allow fleet controller**, it may only ask for connection status and this phone's mesh IP. It cannot turn the mesh on or off, cannot read the status-tray snapshot, and cannot use the newer remote-control commands. Other operations are refused as not authorized. Mesh IP addresses are never written to logs or backups. **[not device-verified]**

**While WireGuard mode is on:** `mesh.status` reports the mesh as down (`connected: false`, no mesh IPs). `mesh.up` is refused until you switch back to mesh mode in the app. `mesh.down` does not tear down the WireGuard tunnel. The status-tray snapshot also shows the mesh as down and the VPN as up (the WireGuard tunnel).

### External control is off by default

1. Open Victorious Mesh at least once and approve the Android VPN dialog.
2. Go to **Settings**.
3. Switch on **Allow fleet controller (droidcommand-AI)**.

Turn the same switch **off** at any time to block the controller again.

### Package and certificate allowlist

Only these packages are accepted, and only if they are signed with a pinned certificate (SHA-256):

| Package | Scope | Allowed certificates |
|---|---|---|
| `ai.droidcommand.app` | Full | Debug `88:00:51:A6:D9:5D:38:DD:8D:81:E1:93:1E:17:05:48:03:87:D5:93:C3:09:43:53:99:68:E6:EF:11:A0:C8:4D` and release `9E:A7:B3:14:94:5B:0A:62:97:15:6F:F7:06:E7:0C:0A:B1:B7:24:DE:06:D5:09:9C:A5:12:8A:B4:43:F7:2D:8D` |
| `ai.droidcommand.app.debug` | Full | The same two certificates |
| `com.victorious.browser` | Status and mesh IP only | Debug `2D:5D:9E:8B:53:8E:78:51:3D:B5:41:F4:DF:FD:67:BE:27:FF:EC:3F:37:EB:1A:D4:8D:77:83:AE:59:27:C3:E1` (release certificate not set yet) |

Anything else is rejected. Victorious Mesh is signed with its **own** key; it does not share a signing key with the controller.

### Destructive actions need on-phone confirmation

Remote control is gated on this phone:

- **Allowing control** is an on-phone Settings switch. Nothing remote can turn that switch on for you.
- **Turning the mesh on** from the controller is refused until you have opened this app and accepted the Android VPN permission dialog. That system dialog is the on-phone confirmation for handing the VPN slot to Victorious Mesh. It is also refused while a WireGuard profile is connected — you must tap **Switch back to mesh mode** (or disconnect WireGuard) on the phone first.
- **Turning the mesh off** from the newer remote-control commands needs a tap on this phone. You get a notification asking you to **Approve** or **Deny**. Until you approve it, the mesh stays up. A leftover older command can still turn the mesh off without that notification; if you do not want that, leave **Allow fleet controller** off. If WireGuard is already running, turning the mesh off does not stop the WireGuard tunnel.

Nothing in the controller's request — including any “trigger” or “already approved” flag — can skip that tap. The phone never auto-approves a remote turn-off.

### Remote-control envelope (v1.1)

The controller can send a newer kind of request on the same bridge. In plain language:

- It can ask what this phone allows (`capabilities.get`) even when the Settings switch is off. That answer is a list only; it does not turn the mesh on or off.
- It can ask for the status-tray snapshot (`node.status.get`) as its own read-only bridge request (with the same one-time, time-limited fields). That path does not go through the “turn the mesh off” confirmation. An older `remote.command` form still works as a compatible alias.
- It can export or import this app's settings as their own bridge requests (`config.export` / `config.import`), not as a remote command. Import without a dry run always asks you to Approve or Deny on this phone. A dry run only reports the planned diff.
- It can run one mesh action (`remote.command`) or a short list of steps (`macro.step` / `macro.run`). A “dry run” checks the list without doing anything.
- Each request is time-limited (up to five minutes) and can be used only once, so an old or copied command is rejected. A request stamped more than two minutes in the future is also rejected. A command that is a few minutes old is still accepted if it is inside its time limit.
- The command must name this phone. A blank or badly formed name is rejected as invalid. A well-formed name for some other phone is treated as not found. This phone, if the sender is not paired with it, is rejected as not paired.
- The phone decides who is allowed by looking at which app sent the message and how that app is signed. Fields inside the message never grant access.

The controller binds this app by package `com.victorious.mesh` plus the action `com.victorious.mesh.action.BRIDGE`. Debug and release share that package.

Technical field names, errors, and examples are in [FLEET_BRIDGE.md](FLEET_BRIDGE.md).

### How to export and import this app's settings

The fleet controller can ask this phone for a settings snapshot, or offer one
back. That snapshot is **this app only** (`com.victorious.mesh`). It includes
the Headscale / control URL, whether the tunnel is mesh or WireGuard, ordinary
prefs (corporate DNS, want-running, exit-node LAN access), and WireGuard
profile **names**. It does **not** include signing keys, Headscale or Tailscale
auth keys, node keys, WireGuard private or preshared keys, API tokens, or
passwords. If a backup would contain any of those names, the phone refuses it
instead of stripping them.

To restore:

1. The controller can ask for a **dry run** first. That returns a list of what
   would change and writes nothing.
2. A real import posts an **Approve fleet action?** notification. Tap
   **Approve** or **Deny** on this phone. Nothing in the request can skip that
   tap.
3. Choose how clashes are handled: **skip** (default — keep what is already on
   the phone), **overwrite** (take the backup's row), or **merge-by-id** (keep
   live rows and add missing ids).
4. After a restore, **Allow fleet controller** stays **off**. Import cannot
   turn remote control on.

A new WireGuard profile name in a backup does not create a working tunnel —
the private key is never in the file. You still add the `.conf` yourself
under **Settings → WireGuard**.

**[not device-verified]** The envelope, time checks, pairing errors, the Approve/Deny notification, the `node.status.get` snapshot, and settings export/import on a real phone have not been run.

## Troubleshooting and FAQ

**The app will not log in.** Set **Settings → Control server** to your Headscale URL on HTTPS port 443, save, then try **Log in** again.

**Android never asks for VPN permission, or start fails.** Another VPN is probably running. Turn it off (the app can open system VPN settings), then try **Connect** again.

**The switch does nothing.** Grant VPN permission from the main screen. If a work policy forces the VPN on, the switch may be disabled.

**No devices appear.** Confirm status is **Connected**, that other devices are on the same Headscale user/network, and wait a moment for the list to refresh. Try Search.

**Exit node or internet is blocked.** If an organization-chosen exit node is offline, internet traffic is blocked until that node is back or the policy changes.

**Mesh Drop will not save files.** Pick a Mesh Drop directory under **Settings → Permissions**. On older Android, allow storage.

**The fleet controller cannot connect.** Turn on **Allow fleet controller (droidcommand-AI)**. Confirm droidcommand-AI is the signed package on the allowlist. The controller must use this install's node id (`mesh-` plus 32 hex characters). Bind package `com.victorious.mesh` (debug and release share it) and action `com.victorious.mesh.action.BRIDGE`. If you never granted VPN permission, turning the mesh on remotely is refused. If a WireGuard profile is connected, the controller sees the mesh as down and cannot turn the mesh on until you switch back to mesh mode. If the controller asks to turn the mesh **off** and nothing happens, look for an **Approve fleet action?** notification on this phone.

**Why some code still says `com.tailscale.ipn`.** The install id and Gradle namespace (`R` / `BuildConfig`) are `com.victorious.mesh`. The Kotlin source package is still `com.tailscale.ipn` on purpose: a full rename would risk the gomobile `libtailscale` bindings and a large merge against upstream tailscale-android. That move is deferred.

**I turned the controller off but I still see a VPN.** The Settings switch only blocks *remote* control. Use the main-screen switch (or the Quick Settings tile) to stop the VPN yourself.

**Can I use this with a second VPN app?** Not at the same time. Android allows one VPN. Mesh and WireGuard inside this app also cannot run together.

**The mesh will not start, and I use WireGuard.** Open **Settings → WireGuard** and tap **Switch back to mesh mode** (or **Disconnect**), then connect the mesh from the main screen.

**The mesh proxy will not start.** Connect the mesh first (not WireGuard). The listener needs this phone's `100.x` address. Check the port is 1024–65535, and that username and password are both set or both empty. **[not device-verified]**

**Internet still bypasses my upstream proxy.** Advisory HTTP (Android 10+) is not forced. Turn on **Force all traffic** to send VPN TCP, public DNS, and IPv6 through the proxy. UDP still needs SOCKS5 UDP ASSOCIATE. If the proxy cannot carry IPv6, IPv6 is blocked instead of leaking. ICMP, Direct apps, and WireGuard mode are documented gaps. See **Settings → Proxy**.

**The kill switch is on but I still have internet after a drop.** Turn the main switch off only when you want to leave. Direct apps skip the VPN. If another VPN app took the slot, this app cannot hold it. Use Android **Always-on VPN** and **Block connections without VPN** for a system lock. **[not device-verified]**

**Auto-connect did not start after reboot.** Grant VPN permission once first. Android 12+ may block a start from boot — use **Always-on VPN**. The app does not start before the first unlock. **[not device-verified]**

**Is there an ad blocker or router dashboard?** Not yet — see **Coming soon**.

**Debug vs release.** Debug and release both install as `com.victorious.mesh`. Debug builds from CI share a fixed debug signature. Release builds use a separate key that is not stored in the project.

## Privacy and data handling

- **Diagnostic log upload is disabled.** The app does not send diagnostic logs to a remote log server. That path is switched off in the build (always reports logging as off; remote upload cannot be turned on). Logs that the VPN core writes stay on the device.
- **There is no in-app bug-report upload.** The old report screen is not linked from Settings, because it targeted that remote log server.
- **Keys stay on this device.** Login and node keys are stored in encrypted app storage. Extra hardware-backed keys use the Android Keystore when the phone supports it. Auth keys you paste are handed to the local VPN engine for login; they are not written to the Control server preference. WireGuard configs, including private keys, stay in the same encrypted on-device store and are not written to logs or error messages. Optional proxy usernames and passwords use that same encrypted store. Android backup for this app is off, so those secrets are not copied to cloud backup.
- **The fleet bridge** returns status and IP addresses only — never keys, tokens, or file contents. Mesh IP addresses are not written to logs or backups.
- **What the intro text still says.** The first-run paragraph mentions that a name, device name, OS version, and IP are used to connect devices. That text is shown in the app; this build does not upload diagnostic logs, and your Headscale server is the control plane you configured.
- **Licensing.** Victorious Mesh is a modified open-source client (BSD-3-Clause). Credit to the original authors belongs in the license files (`LICENSE`, `NOTICE`, `PATENTS`) and on the About screen. This app is not affiliated with or endorsed by those original authors.
