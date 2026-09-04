# The supply-chain record — one contract for images we build and images we import

Every image, whether the factory built it or another pipeline did, goes through the same steps:

```
acquireImage(spec) ─┐
                    ├─▶ [hardenImage] ─▶ scanImage ─▶ gateImage ─▶ publishImage ─▶ signImage
buildImage(cell) ───┘        (opt-in)
   └─ factoryRecord
```

The steps communicate through ONE map, the **record**. Downstream steps read only the fields
below; the factory's `cell` stays opaque and is never read after `factoryRecord`. The record is
what makes the supply-chain steps reusable outside this repo (see *Consumers* at the end).

## Fields

| Field | Set by | Read by | Meaning |
|---|---|---|---|
| `kind` | producer | gate input | `os` / `runtime` / `debug` (built) or `import` |
| `name` | producer | logs, errors | human-readable id (`python/runtime`, `vendor/jboss-eap:7.4.15`) |
| `origin` | producer | gate input | `built` / `vendor` / `internal` |
| `importedAsIs` | producer, `hardenImage` | gate | `true` = the supply chain did **not** harden this image |
| `hardened` | producer, `hardenImage` | gate | `harden.sh` ran, or distroless by construction |
| `prodEligible` | producer | gate (`target` = release / dev), `qualityStatus` | may reach release |
| `labels` | producer | gate | the complete governance label set (validated before any registry call) |
| `gateSkip` | producer | gate | reason string → skip the policy evaluation (debug toolboxes only) |
| `workdir`, `stagingRef`, `imageDigest`, `serial`, `platforms`, `skipped` | producer | all | staging manifest `<repo>.<registry>/<path>:_built-<serial>` |
| `tagPlan` | producer | publish | `[immutable, floating…]` — factory: `scripts/naming.py`; imports: `<path>:<version>-<digest12>`, `<path>:<version>` |
| `qualityStatus`, `catalogProps` | producer | publish | `released` / `builder` / `debug` (+ `quarantine` on deny) and the catalog properties |
| `buildType`, `workflow`, `baseImage`, `importInfo` | producer | provenance, sign | `bisp-base-image` / `bisp-runtime-image` / `bisp-image-import`; the flavor block; the import block |
| `provenance` | `writeProvenance` | sign | path of `provenance.json` |
| `sbom`, `scanReport`, `scan{available,criticalCount,highCount}` | `scanImage` | gate, sign | scanner outputs |
| `gate{target,deny}` | `gateImage` | publish | publish refuses anything not gated or denied |
| `tags`, `published`, `signRefs` | `publishImage` | sign | applied tags, the immutable ref, the ref(s) to sign |

Producers: `factoryRecord` (built images, the only place that switches on `cell.kind`) and
`acquireImage` (imports).

## Gate input (`<workdir>/gate-input.json`)

```json
{ "target": "release", "kind": "import", "origin": "vendor", "importedAsIs": true, "hardened": false,
  "labels": { "...": "..." },
  "scan": { "available": true, "criticalCount": 0, "highCount": 0, "ctiScore": 0 },
  "sbomGenerated": true }
```
Rules (`resources/policy/gate.rego`, real ones in the external rules repo): the 8 mandatory labels on every
target; at release: hardened **unless `importedAsIs`**, a completed scan, no critical, high ≤
threshold, an SBOM, no dev CA. The policy decision and CTI scoring will move to the Supply Chain
API (`scs gate`, placeholder in `gateImage`); the local opa evaluation is the interim path with the
same input and the same `deny` contract.

## The `ImageImport` spec

