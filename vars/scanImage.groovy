// Vulnerability scan plus SBOM. Placeholder output until the real scanner is wired:
//   sh "trivy image --format cyclonedx --output ${wd}/sbom.json ${ref}"
//   sh "trivy image --scanners vuln --format json --output ${wd}/scan.json ${ref}"
// or: sh "scs scan --image ${ref} --sbom ${wd}/sbom.json --report ${wd}/scan.json"
def call(Map rec) {
    if (rec.skipped) return rec
    def ref = rec.stagingRef
    def wd = rec.workdir
    echo "scan (placeholder): ${ref}"
    writeJSON file: "${wd}/sbom.json", json: [bomFormat: 'CycloneDX', specVersion: '1.5', components: [], _placeholder: true]
    writeJSON file: "${wd}/scan.json", json: [available: true, criticalCount: 0, highCount: 0, _placeholder: true]
    def scan = readJSON file: "${wd}/scan.json"
    return rec + [
        sbom      : "${wd}/sbom.json",
        scanReport: "${wd}/scan.json",
        scan      : [available: true, criticalCount: (scan.criticalCount ?: 0), highCount: (scan.highCount ?: 0)],
    ]
}
