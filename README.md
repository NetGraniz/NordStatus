# NordStatus

Lightweight Paper heartbeat sender for the Nord Fjell Instatus page.

The bundled `heartbeat-url` is empty. After installation, enter your private HTTPS
heartbeat URL in the server's `plugins/NordStatus/config.yml`, then restart or enable
the plugin. With no URL configured, the plugin disables itself. Do not commit the URL
or distribute JARs containing it: anyone possessing the URL can send monitor pings.
Existing installed configuration is not overwritten by this template.

- The trigger runs on Paper's main scheduler every 60 seconds.
- HTTP/1.1 requests run on one private daemon thread and never block the server tick.
- Only one heartbeat may be in flight at a time.
- Every usable IPv4 address returned by DNS is tried until one connects.
- The last working address is tried first on later heartbeats, avoiding repeated delays.
- TLS certificate verification and SNI always use the configured hostname.
- A transport failure gets one delayed retry.
- If Paper stops or the main tick loop freezes, the heartbeat stops.
- `/nordstatus test` sends an immediate test heartbeat.
- `/nordstatus reload` reloads `config.yml`.

The Instatus Cron monitor should use a 60-second period and a grace period of at
least 120 seconds.
