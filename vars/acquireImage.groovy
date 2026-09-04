// Bring an image we did not build into the supply chain: check the spec, pin the source digest,
// take source and revision from the Jenkins checkout (the spec overrides them), copy the exact
// manifest into the destination repo's staging tag, build the record.
def call(Object spec) {
    def file = (spec instanceof Map && spec.doc) ? spec.file : null
    def doc = (spec instanceof Map && spec.doc) ? spec.doc : spec
    def s = normalise(doc)
    def problems = validateSpec(doc, s)
    if (problems) error "ImageImport '${s.name ?: '?'}' rejected:\n - ${problems.join('\n - ')}"
    def c = scConfig()

    def srcTagRef = scConfig.pullRef(s.sourceRef)
    def repoPart = srcTagRef.contains('@') ? srcTagRef.split('@')[0] : srcTagRef.replaceFirst(/:[^:\/]+$/, '')
    def digest = s.sourceDigest ?: scDigest(srcTagRef)
    def srcRef = "${repoPart}@${digest}"
    def version = s.version ?: tagOf(s.sourceRef)
    def digest12 = digest.split(':')[-1].take(12)

    // source and revision are the repository this job runs from; the spec wins on every key it sets
    def fromJenkins = [source: env.GIT_URL, revision: env.GIT_COMMIT].findAll { it.value }
    def gl = fromJenkins + s.labels
    problems = validateLabels(s, gl, c.org)
    if (problems) error "ImageImport '${s.name}' rejected:\n - ${problems.join('\n - ')}"

    def destRepoRef = "${s.destRepo}.${c.registry}/${s.destPath}"
    def stagingRef = "${destRepoRef}:_built-${version}-${digest12}"
    sh "docker buildx imagetools create --tag ${stagingRef} ${srcRef}"
    def workdir = "${c.workRoot ?: '.supplychain'}/${s.name}"
    sh "mkdir -p ${workdir}"

    def created = sh(script: 'date -u +%Y-%m-%dT%H:%M:%SZ', returnStdout: true).trim()
    def labels = [
        'org.opencontainers.image.base.name'      : s.sourceRef,
        'org.opencontainers.image.base.digest'    : digest,
        'org.opencontainers.image.created'        : created,
        'org.opencontainers.image.description'    : gl.description,
        'org.opencontainers.image.source'         : gl.source,
        'org.opencontainers.image.revision'       : (gl.revision ?: ''),
        'org.opencontainers.image.version'        : (gl.version ?: version),
        'org.opencontainers.image.vendor'         : gl.vendor,
        'org.opencontainers.image.licenses'       : (gl.licenses ?: ''),
        'org.opencontainers.image.authors'        : (gl.authors ?: ''),
        'org.opencontainers.image.documentation'  : (gl.documentation ?: ''),
        'acme.container.governance.image.auid'    : s.auid,
        'acme.container.governance.image.category': s.category,
    ].findAll { it.value }

    return [
        kind         : 'import',
        name         : "${s.destPath}:${version}",
        origin       : s.origin,
        category     : s.category,
        harden       : s.harden,
        runtime      : s.runtime,
        importedAsIs : !s.harden,
        hardened     : false,
        prodEligible : s.prodEligible,
        platforms    : s.platforms,
        labels       : labels,
        ownerTeam    : (gl.authors ?: s.auid),
        gateSkip     : null,
        tagPlan      : ["${destRepoRef}:${version}-${digest12}", "${destRepoRef}:${version}"],
        qualityStatus: (s.prodEligible ? 'released' : 'dev'),
        catalogProps : ['source.upstreamDigest': digest, 'catalog.category': s.category, 'catalog.variant': 'imported',
                        'catalog.prodEligible': s.prodEligible, 'catalog.origin': s.origin],
        buildType    : 'bisp-image-import',
        workflow     : (file ?: 'Jenkinsfile'),
        baseImage    : [kind: 'imported-image', origin: s.origin, variant: 'imported'],
        importInfo   : [origin: s.origin, sourceRef: s.sourceRef, resolvedFrom: srcTagRef, sourceDigest: digest,
                        sourceRepo: gl.source, sourceRevision: (gl.revision ?: ''),
                        importedBy: (env.BUILD_URL ?: 'local'), harden: s.harden],
        workdir      : workdir, stagingRef: stagingRef, resolved: version, serial: digest12,
        baseDigest   : digest, imageDigest: digest, skipped: false,
    ]
}

private static String tagOf(String ref) {
    if (ref.contains('@')) return ''
    def last = ref.tokenize('/')[-1]
    return last.contains(':') ? last.split(':')[-1] : ''
}

private Map normalise(Map doc) {
    def sp = doc.spec ?: [:]
    def src = sp.source ?: [:]
    return [
        name        : doc.metadata?.name,
        origin      : sp.origin,
        sourceRef   : (src.ref ?: ''),
        sourceDigest: (((src.digest ?: '') ==~ /sha256:[0-9a-f]{64}/) ? src.digest : ''),
        version     : (sp.version ?: '').toString(),
        destRepo    : (sp.destination?.repo ?: ''),
        destPath    : (sp.destination?.path ?: ''),
        prodEligible: (sp.prodEligible ?: false),
        harden      : (sp.harden ?: false),
        runtime     : (sp.runtime ?: 'auto'),
        platforms   : (sp.platforms ?: []),
        labels      : (sp.labels ?: [:]),
        auid        : (doc.metadata?.auid ?: ''),
        category    : (doc.metadata?.category ?: ''),
    ]
}

@groovy.transform.Field static final List CATEGORIES = ['OS', 'MIDDLEWARE', 'DATABASE', 'BUSINESS_CUSTOMIZED', 'APPLICATION', 'OTHER']

// Checked before any registry call.
private List validateSpec(Map doc, Map s) {
    def p = []
    if (doc.kind != 'ImageImport') p << "kind must be ImageImport (got ${doc.kind})"
    if (!(s.name ==~ /[a-z0-9][a-z0-9.-]*/)) p << "metadata.name must match ^[a-z0-9][a-z0-9.-]*\$"
    if (!(s.origin in ['vendor', 'internal'])) p << "spec.origin must be vendor or internal"
    if (!s.sourceRef) p << "spec.source.ref is required"
    if (!s.destRepo || !s.destPath) p << "spec.destination.repo and spec.destination.path are required"
    if (!s.version && !tagOf(s.sourceRef)) p << "spec.version is required when the source ref has no tag"
    if (!s.auid) p << "metadata.auid is required"
    if (!(s.category in CATEGORIES)) p << "metadata.category must be one of ${CATEGORIES}"
    if (s.origin == 'vendor' && s.harden) p << "vendor images are never hardened: spec.harden must be false"
    if (!(s.runtime in ['auto', 'java', 'python', 'dotnet', 'none'])) p << "spec.runtime must be auto, java, python, dotnet or none"
    if (!(s.platforms instanceof List) || !s.platforms) p << "spec.platforms is required (e.g. [linux/amd64])"
    return p
}

// Checked once the checkout values are merged in.
private List validateLabels(Map s, Map gl, String org) {
    def p = []
    if (!gl.description) p << "labels.description is mandatory"
    if (!gl.vendor) p << "labels.vendor is mandatory"
    if (!gl.source) p << "labels.source is mandatory and the job has no GIT_URL to take it from"
    if (s.origin == 'vendor' && org && gl.vendor == org) p << "vendor image: labels.vendor must name the third party, not ${org}"
    return p
}
