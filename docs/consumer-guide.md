# Consumer guide: putting your image through the supply chain

This library takes an image you already have, a vendor image from an upstream registry or one your
own pipeline built, and runs it through the same supply chain as the golden base images: digest
pinning, optional hardening, provenance, vulnerability scan with SBOM, policy gate, publication
with immutable and floating tags, signature and attestations. You describe the image in one YAML
file, your Jenkinsfile is two lines, and the platform team owns the rest.

## 1. Before you start

You need four things.

| What | Why | Who provides it |
|---|---|---|
| The library registered in Jenkins as `supply-chain-lib` | your Jenkinsfile loads it with `@Library` | platform team |
| A Jenkins credential (username and password) | the job logs in to Artifactory with it | you, in your folder, or ask the platform team |
| A destination docker repo in Artifactory you can write to | that is where the gated image is published | your team's repo, or `base-images-docker-local` for vendor images |
| A source that Artifactory can reach | upstream registries go through the pull-through remotes | check the `pull` map in the config; `registry.redhat.io` needs its own remote |

The credential's account must read the source and write the destination repo. Secrets never go in
the YAML.

## 2. Setup in five minutes

Add two files to your repository.

`Jenkinsfile`:
```groovy
@Library('supply-chain-lib@v1') _
supplyChainPipeline(spec: 'supplychain/jboss-eap.yaml', credentialsId: 'my-team-artifactory',
                    notifyEmail: 'my-team@acme.example')
```
`credentialsId` is required. `notifyEmail` is optional; without it a failure is only in the job log.

`supplychain/jboss-eap.yaml`:
```yaml
apiVersion: imagefactory.acme.dev/v1
kind: ImageImport
metadata:
  name: jboss-eap
  category: MIDDLEWARE
  auid: AP12345
spec:
  origin: vendor
  source:
    ref: registry.redhat.io/jboss-eap-7/eap74-openjdk17-openshift-rhel8:7.4.15
    digest: ""
  version: "7.4.15"
  destination:
    repo: base-images-docker-local
    path: vendor/jboss-eap
  prodEligible: true
  harden: false
  enabled: true
  platforms: [linux/amd64]
  labels:
    vendor: "Red Hat"
    description: "JBoss EAP 7.4 on OpenJDK 17, imported, scanned and signed, not hardened"
    source: "https://catalog.redhat.com/software/containers/jboss-eap-7/eap74-openjdk17-openshift-rhel8"
```

Create a Pipeline job in Jenkins pointing at your repository (Pipeline script from SCM), run it.
To track several images, put one YAML per image in the directory and pass the directory:
`supplyChainPipeline(spec: 'supplychain', ...)`. They run in parallel.

## 3. The spec, field by field

