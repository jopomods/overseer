# Reverse proxy and HTTPS

Overseer listens on `127.0.0.1:8765` without TLS. To reach it from the internet (e.g. from
your phone), put a reverse proxy with HTTPS in front of it on the same machine. Login
codes and session cookies must never travel unencrypted.

Requirements for any proxy:

- Forward to `http://127.0.0.1:8765`.
- Preserve the `Host` header, or send `X-Forwarded-Host`. Overseer compares it with the
  browser's `Origin` on every write request.
- Send `X-Forwarded-Proto: https` so cookies are marked `Secure`, and `X-Forwarded-For`
  for correct rate limiting.
- **Do not buffer** responses on `/api/v1/stream` (Server-Sent Events). Overseer sends
  `X-Accel-Buffering: no`, which nginx honors.
- The proxy's address must be in `web.trustedProxies` (default: `127.0.0.1`, `::1`).

Then set `web.publicUrl` in `overseer-server.toml` to the public address so links printed
by `/ov login` and `/ov status` are correct.

## Caddy (recommended)

Caddy obtains and renews Let's Encrypt certificates automatically and passes `Host`,
`X-Forwarded-For` and `X-Forwarded-Proto` by default.

```caddyfile
dash.example.org {
    reverse_proxy 127.0.0.1:8765 {
        flush_interval -1
    }
}
```

## nginx

```nginx
server {
    listen 443 ssl http2;
    server_name dash.example.org;

    ssl_certificate     /etc/letsencrypt/live/dash.example.org/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/dash.example.org/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8765;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Connection "";

        # Server-Sent Events
        proxy_buffering off;
        proxy_read_timeout 1h;
    }
}
```

## Check

```sh
curl -s https://dash.example.org/api/v1/health
```

should return `{"status":"ok",...}`. In the browser, the login cookie should show
`Secure`, `HttpOnly` and `SameSite=Strict`.
