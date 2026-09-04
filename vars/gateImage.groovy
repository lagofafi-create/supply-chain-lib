// Policy gate on a record. Everything is decided here with opa (labels, hardening, scan present,
// SBOM present, dev CA) from the rules repo in RULES_REPO_DIR or the example policy bundled with
// this library. The CVE verdict and the CTI score come from the Supply Chain API and are merged
// into the same decision; until that call is wired the local policy also applies the interim
// CVE thresholds. Fails closed: no decision means deny. On deny the staging image is quarantined
// and the build stops.
def call(Map rec) {
    if (rec.skipped) { echo "gate skipped: ${rec.name}"; return rec }
    def wd = rec.workdir
    def target = rec.prodEligible ? 'release' : 'dev'
    def scan = fileExists("${wd}/scan.json") ? readJSON(file: "${wd}/scan.json") : null
    def input = [
        target       : target,
        kind         : (rec.kind ?: 'import'),
        origin       : (rec.origin ?: 'built'),
        importedAsIs : (rec.importedAsIs ?: false),
        hardened     : (rec.hardened ?: false),
        labels       : (rec.labels ?: [:]),
        scan         : [available: (scan?.criticalCount != null && scan?.highCount != null),
                        criticalCount: (scan?.criticalCount ?: 0), highCount: (scan?.highCount ?: 0),
                        ctiScore: (scan?.ctiScore ?: 0)],
        sbomGenerated: fileExists("${wd}/sbom.json"),
    ]

    // CVE verdict and CTI score from the Supply Chain API. When it is wired:
    //   sh "scs gate --image ${rec.stagingRef} --category ${rec.category} --report ${wd}/scan.json --sbom ${wd}/sbom.json --out ${wd}/cve-decision.json"
    //   def cve = readJSON(file: "${wd}/cve-decision.json")   // { "deny": ["CVE-... critical"], "ctiScore": n }
    //   input.scan.ctiScore = cve.ctiScore ?: 0
    //   apiDeny = (cve.deny ?: []) as List
    def apiDeny = []

    writeJSON file: "${wd}/gate-input.json", json: input
    def denies = []
    if (rec.gateSkip) {
        echo "gate skipped: ${rec.gateSkip}"
    } else {
        sh 'command -v opa >/dev/null 2>&1 || { echo "opa is required on the agent"; exit 90; }'
        def rulesDir = env.RULES_REPO_DIR?.trim()
        if (!rulesDir) {
            rulesDir = "${wd}/policy"
            writeFile file: "${rulesDir}/gate.rego", text: libraryResource('policy/gate.rego')
        }
        def raw = sh(script: "opa eval -f raw -d ${rulesDir} -i ${wd}/gate-input.json 'data.imagefactory.gate.deny'",
                     returnStdout: true).trim()
        if (!raw || raw == 'undefined') {
            error "Policy gate produced no decision (rules dir '${rulesDir}' missing or package mismatch)"
        }
        denies = (readJSON(text: raw) ?: []) as List
    }
    denies += apiDeny
    if (denies) {
        scProps(rec.stagingRef, 'quality.status=quarantine')
        error "Policy gate DENY for ${rec.name} [${target}]:\n - ${denies.join('\n - ')}"
    }
    return rec + [gate: [target: target, deny: denies]]
}