| Field | Required | Meaning |
|---|---|---|
| `metadata.name` | yes | short id, lowercase (`jboss-eap`); also the workdir name |
| `metadata.category` | yes | `OS`, `MIDDLEWARE`, `DATABASE`, `BUSINESS_CUSTOMIZED`, `APPLICATION` or `OTHER`, per the governance sheet |
| `metadata.auid` | yes | the AUID (AP code) of the application that owns the image |
| `spec.origin` | yes | `vendor` for a third party image, `internal` for one built by our pipelines |
| `spec.source.ref` | yes | the image to import. Upstream hosts are rewritten through the pull-through map, refs on our Artifactory pass through |
| `spec.source.digest` | no | `sha256:...` to pin. Empty means the live digest is resolved on every run (tracking mode) |
| `spec.version` | no | the tag base. Defaults to the source tag; required when the source is a digest ref |
| `spec.destination.repo` | yes | Artifactory docker repo the result is published to |
| `spec.destination.path` | yes | image path inside it (`vendor/jboss-eap`, `payments/api`) |
| `spec.prodEligible` | no | `true`: the image is meant for production. The gate applies the full release rules, the tags get `quality.status=released`, the image is signed and attested. `false`: only the labels are checked, the tags get `quality.status=dev`, and the image is published unsigned, so admission can never deploy it. Default `false` |
| `spec.harden` | no | `true` (internal only): inject the internal CA, run the uniform hardening and flatten. Default `false` |
| `spec.runtime` | no | with `harden: true`, which runtime trust store also gets the internal CA: `auto` (detects a JVM or Python), `java`, `python`, `dotnet`, `none`. Default `auto` |
| `spec.enabled` | no | `false`: the spec is skipped entirely, nothing runs, the tags already published stay as they are. A pause switch that keeps the file in place. Default `true` |
| `spec.platforms` | yes | platforms to publish, e.g. `[linux/amd64]` |
| `spec.labels.vendor` | yes | the distributing entity: the third party for a vendor image, our organisation for an internal one |
| `spec.labels.description` | yes | what the image is |
| `spec.labels.source` | no | the repository the job runs from (`GIT_URL`) unless set here, e.g. a vendor's catalog page |
| `spec.labels.revision` | no | the checkout commit (`GIT_COMMIT`) unless set here |
| `spec.labels.version` | no | defaults to `spec.version` |
| `spec.labels.os`, `osVersion` | yes, unless detected | the distribution and its version, read from the image's `/etc/os-release`; a scratch image has none, so set them here (`os: scratch`) or the gate denies |
| `spec.labels.authors` | no | the owning team; also recorded as the owner in provenance (the AUID otherwise) |
| `spec.labels.documentation`, `licenses` | no | optional governance labels |

Three mandatory labels are filled by the pipeline, never by you: `base.name` is the source ref,
`base.digest` the resolved digest, `created` the import time. The OS and its version are read from
the image's `/etc/os-release` (works for chiselled, UBI micro and distroless images; a scratch image
has none, so set `labels.os` and `labels.osVersion` yourself, the gate requires both). Source and revision come from the
repository the job runs from: the checkout URL and commit Jenkins provides. For an internal image
that is your application repository, which is exactly what provenance should record. For a vendor
image set `labels.source` to the vendor's page if you prefer; whatever you set in the spec wins.

The spec's shape is checked before anything touches a registry, the label set right after. An
unknown `origin`, a vendor image with `harden: true`, a missing vendor or description, missing
platforms, a vendor image whose `vendor` label says Acme, or no source anywhere all stop the job
with the list of problems.

## 4. What happens to your image

1. **Acquire.** The source is resolved through Artifactory and pinned to a digest, its OS is read
   from `/etc/os-release`. The exact manifest is copied into
   `<destination.repo>/<path>:_built-<version>-<digest12>`, all platforms preserved.
2. **Harden** (internal images with `harden: true`). The internal CA is installed in the OS trust
   store and in the runtime's (JVM `cacerts` or Python certifi, detected unless `runtime` says),
   the uniform `harden.sh` runs, the result is flattened to one layer, and your entrypoint,
   command, environment, user, working directory, ports and volumes are re-emitted so the image
   still runs. The labels are baked in. Your image needs a POSIX `sh` for this step. An image
   imported as-is keeps the trust store it came with.
3. **Label.** An image that is not hardened is rebuilt once, config only: `FROM` the pinned
   digest plus the governance labels, no command runs. Its layers stay exactly the source's, only
   the image config changes, so `docker inspect` on the published image shows every label.
4. **Provenance.** A `provenance.json` is written describing the import: source, digest, who
   imported it, and the hardening if any. It never claims to be your build.
5. **Scan.** Vulnerability report and CycloneDX SBOM.
6. **Gate.** The policy decision, evaluated locally: all mandatory labels on every image (the
   governance eight plus `os` and `os.version`); at
   `release` also a completed scan, an SBOM, hardening (vendor images and internal images that
   opted out are accepted as they are), no dev CA. The CVE verdict and the CTI score come from
   the Supply Chain API and are merged into the same decision (interim thresholds apply until it
   is wired: no critical, high under the threshold). A denied image is quarantined in staging and
   the job fails with the reasons; nothing is published.
