# NordStatus

Sends HTTPS heartbeats to an Instatus Cron monitor from Paper 26.2 or Folia 26.2. Both platforms use one JAR on Java 25.

## Configuration

After installation, set your private `heartbeat-url` in `plugins/NordStatus/config.yml`:

```yaml
heartbeat-url: ""
interval-seconds: 60
initial-delay-seconds: 10
request-timeout-seconds: 10
```

The bundled URL is empty. With no valid HTTPS URL, the plugin disables itself; configure the file and restart. Existing installed configuration is not overwritten.

Keep the URL private. Anyone who has it can send monitor pings. Do not commit it or distribute a JAR containing it.

The minimum interval is 30 seconds, the minimum initial delay is 1 second and the minimum socket timeout is 3 seconds. These values do not define an end-to-end delivery deadline: each IPv4 address can incur connection, TLS and read delays, and DNS resolution has its own timing.

## Heartbeat delivery

The global region scheduler triggers heartbeats. HTTP/1.1 requests run on one private daemon thread, not a game tick thread. Only one heartbeat cycle can be in flight; scheduled triggers skip a busy cycle rather than queue more work.

Each attempt resolves the hostname and tries its usable IPv4 addresses until one returns an HTTP status. Later attempts try the last responding address first. TLS certificate verification and SNI use the configured hostname.

Transport failures, HTTP 429 and HTTP 5xx receive one retry after 5 seconds. Other non-2xx responses are logged without that retry. This is not a continuous retry loop.

If the server stops or the scheduling loop stops advancing, new scheduled heartbeats stop. A cycle already running on the HTTP worker can still finish or retry. On Folia, this is a global-scheduler heartbeat, not a probe of every region: one frozen region does not necessarily stop it.

## Commands and permissions

| Command | Permission | Default |
| --- | --- | --- |
| `/nordstatus test`, request an immediate heartbeat unless one is in progress | `nordstatus.admin` | Operators |
| `/nordstatus reload`, reload `config.yml` | `nordstatus.admin` | Operators |

## Permissions

`nordstatus.admin` controls both commands. It does not affect automatic heartbeat delivery.

## Monitor timing

For the default 60-second interval, the original monitor setup used a 60-second period and at least 120 seconds of grace. Adjust the monitor window to your installed interval and observed network delays; a socket timeout alone does not bound a complete cycle.

## Build and installation

See [BUILDING.md](BUILDING.md) for release requirements and [FOLIA.md](FOLIA.md) for scheduling notes. Install the release JAR on a stopped server.
