# supply-chain-lib — scan · gate · publish · sign · provenance (· harden) for any image

A Jenkins shared library that applies the same supply chain to **every** container image, whether
the base-image factory built it or not: a vendor image tracked from an upstream registry (JBoss,
NGINX…), or an application image another team's pipeline built.

```
acquireImage(spec) ─▶ [hardenImage] ─▶ writeProvenance ─▶ scanImage ─▶ gateImage ─▶ publishImage ─▶ signImage
```

The factory (`base-images-pipeline`) is this library's first consumer: its `buildImage` produces the
same **record** and calls the same steps. One code path, one policy, one provenance format.

## Use it from your repo

`Jenkinsfile` (the whole file):
```groovy
@Library('supply-chain-lib@v1') _
supplyChainPipeline(spec: 'supplychain/jboss-eap.yaml')     // or a directory: spec: 'supplychain'
```

`supplychain/jboss-eap.yaml` — the spec is complete on its own (see [examples/](examples/)):
```yaml
apiVersion: imagefactory.acme.dev/v1
kind: ImageImport
metadata: { name: jboss-eap, category: MIDDLEWARE, auid: AP12345 }
spec:
  origin: vendor                     # vendor | internal
  source:  { ref: registry.redhat.io/jboss-eap-7/eap74-openjdk17-openshift-rhel8:7.4.15, digest: "" }
  version: "7.4.15"
  destination: { repo: base-images-docker-local, path: vendor/jboss-eap }
  prodEligible: true
  harden: false                      # internal images may set true (wrap-build harden.sh + flatten)
  labels: { vendor: "Red Hat", description: "...", source: "https://catalog.redhat.com/..." }
```

What happens: the spec is validated (schema shape + the mandatory governance labels — `auid`,
`category`, `vendor`, `description`, `source`; `base.name`, `base.digest`, `created` are derived) →
the source is digest-pinned through the Artifactory pull-through map (optionally signature-verified)
→ copied exactly into `<destination.repo>/<path>:_built-<version>-<digest12>` → optionally hardened
→ provenance written → scanned (SBOM + vuln report) → policy gate → published as
`<path>:<version>-<digest12>` (immutable) + `<path>:<version>` (floating) with `quality.status` →
signed + attested (SLSA provenance of the **import**, CycloneDX SBOM).

| `origin` | hardening | `labels.vendor` |
|---|---|---|
| `vendor` | never (`harden: true` is rejected) | must name the third party |
| `internal` | optional | defaults to ours |

New here? Start with the [consumer guide](docs/consumer-guide.md). Full field reference and the
record contract: [docs/record.md](docs/record.md).

## Verifying the source

`source.verify.signature: true` calls `verifySignature` on the pinned source digest before anything
is copied; `source.verify.attestations: [<predicate types>]` calls `getAttestations` and refuses the
import if one is missing. Both go through the Supply Chain API (curl placeholders in the steps,
`api.url` and `credentials.api` in the config). Until the API is wired a requested verification
fails, so nothing is ever imported as verified by accident.

## Credentials

Secrets never go in the spec. The job binds one Jenkins username/password credential and logs in
to every repo it touches (the pull-through remotes, the default repo, the spec's destination repo):

```groovy
supplyChainPipeline(spec: 'supplychain/jboss-eap.yaml', credentialsId: 'payments-artifactory')
```

`credentialsId` names a credential visible to the job (folder or global scope) whose account can
read the source and write the destination repo. Without it the library uses `credentials.docker`
from the config (`artifactory-docker` by default). Teams that write their own Jenkinsfile instead
of `supplyChainPipeline` bind the credential themselves as `AF_USER` / `AF_PASS` and call
`scLogin()` before `supplyChain(...)`.

## Configuration

`resources/config/supply-chain.yaml` holds the org defaults (registry host, pull-through map,
credential ids, default labels, notify address). Overrides, later wins: `config/registry.yaml` +
`config/defaults.yaml` in the job's workspace → the file named by env `SC_CONFIG` → env `REGISTRY`
/ `REPO`. Rules: the external rules repo via `RULES_REPO_DIR`, else the bundled example policy.

Agent requirements: `docker` (buildx), `python3` (only for `harden: true`), `opa` (interim local
gate), the Supply Chain CLI `scs` (scan/verify/sign/attest — placeholders today, marked in the steps).

## Jenkins setup

Manage Jenkins → System → Global Trusted Pipeline Libraries → add `supply-chain-lib`, default
version `v1`, Modern SCM pointing at this repo, **Load implicitly: off**. It must be a *global*
(trusted) library: `scConfig` caches in the pipeline binding, which folder libraries sandbox.
`vars/` is at the repo root, so no Library Path is needed. Consumers pin a version
(`@Library('supply-chain-lib@v1')`); `v1` is a moving branch, releases are semver tags.

Compatibility surface (semver): the record fields, the step signatures, the gate-input shape, the
`ImageImport` schema. Steps in this library never read the factory's `cell`.

## Layout

```
vars/          the steps (acquireImage, hardenImage, scanImage, gateImage, publishImage, signImage,
               writeProvenance, supplyChain, supplyChainPipeline, scConfig, scLogin, scProps, scSpecs, scNotify)
resources/     config/supply-chain.yaml · policy/gate.rego (+ tests) · hardening/{harden.sh,install-certs.sh,wrap.py}
               · slsa/provenance.input*.json (the provenance.json contract)
schema/        imageimport.schema.json     examples/  jboss-eap.yaml · internal-app.yaml · Jenkinsfile.consumer
tests/         run.sh (pytest + opa + shellcheck)      docs/consumer-guide.md · docs/record.md
```

## Placeholders to wire

`scanImage` (Trivy or `scs scan`), `gateImage` (`scs gate` — policy + CTI on the Supply Chain API;
local opa is the interim path), `acquireImage` (`scs verify`), `signImage` (`scs sign` / `scs attest`).
Each is a marked block; the inputs/outputs around them are fixed.