7. **Publish.** Two tags on the same digest: `<path>:<version>-<digest12>` (immutable, pin this in
   production) and `<path>:<version>` (floating, moves on the next import). The `quality.status`
   property is `released` when `prodEligible` is true, `dev` otherwise.
8. **Sign.** Prod eligible images only: the published tag is signed and the SLSA provenance and
   SBOM are attached as attestations. A `prodEligible: false` image is published unsigned, which is
   exactly what keeps it out of production.
9. **Verify.** The Supply Chain API is asked to confirm the signature and both attestations on the
   published digest. Nothing is signed before this pipeline, so this is the only place a signature
   is checked. Deploy-time admission verifies them again.

## 5. Vendor or internal

| | `origin: vendor` | `origin: internal` |
|---|---|---|
| typical source | Red Hat, NGINX, a public or partner registry | your app pipeline's dev repo on Artifactory |
| hardening | never; `harden: true` is rejected | optional |
| `labels.vendor` | must name the third party | our organisation |
| destination | usually `base-images-docker-local` under `vendor/` | your team's release repo |
| gate | accepted without hardening | hardened by you, or accepted as is because the golden base it was built on is |

## 6. Updating and re-running

- **Tracking mode** (`digest: ""`): every run resolves the current digest. A weekly cron on your job
  keeps vendor images fresh; a new upstream digest produces a new immutable tag and moves the
  floating one.
- **Pinned** (`digest: sha256:...`): the same image every run. Bump the digest and the version in
  the YAML to move.
- Changing `version` changes the tags. Changing labels changes the record and the provenance, not
  the image bytes (unless hardening rebuilds it).
- A failed run publishes nothing. The previous tags keep serving.

## 7. When it fails

| Message | Meaning | What to do |
|---|---|---|
| `ImageImport '<name>' rejected:` followed by a list | spec validation | fix the listed fields |
| `could not resolve a digest for <ref>` | the source is not reachable through Artifactory | check the ref and the pull-through map; ask for a remote if the registry has none |
| `signature verification failed` or `is missing attestations` | the published digest is not signed or attested as expected | the platform team checks the signing service; nothing to change in your spec |
| `Policy gate DENY for <name> [release]: [...]` | the gate refused release | read the reasons: CVEs to fix upstream, missing SBOM, missing labels. Set `prodEligible: false` to publish a non-release image meanwhile |
| `harden.sh: not found` or the wrap build fails at `RUN sh` | the source has no shell | hardening needs a POSIX `sh`; set `harden: false` |
| `no enabled ImageImport spec found at <path>` | wrong `spec:` path or every spec is `enabled: false` | check the path in the Jenkinsfile |

## 8. Questions people ask

**Can I put my Artifactory password in the YAML?** No. The Jenkinsfile names a Jenkins credential
(`credentialsId`), the library binds it and logs in. Nothing secret is ever in the spec.

**Do I have to use `supplyChainPipeline`?** No. Write your own declarative pipeline, bind the
credential as `AF_USER` and `AF_PASS`, call `scLogin()` and then `supplyChain('supplychain/x.yaml')`
inside a `script` block. You get the same stages.

**Where do I see the results?** The job log lists the published tags and the signed ref.
`docker inspect --format '{{json .Config.Labels}}' <published tag>` shows the governance labels. In
Artifactory, the immutable tag carries `quality.status` and the catalog properties. The
`provenance.json`, `sbom.json`, `scan.json` and `gate-input.json` are in the job workspace under
`.supplychain/<name>/`.

**Can one job handle images in several destination repos?** Yes. The library logs in to every
destination repo the specs name, with the one credential you gave it. The account must have write
access to all of them.

**How do I pin a version to a library release?** `@Library('supply-chain-lib@v1')` follows the v1
line. Pin a tag (`@v1.2.0`) if you need to freeze behaviour.

**What does the library decide for me?** Only platform facts: the registry host, the pull-through
remotes, the Supply Chain API. Your credential, labels, platforms and notify address are yours to
write; the library never fills them in.

**Who do I contact?** The platform team, for questions and for new pull-through remotes. Failure
mails go to the `notifyEmail` you set on the pipeline.
