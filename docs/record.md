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
| `category` | producer | scan, sign (CLI `--category`) | the governance category: `OS`, `MIDDLEWARE`, `DATABASE`, `BUSINESS_CUSTOMIZED`, `APPLICATION`, `OTHER` |
| `importedAsIs` | producer, `hardenImage` | gate | `true` = the supply chain did **not** harden this image |
| `hardened` | producer, `hardenImage` | gate | `harden.sh` ran, or distroless by construction |
| `prodEligible` | producer | gate (`target` = release / dev), `qualityStatus` | may reach release |
| `labels` | producer | gate | the complete governance label set (validated before any registry call) |
| `gateSkip` | producer | gate | reason string → skip the policy evaluation (debug toolboxes only) |
| `workdir`, `stagingRef`, `imageDigest`, `serial`, `platforms`, `skipped` | producer | all | staging manifest `<repo>.<registry>/<path>:_built-<serial>` |
| `tagPlan` | producer | publish | `[immutable, floating…]` — factory: `scripts/naming.py`; imports: `<path>:<version>-<digest12>`, `<path>:<version>` |
| `qualityStatus`, `catalogProps` | producer | publish | `released` (prod eligible, gate passed), `dev` (imports not eligible for production), `builder` / `debug` (factory), `quarantine` on deny; plus the catalog properties |
| `buildType`, `workflow`, `baseImage`, `importInfo` | producer | provenance, sign | `bisp-base-image` / `bisp-runtime-image` / `bisp-image-import`; the flavor block; the import block |
| `provenance` | `writeProvenance` | sign | path of `provenance.json` |
| `sbom`, `scanReport`, `scan{available,criticalCount,highCount}` | `scanImage` | gate, sign | scanner outputs |
| `gate{target,deny}` | `gateImage` | publish | publish refuses anything not gated or denied |
| `tags`, `published`, `signRefs` | `publishImage` | sign | applied tags, the immutable ref, the ref(s) to sign (signed only when `prodEligible`) |

Producers: `factoryRecord` (built images, the only place that switches on `cell.kind`) and
`acquireImage` (imports).

## Gate input (`<workdir>/gate-input.json`)

```json
{ "target": "release", "kind": "import", "origin": "vendor", "importedAsIs": true, "hardened": false,
  "labels": { "...": "..." },
  "scan": { "available": true, "criticalCount": 0, "highCount": 0, "ctiScore": 0 },
  "sbomGenerated": true }
```
Rules (`resources/policy/gate.rego`, real ones in the external rules repo): the 8 mandatory labels
plus `os` and `os.version` on every target; at release: hardened **unless `importedAsIs`**, a completed scan, no critical, high ≤
threshold, an SBOM, no dev CA. All of that is evaluated locally by opa and stays local. Only the
CVE verdict and the CTI score come from the Supply Chain API (`scs gate`, placeholder in
`gateImage`) and are merged into the same deny list; the local critical/high thresholds are the
interim floor until that call is wired.

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
  version: "7.4.15"                  # default = the source tag
  destination: { repo: base-images-docker-local, path: vendor/jboss-eap }
  prodEligible: true
  harden: false                      # internal only (vendor + harden is rejected)
  enabled: true
  labels:                            # description always; vendor for vendor images; source and
    vendor: "Red Hat"                #   revision default to the Jenkins checkout unless set here
    description: "JBoss EAP 7.4, imported, scanned, signed, not hardened"
    source: "https://catalog.redhat.com/software/containers/..."
    licenses: "..."                  # optional: authors, documentation, licenses
```

| `origin` | hardening | `labels.vendor` | category |
|---|---|---|---|
| `vendor` | never (`harden: true` rejected by schema and step) | must name the third party | author's choice |
| `internal` | optional (`harden: true` → wrap-build, `hardenImage`) | our organisation, written by the author | author's choice |

Derived by the pipeline, never written by the author: `base.name` (source ref), `base.digest`
(resolved digest), `created` (import time). Read from the image's `/etc/os-release` unless the spec
sets them: `os` and `os.version` (absent for scratch images). Taken from the Jenkins checkout
unless the spec sets them: `source` (the repository URL) and `revision` (the commit). After
`signImage`, `verifyPublished` asks the Supply Chain API to confirm the signature and the SLSA and
SBOM attestations on the published digest (skipped while `api.enabled` is false). The staging tag is `_built-<version>-<digest12>`; the
published tags are `<path>:<version>-<digest12>` (immutable) and `<path>:<version>` (floating).
The source manifest is copied exactly (registry-side, all platforms) into staging, then rebuilt once:
hardened (`hardenImage`) or config-only (`labelImage`: `FROM` the digest plus `LABEL` lines, layers
untouched), so the governance labels are in the published image as well as on the record. Provenance describes the
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
The library's config holds platform values only (registry, organisation name, pull-through map,
Supply Chain API); the consumer supplies `credentialsId`, `notifyEmail`, labels and platforms, and
nothing is defaulted for them.

## `harden: true` (internal images)

`hardenImage` installs the internal CA (`resources/certs/`, OS store plus the detected or declared
`runtime` store), runs the uniform `harden.sh` on the imported image in a wrap-build and flattens
the result to one layer. Because the flatten discards the source config, the generated Dockerfile
re-emits ENV, WORKDIR, EXPOSE, VOLUME, STOPSIGNAL, ENTRYPOINT, CMD and USER from the image config
read out of the registry, and bakes the validated governance labels in. The source must contain a
POSIX `sh`. The record then carries `hardened: true`, `importedAsIs: false`, buildType
`bisp-hardened-import`, and `importInfo.preHardenDigest` (= label `base.digest`).
