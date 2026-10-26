You can put JAR files here for dependencies which aren't published to CurseForge/Modrinth or available on any Maven.

`hexcasting-fabric-1.21.1-0.12.0-devel-pre-64.jar` is intermediary (Loom remaps it for `common` and `fabric`).
`hexcasting-neoforge-1.21.1-0.12.0-devel-pre-64.jar` is Mojmap and is a plain `implementation` on NeoForge.
Both are the same bytes as `xm1221/MieHexRevolution1.20.1` branch `1.21` `common/libs/`. BlameJared has no `hexcasting-*-1.21.1` artifacts.

HexCLI soft-deps (dev runtime / compileOnly; optional at publish time):
- `hexParse-fabric-1.21.1-1.12.0.pre64.jar` / `hexParse-neoforge-1.21.1-1.12.0.pre64.jar`
- `yclib-1.21.1-0.2.jar` / `yclib-neoforge-1.21.1-0.2.jar` — extracted from hexParse's jar-in-jar.
  Loom drops nested `META-INF/jars/` in the remapped dev runtime, so they must be listed separately.
