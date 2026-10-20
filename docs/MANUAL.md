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
| Notifications | Alerts for connection health, sign-in expiry, and Mesh Drop. You can leave the persistent status notification off in system settings. |
| Storage (Android 10 and older) | Saves incoming Mesh Drop files on older Android versions. |
| Foreground service | Keeps the VPN running while the app is in the background. |
| Query all packages | Builds the list of installed apps for split tunneling. |

The VPN service itself is not exported to other apps. The fleet bridge service *is* exported, but every call is checked (see Fleet integration). Android backup is turned **off** for this app.

## Fleet integration

The controller **droidcommand-AI** (`ai.droidcommand.app`) can:

- Read what this node supports
- Read connection status (running or stopped, control server URL, mesh IP addresses)
- Turn the mesh **on**
- Turn the mesh **off**
- Read this phone's mesh IPv4 and IPv6

It cannot read keys, auth keys, or other secrets. It cannot change the Headscale URL or log you in. It cannot start or stop a WireGuard profile.

**While WireGuard mode is on:** `mesh.status` reports the mesh as down (`connected: false`, no mesh IPs). `mesh.up` is refused until you switch back to mesh mode in the app. `mesh.down` does not tear down the WireGuard tunnel.

### External control is off by default

1. Open Victorious Mesh at least once and approve the Android VPN dialog.
2. Go to **Settings**.
3. Switch on **Allow fleet controller (droidcommand-AI)**.

Turn the same switch **off** at any time to block the controller again.

### Package and certificate allowlist

Only these two packages are accepted, and only if they are signed with a pinned certificate (SHA-256):

| Package | Allowed certificates |
|---|---|
| `ai.droidcommand.app` | Debug `88:00:51:A6:D9:5D:38:DD:8D:81:E1:93:1E:17:05:48:03:87:D5:93:C3:09:43:53:99:68:E6:EF:11:A0:C8:4D` and release `9E:A7:B3:14:94:5B:0A:62:97:15:6F:F7:06:E7:0C:0A:B1:B7:24:DE:06:D5:09:9C:A5:12:8A:B4:43:F7:2D:8D` |
| `ai.droidcommand.app.debug` | The same two certificates |

Anything else is rejected. Victorious Mesh is signed with its **own** key; it does not share a signing key with the controller.

### Destructive actions need on-phone confirmation

Remote control is gated on this phone:

- **Allowing control** is an on-phone Settings switch. Nothing remote can turn that switch on for you.
- **Turning the mesh on** from the controller is refused until you have opened this app and accepted the Android VPN permission dialog. That system dialog is the on-phone confirmation for handing the VPN slot to Victorious Mesh. It is also refused while a WireGuard profile is connected — you must tap **Switch back to mesh mode** (or disconnect WireGuard) on the phone first.
- **Turning the mesh off** from the controller, once the switch is on and the caller is allowlisted, happens without a second pop-up. If WireGuard is already running, that request does not stop the WireGuard tunnel. If you do not want the controller to stop the mesh, turn **Allow fleet controller** off.

There is no extra in-app “Type yes to confirm” step beyond VPN permission and the Settings switch.

## Troubleshooting and FAQ

**The app will not log in.** Set **Settings → Control server** to your Headscale URL on HTTPS port 443, save, then try **Log in** again.

**Android never asks for VPN permission, or start fails.** Another VPN is probably running. Turn it off (the app can open system VPN settings), then try **Connect** again.

**The switch does nothing.** Grant VPN permission from the main screen. If a work policy forces the VPN on, the switch may be disabled.

**No devices appear.** Confirm status is **Connected**, that other devices are on the same Headscale user/network, and wait a moment for the list to refresh. Try Search.

**Exit node or internet is blocked.** If an organization-chosen exit node is offline, internet traffic is blocked until that node is back or the policy changes.

**Mesh Drop will not save files.** Pick a Mesh Drop directory under **Settings → Permissions**. On older Android, allow storage.

**The fleet controller cannot connect.** Turn on **Allow fleet controller (droidcommand-AI)**. Confirm droidcommand-AI is the signed package on the allowlist. The controller must use this install's node id (`mesh-` plus 32 hex characters). If you never granted VPN permission, turning the mesh on remotely is refused. If a WireGuard profile is connected, the controller sees the mesh as down and cannot turn the mesh on until you switch back to mesh mode.

**I turned the controller off but I still see a VPN.** The Settings switch only blocks *remote* control. Use the main-screen switch (or the Quick Settings tile) to stop the VPN yourself.

**Can I use this with a second VPN app?** Not at the same time. Android allows one VPN. Mesh and WireGuard inside this app also cannot run together.

**The mesh will not start, and I use WireGuard.** Open **Settings → WireGuard** and tap **Switch back to mesh mode** (or **Disconnect**), then connect the mesh from the main screen.

**Is there an ad blocker or router dashboard?** Not yet — see **Coming soon**.

**Debug vs release.** Debug builds from CI share a fixed debug signature. Release builds use a separate key that is not stored in the project.

## Privacy and data handling

- **Diagnostic log upload is disabled.** The app does not send diagnostic logs to a remote log server. That path is switched off in the build (always reports logging as off; remote upload cannot be turned on). Logs that the VPN core writes stay on the device.
- **There is no in-app bug-report upload.** The old report screen is not linked from Settings, because it targeted that remote log server.
- **Keys stay on this device.** Login and node keys are stored in encrypted app storage. Extra hardware-backed keys use the Android Keystore when the phone supports it. Auth keys you paste are handed to the local VPN engine for login; they are not written to the Control server preference. WireGuard configs, including private keys, stay in the same encrypted on-device store and are not written to logs or error messages. Android backup for this app is off, so those secrets are not copied to cloud backup.
- **The fleet bridge** returns status and IP addresses only — never keys, tokens, or file contents.
- **What the intro text still says.** The first-run paragraph mentions that a name, device name, OS version, and IP are used to connect devices. That text is shown in the app; this build does not upload diagnostic logs, and your Headscale server is the control plane you configured.
- **Licensing.** Victorious Mesh is a modified open-source client (BSD-3-Clause). Credit to the original authors belongs in the license files (`LICENSE`, `NOTICE`, `PATENTS`) and on the About screen. This app is not affiliated with or endorsed by those original authors.
