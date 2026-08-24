# libs-repo/

A tiny local, offline Maven repository (just one artifact) vendored into this
project so it doesn't depend on Maven Central being reachable at build time.

Contains: `com.formdev:flatlaf:3.7.2` — compiled directly from the official
`JFormDesigner/FlatLaf` GitHub source tag `3.7.2` (Apache 2.0 licensed), with
no modifications, no native-library modules, and no third-party themes —
just the pure-Java `flatlaf-core` module (light/dark Look and Feel).

`pom.xml` picks this up automatically via:

```xml
<repositories>
    <repository>
        <id>project-local-libs</id>
        <url>file://${project.basedir}/libs-repo</url>
    </repository>
</repositories>
```

If your environment *does* have normal internet access, you can delete this
directory and the `<repositories>` block in `pom.xml` — Maven will fetch the
real `com.formdev:flatlaf:3.7.2` from Maven Central instead, with no other
changes needed.