```yaml
apiVersion: imagefactory.acme.dev/v1
kind: ImageImport
metadata: { name: jboss-eap, category: MIDDLEWARE, auid: AP12345 }
spec:
  origin: vendor                     # vendor | internal
  source:
    ref: registry.redhat.io/jboss-eap-7/eap74-openjdk17-openshift-rhel8:7.4.15
    digest: ""                       # empty = resolve live on every run (tracking mode)
    verify: { signature: false, attestations: [] }   # verifySignature / getAttestations via the Supply Chain API
  version: "7.4.15"                  # default = the source tag
  destination: { repo: base-images-docker-local, path: vendor/jboss-eap }
  prodEligible: true
  harden: false                      # internal only (vendor + harden is rejected)
  enabled: true
  labels:                            # mandatory: vendor, description, source
    vendor: "Red Hat"
    description: "JBoss EAP 7.4 — imported, scanned, signed; not hardened"
    source: "https://catalog.redhat.com/software/containers/..."
    licenses: "..."                  # optional: authors, documentation, licenses, version, revision
```

| `origin` | hardening | `labels.vendor` | category |
|---|---|---|---|
| `vendor` | never (`harden: true` rejected by schema and step) | must name the third party | author's choice, `OTHER` by default |
| `internal` | optional (`harden: true` → wrap-build, `hardenImage`) | defaults to ours | author's choice |

Derived by the pipeline, never written by the author: `base.name` (source ref), `base.digest`
(resolved digest), `created` (import time). The staging tag is `_built-<version>-<digest12>`; the
published tags are `<path>:<version>-<digest12>` (immutable) and `<path>:<version>` (floating).
What is published is the **exact** source manifest (registry-side copy, all platforms); the labels
travel on the record and in provenance, the image is not relabelled. Provenance describes the
import (`import` block), never a build.

## Where the steps live: two libraries

| Library | Steps | Who loads it |
|---|---|---|
| `base-image-lib` (the factory repo, `jenkins/vars/`) | `buildImage`, `factoryRecord`, `matrixCells`, `loadSpecs`, `triggerDependents`, `resolveUpstream`, `cfg`, `notifyFailure` | the factory's Jenkinsfiles |
| `supply-chain-lib` (own repo, versioned `@v1`) | `acquireImage`, `hardenImage`, `scanImage`, `gateImage`, `publishImage`, `signImage`, `writeProvenance`, `supplyChain`, `supplyChainPipeline`, `scConfig`, `scLogin`, `scProps`, `scSpecs`, `scNotify` + bundled policy, hardening scripts, config defaults | the factory (`@Library(['base-image-lib', 'supply-chain-lib@v1'])`) and every consumer |

The factory is the library's first consumer: its Jenkinsfiles call
`signImage(publishImage(gateImage(scanImage(buildImage(c)))))`, and `buildImage` ends with
`writeProvenance(factoryRecord(...))`. Step names are disjoint between the two libraries on purpose
(two libraries defining the same `vars/` name resolve by undocumented load order).

## Consumers (other teams)

```groovy
@Library('supply-chain-lib@v1') _
supplyChainPipeline(spec: 'supplychain/jboss-eap.yaml')   // or a directory of specs
```
`supplyChainPipeline` brings the agent, credentials, registry logins, per-image lock, the stage
chain (`acquire -> [harden] -> provenance -> scan -> gate -> publish -> sign`) and failure mail.
Configuration comes from the library's bundled defaults, overridable by `config/registry.yaml` +
`config/defaults.yaml` in the workspace, env `SC_CONFIG`, env `REGISTRY`/`REPO`.

## `harden: true` (internal images)

`hardenImage` runs the uniform `harden.sh` on the imported image in a wrap-build and flattens the
result to one layer. Because the flatten discards the source config, the generated Dockerfile
re-emits ENV, WORKDIR, EXPOSE, VOLUME, STOPSIGNAL, ENTRYPOINT, CMD and USER from the image config
read out of the registry, and bakes the validated governance labels in. The source must contain a
POSIX `sh`. The record then carries `hardened: true`, `importedAsIs: false`, buildType
`bisp-hardened-import`, and `importInfo.preHardenDigest` (= label `base.digest`).
