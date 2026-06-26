# :package: Ligoj Nexus plugin ![Maven Central](https://img.shields.io/maven-central/v/org.ligoj.plugin/plugin-registry-nexus)

[![License](http://img.shields.io/:license-mit-blue.svg)](http://fabdouglas.mit-license.org/)

[Ligoj](https://github.com/ligoj/ligoj) Sonatype Nexus plugin, extending the
[Registry plugin](https://github.com/ligoj/plugin-registry).

Tool-level plugin living at the node `service:registry:nexus`. It augments the
registry service parent with a subscription-row link to the Nexus repository
browser and a registry chip. Nexus is multi-format, so the artifact `type` is a
real choice.

## Node parameters

| Parameter                         | Type     | Validation scope   | Secured | Purpose                                              |
| --------------------------------- | -------- | ------------------ | ------- | ---------------------------------------------------- |
| `service:registry:nexus:url`      | `TEXT`   | node validation    | no      | Nexus Repository Manager base URL.                   |
| `service:registry:nexus:user`     | `TEXT`   | node validation    | no      | Credentials — login.                                 |
| `service:registry:nexus:password` | `TEXT`   | node validation    | **yes** | Credentials — secret.                                |
| `service:registry:nexus:type`     | `SELECT` | subscription time  | no      | Artifact type — `docker` / `maven` / `nuget` / `npm` / `python`. |
| `service:registry:nexus:registry` | `TEXT`   | subscription time  | no      | Nexus repository hosting the artifacts.              |

`url` + credentials are required to validate the node; `type` + `registry` are
required only when subscribing a project. See
[`src/main/resources/csv/parameter.csv`](src/main/resources/csv/parameter.csv).

## Backend (Java) module

`NexusPluginResource` validates the node (authenticated call to
`/service/rest/v1/repositories`) and the subscription registry
(`/service/rest/v1/repositories/<registry>`). Build & test with Maven:

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
