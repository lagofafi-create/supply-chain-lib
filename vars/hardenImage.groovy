// harden: true for internal images. Wrap-build: the internal CA goes into the OS and runtime trust
// stores (runtime detected unless the spec says), harden.sh runs, the result is flattened to one
// layer, then the source config (the flatten drops it) is re-emitted and the labels baked in.
// wrap.py renders the Dockerfile from the image config read out of the registry.
// The source needs a POSIX sh for harden.sh to run.
def call(Map rec) {
    if (rec.skipped || !rec.harden) return rec
    if (rec.origin == 'vendor') error "hardenImage: vendor images are never hardened (${rec.name})"
    def ctx = "${rec.workdir}/harden"
    sh "rm -rf ${ctx} && mkdir -p ${ctx}/hardening"
    ['harden.sh', 'install-certs.sh'].each { f -> writeFile file: "${ctx}/hardening/${f}", text: libraryResource("hardening/${f}") }
    writeFile file: "${ctx}/wrap.py", text: libraryResource('hardening/wrap.py')
    def c = scConfig()
    def certs = (c.certs ?: []) as List
    certs.each { name -> writeFile file: "${ctx}/certs/${name}.crt", text: libraryResource("certs/${name}.crt") }
    def certArgs = certs ? "--certs --runtime ${rec.runtime ?: 'auto'} --alias-prefix ${c.certAliasPrefix ?: 'internal'}" : ''

    def pinned = "${rec.stagingRef.replaceFirst(/:[^:\/]+$/, '')}@${rec.imageDigest}"
    // the source config through the docker engine: works on every docker version, and the
    // wrap-build pulls the same image anyway
    sh "docker pull -q ${pinned} >/dev/null"
    def cfgJson = sh(script: "docker inspect --format '{{json .Config}}' ${pinned}", returnStdout: true).trim()
    writeFile file: "${ctx}/image-config.json", text: cfgJson
    writeJSON file: "${ctx}/labels.json", json: (rec.labels ?: [:])
    sh "python3 ${ctx}/wrap.py --from ${pinned} --config ${ctx}/image-config.json --labels ${ctx}/labels.json ${certArgs} --out ${ctx}/Dockerfile"

    def hardenedRef = "${rec.stagingRef}-hardened"
    def platforms = (rec.platforms ?: ['linux/amd64']).join(',')
    sh "docker buildx build --platform ${platforms} -f ${ctx}/Dockerfile -t ${hardenedRef} --provenance=true --sbom=true --push ${ctx}"
    def digest = scDigest(hardenedRef)
    echo "hardened ${rec.name}: ${rec.imageDigest} -> ${digest}"

    return rec + [
        stagingRef  : hardenedRef,
        imageDigest : digest,
        hardened    : true,
        importedAsIs: false,
        buildType   : 'bisp-hardened-import',
        catalogProps: (rec.catalogProps ?: [:]) + ['catalog.variant': 'hardened'],
        baseImage   : (rec.baseImage ?: [:]) + [variant: 'hardened'],
        importInfo  : (rec.importInfo ?: [:]) + [preHardenDigest: rec.imageDigest, hardened: true],
    ]
}
