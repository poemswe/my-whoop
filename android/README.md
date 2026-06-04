# my-whoop Android

Kotlin/JVM port of the WHOOP 4.0 protocol layer, mirroring `Packages/WhoopProtocol/`.

## Modules

- `:protocol` — pure Kotlin/JVM library. Frame envelope, CRC-8/CRC-32, reassembly, schema-driven packet interpretation. No Android SDK deps.

The canonical protocol schema lives at `../protocol/whoop_protocol.json` and is wired in as a resource by Gradle so iOS and Android stay in sync.

## Build

```
cd android
gradle :protocol:test
```

(Run `gradle wrapper` once to generate `./gradlew`.)
