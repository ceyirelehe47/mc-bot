# MC-2A UI Fixture

Client-only validation mod for the MC-2A0.6 exact mod-Screen adapter slice.

It replaces only the exact vanilla `GenericContainerScreen` visual class with
`FixtureGenericContainerScreen`. The underlying vanilla ScreenHandler and server barrel remain
unchanged. It adds one inert `Fixture Inspect` button so the production read model can prove bounded
widget introspection.

Build from a clean pinned upstream AIBot checkout:

```bash
./gradlew -p /absolute/path/to/aibot-dsh-m0/fixtures/mc2a-ui-fixture build
```

On Windows:

```powershell
.\gradlew.bat -p D:\path\to\aibot-dsh-m0\fixtures\mc2a-ui-fixture build
```

Load the resulting JAR only in Bob's client for the fixture LIVE. Do not install it on the server or
the owner's PCL2 client.
