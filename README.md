# supply-chain-lib — scan · gate · publish · sign · provenance (· harden) for any image

A Jenkins shared library that applies the same supply chain to **every** container image, whether
the base-image factory built it or not: a vendor image tracked from an upstream registry (JBoss,
NGINX…), or an application image another team's pipeline built.

```
acquireImage(spec) ─▶ [hardenImage] ─▶ writeProvenance ─▶ scanImage ─▶ gateImage ─▶ publishImage ─▶ signImage ─▶ verifyPublished
```

The factory (`base-images-pipeline`) is this library's first consumer: its `buildImage` produces the
same **record** and calls the same steps. One code path, one policy, one provenance format.

## Use it from your repo

`Jenkinsfile` (the whole file):
```groovy
@Library('supply-chain-lib@v1') _
supplyChainPipeline(spec: 'supplychain/jboss-eap.yaml',     // or a directory: spec: 'supplychain'
                    credentialsId: 'payments-artifactory', notifyEmail: 'payments-team@acme.example')
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
  platforms: [linux/amd64]
  labels: { vendor: "Red Hat", description: "...", source: "https://catalog.redhat.com/..." }
```

What happens: the spec is validated (`auid`, `category`, `platforms`, `description` and `vendor`
come from the spec; `source` and `revision` are the Jenkins checkout's URL and commit
unless the spec overrides them; `base.name`, `base.digest`, `created` are derived) → the source is
digest-pinned through the Artifactory pull-through map → copied exactly into
`<destination.repo>/<path>:_built-<version>-<digest12>` → optionally hardened → provenance written
→ scanned (SBOM + vuln report) → policy gate → published as `<path>:<version>-<digest12>`
(immutable) + `<path>:<version>` (floating) with `quality.status` → if prod eligible, signed +
attested (SLSA provenance of the **import**, CycloneDX SBOM) and verified on the published digest
through the Supply Chain API. A `prodEligible: false` image is published unsigned and can never
pass admission.

| `origin` | hardening | `labels.vendor` |
|---|---|---|
| `vendor` | never (`harden: true` is rejected) | must name the third party |
| `internal` | optional | our organisation, written by the author |

New here? Start with the [consumer guide](docs/consumer-guide.md). Full field reference and the
record contract: [docs/record.md](docs/record.md).

## Verifying what was published

Nothing is signed before it goes through this pipeline, so verification happens at the end:
`verifyPublished` calls `verifySignature` and `getAttestations` on the published digest and fails
the job if the signature or the SLSA and SBOM attestations are missing. Both call the Supply Chain
API (`api.url`, token in `credentials.api`) and are skipped while `api.enabled` is false.

## Credentials

Secrets never go in the spec. The job binds the Jenkins username/password credential named by
`credentialsId` (required) and logs in to the pull-through remotes and to every destination repo
the specs name; the account must read the source and write the destination. Teams that write
their own Jenkinsfile instead of `supplyChainPipeline` bind it as `AF_USER` / `AF_PASS` and call
`scLogin([repos])` before `supplyChain(...)`. The Supply Chain API token (`credentials.api`) is the
platform's, bound by the library itself.

## Configuration

`resources/config/supply-chain.yaml` holds platform values only: registry host, organisation
name, pull-through map, Supply Chain API url and token credential id, work root. Nothing a consumer
should decide is defaulted there: credential id, labels, platforms and notify address come from the
consumer's Jenkinsfile and spec, and are simply absent when not given. Overrides, later wins:
`config/registry.yaml` + `config/defaults.yaml` in the job's workspace → the file named by env
`SC_CONFIG` → env `REGISTRY`. Rules: the external rules repo via `RULES_REPO_DIR`, else the bundled example policy.

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

`scanImage` (Trivy or `scs scan`), `gateImage` (`scs gate`, policy and CTI on the Supply Chain API;
local opa is the interim path), `signImage` (`scs sign` / `scs attest`), and the API calls in
`verifySignature` / `getAttestations` (guarded by `api.enabled`, endpoints to confirm).
