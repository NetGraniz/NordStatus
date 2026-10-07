# Paper / Folia compatibility — 1.3.0

Use `NordStatus-1.3.0.jar` with JDK 25 on Paper 26.2 or Folia 26.2.

Heartbeat timing uses the global scheduler; HTTP and bounded retries remain asynchronous. This is NOT a health check of every region. Keep the private heartbeat URL in installed config, never GitHub. Configuration values and HTTP behavior are unchanged.

Run `mvn clean verify` first. The [isolated runtime harness](https://github.com/NetGraniz/NordChat/tree/main/test-support) exercises all eight plugins together with synthetic loopback clients and local HTTPS. Never deploy its helper JAR on a real server. Results are saved outside the repository. This is not a 1000-player load test or a future-version guarantee.

Back up configuration/player data, stop the server and replace only the JAR without duplicates. Preserve installed data and working config; repository templates are not migration scripts. No production worlds or data are migrated by this release.
