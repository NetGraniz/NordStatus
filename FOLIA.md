# Paper / Folia compatibility — 1.3.0

Use `NordStatus-1.3.0.jar` with JDK 25 on Paper 26.2 or Folia 26.2.

Heartbeat timing uses the global scheduler; HTTP and bounded retries remain asynchronous. This is NOT a health check of every region. Keep the private heartbeat URL in installed config, never GitHub. Configuration values and HTTP behavior are unchanged.

Run `mvn clean verify` before packaging the release JAR.

Back up configuration/player data, stop the server and replace only the JAR without duplicates. Preserve installed data and working config; repository templates are not migration scripts. No production worlds or data are migrated by this release.
