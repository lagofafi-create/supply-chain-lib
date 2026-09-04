// Last step: check that what we published really carries a signature and the two attestations
// signImage attached. Nothing to check while the Supply Chain API is not enabled.
def call(Map rec) {
    if (rec.skipped) return rec
    def c = scConfig()
    if (!c.api?.enabled) {
        echo "verifyPublished skipped: Supply Chain API not enabled"
        return rec + [verified: false]
    }
    def expected = ['https://slsa.dev/provenance/v1', 'https://cyclonedx.org/bom']
    def ref = "${rec.published.replaceFirst(/:[^:\/]+$/, '')}@${rec.imageDigest}"
    verifySignature(ref, [workdir: rec.workdir])
    def have = getAttestations(ref, [workdir: rec.workdir]).collect { it.predicateType }
    def missing = expected.findAll { !(it in have) }
    if (missing) error "${ref} is missing attestations: ${missing.join(', ')}"
    return rec + [verified: true]
}
