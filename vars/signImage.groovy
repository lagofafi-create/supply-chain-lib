// Sign and attest the published refs with the Supply Chain CLI. Only a published digest can be
// signed, and only a prod eligible one: a signature is what admission accepts, so an image that
// may not go to production never gets one. Placeholder until scs is wired; a failure here must
// fail the build.
def call(Map rec) {
    if (rec.skipped) return rec
    if (!rec.prodEligible) { echo "not signed: ${rec.name} is not prod eligible (quality.status ${rec.qualityStatus})"; return rec }
    def wd = rec.workdir
    def buildType = rec.buildType ?: 'bisp-image-import'
    def builderId = env.BUILD_URL ?: 'https://jenkins/supply-chain'
    (rec.signRefs ?: []).each { ref ->
        sh """
            scs sign   --image ${ref}
            scs attest --image ${ref} --predicate slsaprovenance --provenance ${rec.provenance ?: wd + '/provenance.json'} \\
                       --buildtype ${buildType} --builder-id '${builderId}'
            scs attest --image ${ref} --predicate cyclonedx      --sbom ${rec.sbom ?: wd + '/sbom.json'}
        """
        echo "signed and attested (${buildType}): ${ref}"
    }
    return rec
}
