// Per image provenance.json, the input the Supply Chain CLI turns into the SLSA predicate.
// Shape: resources/slsa/provenance.input.example.json and provenance.input.import.example.json.
// Every required field must be present, and in production none may hold a local fallback.
def call(Map rec) {
    def json = [
        imageSource: [
            repo  : (env.GIT_URL ?: 'unknown'),
            ref   : (env.GIT_BRANCH ?: 'refs/heads/main'),
            commit: (env.GIT_COMMIT ?: 'local'),
        ],
        ci: [
            provider    : 'jenkins',
            workflow    : (rec.workflow ?: 'Jenkinsfile'),
            workflowName: (env.JOB_NAME ?: 'supply-chain'),
            runId       : (env.BUILD_NUMBER ?: '0'),
            runAttempt  : 1,
        ],
        container: [
            imageRepo: rec.stagingRef.replaceFirst(/:[^:\/]+$/, ''),
            platform : (rec.platforms ?: ['linux/amd64']).join(','),
            digest   : rec.imageDigest,
        ],
        customMetadata: [
            owner_team  : (scConfig().defaults?.labels?.authors ?: 'devsecops-cd-team'),
            build_number: (env.BUILD_NUMBER ?: '0'),
            environment : (env.CA_SOURCE == 'dev-fake' ? 'dev' : 'production'),
        ],
        baseImage: (rec.baseImage ?: [kind: 'internal-base-image']),
    ]
    if (rec.importInfo) json['import'] = rec.importInfo

    def missing = missingFields(json)
    if (missing) error "provenance.json for ${rec.name} is incomplete: ${missing.join(', ')}"
    if (!(json.container.digest ==~ /sha256:[0-9a-f]{64}/)) error "provenance.json for ${rec.name}: bad digest ${json.container.digest}"

    writeJSON file: "${rec.workdir}/provenance.json", json: json, pretty: 2
    echo "provenance.json written: ${rec.workdir}/provenance.json (digest ${rec.imageDigest})"
    return rec + [provenance: "${rec.workdir}/provenance.json"]
}

private static final List REQUIRED = [
    'imageSource.repo', 'imageSource.ref', 'imageSource.commit',
    'ci.provider', 'ci.workflow', 'ci.workflowName', 'ci.runId',
    'container.imageRepo', 'container.platform', 'container.digest',
    'customMetadata.owner_team', 'customMetadata.build_number', 'customMetadata.environment',
    'baseImage.kind',
]
private static final List REQUIRED_IMPORT = ['import.origin', 'import.sourceRef', 'import.sourceDigest', 'import.importedBy']
private static final List FALLBACKS = ['unknown', 'local', '0']

@NonCPS
List missingFields(Map json) {
    def production = json.customMetadata?.environment == 'production'
    def paths = REQUIRED + (json['import'] != null ? REQUIRED_IMPORT : [])
    return paths.findAll { p ->
        def v = p.split('\\.').inject(json) { node, k -> node instanceof Map ? node[k] : null }
        v == null || v.toString().trim() == '' || (production && v.toString() in FALLBACKS)
    }
}
