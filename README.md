# :package: Ligoj Nexus plugin ![Maven Central](https://img.shields.io/maven-central/v/org.ligoj.plugin/plugin-registry-nexus)

[![License](http://img.shields.io/:license-mit-blue.svg)](http://fabdouglas.mit-license.org/)

[Ligoj](https://github.com/ligoj/ligoj) Sonatype Nexus plugin, extending the
[Registry plugin](https://github.com/ligoj/plugin-registry).

Tool-level plugin living at the node `service:registry:nexus`. It augments the
registry service parent with a subscription-row link to the Nexus repository
browser and a registry chip. Nexus is multi-format, so the artifact `type` is a
real choice.

## Parameters

| Parameter                              | Type     | Validation scope   | Secured | Purpose                                              |
| -------------------------------------- | -------- | ------------------ | ------- | ---------------------------------------------------- |
| `service:registry:nexus:url`           | `TEXT`   | node validation    | no      | Nexus Repository Manager base URL.                   |
| `service:registry:nexus:user`          | `TEXT`   | node validation    | no      | Credentials — login.                                 |
| `service:registry:nexus:password`      | `TEXT`   | node validation    | **yes** | Credentials — secret.                                |
| `service:registry:nexus:type`          | `SELECT` | subscription time  | no      | Artifact type — `docker`, `maven`, `nuget`, `npm`, `python`, `yum`, `apt`, `raw`, `helm`, `rubygems`, `r`, `gitlfs`. |
| `service:registry:nexus:registry`      | `TEXT`   | subscription time  | no      | LINK: existing Nexus repository. CREATE: name of the hosted repository to create. |
| `service:registry:nexus:configuration` | `TEXT`   | CREATE only        | **yes** | Optional JSON settings of the created repository, merged over the format defaults. |
| `service:registry:nexus:roles`         | `TEXT`   | CREATE only        | no      | Optional JSON role mapping, keyed by group.          |

`url` + credentials are required to validate the node; `type` + `registry` are
required only when subscribing a project. See
[`src/main/resources/csv/parameter.csv`](src/main/resources/csv/parameter.csv).
The `type` options are stored by index: new types are only appended.

## Create mode

The node supports both modes (`ALL`). A CREATE subscription creates a hosted repository named by `registry`, of the
`type` format, through `POST /service/rest/v1/repositories/<format>/hosted`. When a hosted repository of the same format
already exists with this name, it is reused with a warning; another format or type is rejected. Everything is
validated before the first call to Nexus.

### Repository settings

The payload sent to Nexus is built from the common defaults, then the format defaults, then the `configuration` JSON,
merged recursively: objects are merged, other values replaced. The name always comes from `registry`. The settings
are the ones of the [Nexus repositories API](https://help.sonatype.com/en/repositories-api.html).

| Type       | Nexus format | Write policy | Format defaults                                                        |
| ---------- | ------------ | ------------ | ---------------------------------------------------------------------- |
| `docker`   | `docker`     | `ALLOW`      | `"docker": {"v1Enabled": false, "forceBasicAuth": true}`               |
| `maven`    | `maven2`     | `ALLOW_ONCE` | `"maven": {"versionPolicy": "RELEASE", "layoutPolicy": "STRICT", "contentDisposition": "INLINE"}` |
| `nuget`    | `nuget`      | `ALLOW_ONCE` |                                                                        |
| `npm`      | `npm`        | `ALLOW_ONCE` |                                                                        |
| `python`   | `pypi`       | `ALLOW_ONCE` |                                                                        |
| `yum`      | `yum`        | `ALLOW_ONCE` | `"yum": {"repodataDepth": 0, "deployPolicy": "STRICT"}`                |
| `apt`      | `apt`        | `ALLOW_ONCE` | none: `apt.distribution` and `aptSigning.keypair` are **required**     |
| `raw`      | `raw`        | `ALLOW`      | `"raw": {"contentDisposition": "ATTACHMENT"}`                          |
| `helm`     | `helm`       | `ALLOW_ONCE` |                                                                        |
| `rubygems` | `rubygems`   | `ALLOW_ONCE` |                                                                        |
| `r`        | `r`          | `ALLOW_ONCE` |                                                                        |
| `gitlfs`   | `gitlfs`     | `ALLOW`      |                                                                        |

Common defaults:

```json
{
  "online": true,
  "storage": { "blobStoreName": "default", "strictContentTypeValidation": true, "writePolicy": "<write policy>" },
  "cleanup": { "policyNames": [] },
  "component": { "proprietaryComponents": false }
}
```

Samples of `configuration`:

```json
{ "maven": { "versionPolicy": "SNAPSHOT" }, "storage": { "writePolicy": "ALLOW" }, "cleanup": { "policyNames": ["weekly"] } }
```

```json
{ "yum": { "repodataDepth": 2, "deployPolicy": "PERMISSIVE" } }
```

```json
{
  "apt": { "distribution": "bookworm" },
  "aptSigning": { "keypair": "-----BEGIN PGP PRIVATE KEY BLOCK-----\n...", "passphrase": "..." }
}
```

```json
{ "docker": { "forceBasicAuth": true, "httpPort": 8083 } }
```

The configuration is a secured parameter (encrypted in database): it may hold the APT signing key pair.

### Role mapping

`roles` grants the repository to groups. Keys are group names, each one granting repository actions among `browse`,
`read`, `edit`, `add`, `delete` and `*`:

- `view-permissions`: content actions, privileges `nx-repository-view-<format>-<repository>-<action>`;
- `admin-permissions`: repository administration actions, privileges `nx-repository-admin-<format>-<repository>-<action>`.

These privileges exist for every repository format, so the same mapping applies to Maven, Python, YUM, APT, Docker...
The privileges are granted through the Nexus role having the group name as identifier, which Nexus maps to the
directory group of the same name (LDAP realm). A missing role is created; an existing role keeps its other privileges
and roles.

```json
{
  "admin": { "view-permissions": ["*"], "admin-permissions": ["*"] },
  "dev": { "view-permissions": ["browse", "read", "delete", "add"], "admin-permissions": ["browse", "read", "delete"] },
  "test": { "view-permissions": ["browse"] }
}
```

For a Maven repository `mvn-demo`, the `test` group gets the privilege `nx-repository-view-maven2-mvn-demo-browse`.

### Subscription sample

A node accepting the creation, then a CREATE subscription of the project `1` with a Maven repository, its settings and
its role mapping, sent with `POST rest/node` and `POST rest/subscription`:

```json
{
  "id": "service:registry:nexus:main",
  "name": "Nexus",
  "node": "service:registry:nexus",
  "mode": "ALL",
  "parameters": [
    { "parameter": "service:registry:nexus:url", "text": "https://nexus.sample.com" },
    { "parameter": "service:registry:nexus:user", "text": "admin" },
    { "parameter": "service:registry:nexus:password", "text": "secret" }
  ]
}
```

```json
{
  "node": "service:registry:nexus:main",
  "project": 1,
  "mode": "CREATE",
  "parameters": [
    { "parameter": "service:registry:nexus:type", "index": 1 },
    { "parameter": "service:registry:nexus:registry", "text": "demo-maven" },
    {
      "parameter": "service:registry:nexus:configuration",
      "text": "{\"maven\": {\"versionPolicy\": \"MIXED\"}, \"storage\": {\"writePolicy\": \"ALLOW\"}, \"cleanup\": {\"policyNames\": [\"weekly\"]}}"
    },
    {
      "parameter": "service:registry:nexus:roles",
      "text": "{\"admin\": {\"view-permissions\": [\"*\"], \"admin-permissions\": [\"*\"]}, \"dev\": {\"view-permissions\": [\"browse\", \"read\", \"delete\", \"add\"], \"admin-permissions\": [\"browse\", \"read\", \"delete\"]}, \"test\": {\"view-permissions\": [\"browse\"]}}"
    }
  ]
}
```

The JSON parameters are sent as text, so their JSON is escaped. The `type` is a `SELECT`, sent as the `index` of its
option:

| Index | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Type | `docker` | `maven` | `nuget` | `npm` | `python` | `yum` | `apt` | `raw` | `helm` | `rubygems` | `r` | `gitlfs` |

This subscription creates the hosted repository `demo-maven` (format `maven2`, mixed release and snapshot versions,
redeploy allowed, `weekly` cleanup), then grants it to the roles `admin`, `dev` and `test`. For an APT repository, the
same payload takes `"index": 6` and a configuration such as
`{"apt": {"distribution": "bookworm"}, "aptSigning": {"keypair": "...", "passphrase": "..."}}`.

### Deletion

Deleting the subscription with the "remote data" option removes the privileges of the repository from the mapped
roles (a role left without privilege nor role is deleted), then deletes the repository. Remote data already partially
deleted is tolerated: each issue (repository missing, deletion or role update failing) is reported as a warning
(`X-Ligoj-Warning` header, shown as a toast in the UI) without blocking the unsubscription.

## Backend (Java) module

`NexusPluginResource` validates the node (authenticated call to
`/service/rest/v1/repositories`) and the subscription registry
(`/service/rest/v1/repositories/<registry>`). `NexusFormat` holds the formats and their defaults,
`NexusRepositoryManager` the repository and role calls of the CREATE mode. Build & test with Maven:

```bash
mvn -Pjacoco verify     # JUnit (WireMock-backed) + JaCoCo (100% coverage)
```

## UI (Vue) module

```bash
cd ui
npm install
npm run build          # emits to ../src/main/resources/.../webjars/registry-nexus/vue/
npm run lint
npm test
npm run test:coverage  # enforces 100% coverage
```
