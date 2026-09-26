# Relay / ovpn-pin

![Relay network banner](docs/relay-banner.png)

[English](README.md) · [فارسی](README.fa.md)

## Choose your setup

| Your computer | Start here |
| --- | --- |
| Windows 10/11, 64-bit | [Install the Relay app](#windows-relay-app) |
| Linux | [Install the `ovpn` command](#linux) |

You need an account with a supported VPN provider. The download does not include an account or password. For the Windows app, use Surfshark or Windscribe. The Linux steps below use the Surfshark OpenVPN configs included in this repository; you can also put your own provider's `.ovpn` files in `configs/`.

## Windows: Relay app

### 1. Download the app

1. Open the [latest GitHub release](https://github.com/120hdd/ovpn-pin/releases/latest).
2. Under **Assets**, download the file named `Relay-windows-x64-….zip`. Download the matching `.zip.sha256` file if you want to check the download. Do not choose **Source code**: that is the repository, not the ready-to-run app.
3. In File Explorer, right-click the ZIP and choose **Extract All**. Open the extracted folder that contains `Relay.exe`. Keep its contents together.

No Git, Python, or installer is needed for this route.

<details>
<summary>Optional: check the ZIP before opening it</summary>

Put the ZIP and its `.sha256` file in the same folder. Open PowerShell in that folder and run:

```powershell
Get-FileHash .\Relay-windows-x64-*.zip -Algorithm SHA256
Get-Content .\Relay-windows-x64-*.zip.sha256
```

The two long hashes should match. Windows may show a SmartScreen warning because the app is not code-signed; check the release and checksum before choosing **More info → Run anyway**.

</details>

### 2. Add your account

1. Double-click `Relay.exe` in the extracted folder.
2. Click the gear icon, then open **Accounts** and choose **Add an account**.
3. Choose a provider:
   - **Surfshark:** enter the **service username and password** from Surfshark's manual setup page. Your usual login email is not the service username.
   - **Windscribe:** sign in with your Windscribe account and complete the prompt shown by the app.
4. Under **Connect through**, select the provider you just added. The release already includes a small set of starter servers. If the app asks for more servers, use the action shown on that provider's card in **Accounts**.

### 3. Connect

1. Close Settings and leave the route set to **Provider**.
2. Choose an **Exit location**, or leave **Whichever answers first** selected.
3. Click **Connect**. When connected, the **Seen as** address should appear.
4. Click **Disconnect** when finished.

Relay keeps account data and settings in `%LOCALAPPDATA%\Relay`. You can move the extracted app folder later without moving those settings.

### If it does not connect

- Check the account in **Settings → Accounts**. Surfshark needs its service credentials.
- If the server list is empty, fetch servers from the provider card in **Accounts**. For your own `.ovpn` files, use **Settings → Servers → Pin them to real addresses**.
- If Windows still points to a proxy after an unexpected shutdown, open Relay again and disconnect; it restores the previous system proxy setting.

For the Windows PowerShell tools, see [windows/README.md](windows/README.md). For app build and advanced settings, see [app/README.md](app/README.md).

## Linux

The commands below are for Ubuntu or Debian. On another distribution, install the equivalent packages: Git, curl, OpenVPN, and iproute2. Run the commands in a terminal.

### 1. Install the required packages

```bash
sudo apt update
sudo apt install git curl openvpn iproute2 nano
```

### 2. Get the files

**With Git:**

```bash
git clone https://github.com/120hdd/ovpn-pin.git
cd ovpn-pin
```

**Without Git:** open the [repository page](https://github.com/120hdd/ovpn-pin), click **Code → Download ZIP**, and extract it. In your file manager, open the extracted `ovpn-pin-main` folder, right-click an empty area, and choose **Open in Terminal**. All remaining commands assume the terminal is in that folder. Keep the folder after setup: the installed command points back to it.

### 3. Add your VPN credentials

The included configs are Surfshark configs. Copy the example file, make it private, and edit the two values at its top:

```bash
cp .env.example .env
chmod 600 .env
nano .env
```

```ini
OVPN_USER=your-service-username
OVPN_PASS=your-service-password
```

Save in nano with **Ctrl+O**, **Enter**, then **Ctrl+X**. Use the Surfshark *service* credentials from its manual setup page, not your account email. If you have another provider's `.ovpn` files, copy them into `configs/` first and use the credentials that provider gives for OpenVPN.

### 4. Install the command and prepare the configs

```bash
bash linux/ovpn install
./linux/ovpn pin
```

The first command adds `ovpn` to `~/.local/bin`. The second reads `configs/` and writes ready-to-use copies into `pinned/`; it does not overwrite the originals. The installer prints a PATH command if this terminal cannot find `ovpn` yet. You can keep using `./linux/ovpn` from the repository instead.

### 5. Connect and disconnect

```bash
./linux/ovpn connect
```

Pick a number from the displayed list. Enter your Linux password when `sudo` asks; OpenVPN needs administrator access to create the tunnel. Then:

```bash
./linux/ovpn status
./linux/ovpn stop
```

In a new terminal, `ovpn connect`, `ovpn status`, and `ovpn stop` work from anywhere if `~/.local/bin` is on your PATH.

### If it does not connect

- If `pin` cannot reach DNS-over-HTTPS and you already have a local HTTP proxy, try `./linux/ovpn pin --proxy http://127.0.0.1:10808` (replace the port with yours).
- If the saved addresses are old, run `./linux/ovpn sync` and try again.
- If OpenVPN rejects the login, check the two values in `.env`, then run `./linux/ovpn pin` again.
- If `ovpn: command not found`, open a new terminal or use `./linux/ovpn` while in the repository folder.

More commands and Linux options: [linux/README.md](linux/README.md).

## Set up a tunnel of your own

This is optional. First complete the Windows or Linux setup above. In the Windows app, open **Settings → Your own tunnel** and follow its server setup instructions. You will need a server and a domain; the server installer prints the values to enter in Relay. The equivalent Linux commands are in [linux/README.md](linux/README.md#the-proxy-and-your-own-tunnel).
