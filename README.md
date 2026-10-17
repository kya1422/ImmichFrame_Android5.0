<div align="center">
  <a href="https://github.com/immichFrame/ImmichFrame_Android">
    <img src="https://github.com/immichFrame/ImmichFrame_Desktop/blob/main/src-tauri/icons/icon.png" alt="Logo" width="200" height="200">
  </a>

  <h3 align="center">ImmichFrame Android</h3>

  <p align="center">
    Android client for <a href="https://github.com/immichFrame/ImmichFrame">ImmichFrame</a>.
    <br />
    <br />
    <a href="https://immichframe.dev">Documentation</a>
  <p>
</div>

## 📄 Documentation
You can find the documentation [here](https://immichframe.dev).

## 🗓 Active Times (scheduled screen off/on)

The frame can switch itself off (screen off + device lock) outside the hours configured under
**Settings → Active Times** and come back on at the next start time.

What the app does to make that reliable:

* **Diagnostics screen** (Settings → Diagnostics): shows the stored schedule, the next sleep/wake
  time, whether a wake-up alarm is armed, whether the device admin really holds the force-lock
  policy, the Android/device info and a rolling event log (kept on the device, shareable).
  Buttons: *Arm wake test (+1 min)*, *Sleep now + wake in 30 s*, *Check now*, *Clear log*.
* **Wake-up alarm via `setAlarmClock()`**: the most reliable mechanism on every Android version,
  visible to the system (and to the Diagnostics screen). Falls back to `setExact()`/`set()`.
* **Keep-Alive Service** (Settings → Reliability): optional foreground service for devices that
  kill backgrounded apps (Huawei/EMUI, Xiaomi/MIUI, ...). It shows a permanent notification and
  re-checks the schedule every minute. During the active hours it holds a partial wake lock so the
  frame keeps rendering; outside them it **releases the wake lock** (the device may sleep) and only
  keeps the wake-up alarm armed, so it does not drain the battery overnight.
* **Boot receiver**: re-arms the alarm and restarts the keep-alive service after a reboot.
* **Auto-save** in the schedule editor: leaving the editor with the back button no longer throws
  the draft away.
* The schedule is re-evaluated immediately when the settings change, and the Active Times summary
  shows the next sleep/wake time.

### Huawei / EMUI (e.g. dtab d-01H, Android 5.1)

EMUI closes background apps when the screen is locked, which also cancels the wake-up alarm.
In addition to enabling the keep-alive service:

1. Open **Phone Manager (端末管理)** → **Protected apps (保護されたアプリ)** and protect ImmichFrame
   (EMUI ≤ 4.9). On newer EMUI: **Battery → App launch** → set ImmichFrame to *Manage manually*
   with *Auto-launch* and *Run in background* enabled.
2. Allow ImmichFrame to auto-start after a reboot if a *Startup manager* exists.

### Known limitations

* Android 10+ forbids bringing an activity to the foreground from the background. On those
  devices the wake-up alarm still fires (and the event log shows it), but the screen may only
  light up when the app is already visible (e.g. as the launcher/home app or with the keep-alive
  service running).
* The screensaver (Daydream) mode is started by Android itself, so the Active Times schedule
  cannot switch it on; it is closed when the frame is supposed to sleep.

## 🔏 Release signing

The APKs attached to the GitHub releases are signed with a **stable key** so that a new release
installs over the previous one (an APK can only replace another one when the signature matches).

The keystore lives outside the repository; the CI job reads it from repository secrets:

| Secret | Content |
| --- | --- |
| `IMMICHFRAME_KEYSTORE_BASE64` | the keystore file, base64 encoded (`base64 -w0 keystore.jks`) |
| `IMMICHFRAME_KEYSTORE_PASSWORD` | store password |
| `IMMICHFRAME_KEY_ALIAS` | key alias |

Without these secrets the build falls back to the runner's throwaway debug key (buildable, but such
an APK cannot update an installed one). Locally, Android Studio keeps using the default debug key.

> Keep a backup of the keystore and its password. If it is lost, published builds can only be
> installed after uninstalling the app (which also deletes its settings).

## 🔐 Client certificate (Cloudflare mTLS)

Servers that are protected by Cloudflare Access "Mutual TLS" (or any other proxy that asks for a
TLS client certificate) can be reached by importing a client certificate into the app:

1. Open **Settings → Client Certificate (mTLS) → Import Client Certificate**.
2. Select either
   * the **PKCS#12** file (`.p12`/`.pfx`) — you will be asked for its password, or
   * the **PEM** files of the certificate and the private key (the two files the Cloudflare
     dashboard hands out, `cert.pem` + `key.pem`). Both files can be selected at once; the order
     does not matter.
3. Enable **Use Client Certificate?** and close the settings.

The certificate and key are stored in the app private storage and are used by

* the **WebView** (`onReceivedClientCertRequest`) and
* the **native image mode** (Retrofit/OkHttp via a custom `X509KeyManager`).

Supported PEM blocks: `CERTIFICATE`, `PRIVATE KEY` (PKCS#8), `RSA PRIVATE KEY` (PKCS#1) and
`EC PRIVATE KEY` (SEC1). Encrypted private keys (`ENCRYPTED PRIVATE KEY`) have to be converted
first, e.g.

```bash
openssl pkcs8 -topk8 -nocrypt -in encrypted.key -out key.pem
```

Notes:

* The certificate is only presented to the host of the configured **ImmichFrame Server URL**.
  Requests from other hosts (e.g. an unrelated host embedded in a page) are declined, so the
  certificate cannot leak.
* Certificates made by OpenSSL 3.x are encrypted with AES-256 by default. Android versions before
  Android 10 cannot read those containers — either import the `.pem` files or export the container
  with `openssl pkcs12 -export -legacy ...`.
* **Remove Client Certificate** deletes the private key from the device.

### Creating a client certificate in Cloudflare

1. Cloudflare dashboard → **SSL/TLS → Client Certificates → Create Certificate** (Cloudflare
   generates an ECDSA key pair) or *Upload* your own client certificate.
2. Copy the certificate and the private key.
3. Cloudflare dashboard → **Access → Applications → your application → Policies**: add a policy of
   type *Mutual TLS* (or use a *Service Auth* rule that requires the client certificate).

# Development
- [Android Studio](https://developer.android.com/studio/) 
