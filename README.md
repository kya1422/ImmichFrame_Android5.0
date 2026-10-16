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
