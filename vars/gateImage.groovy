// Policy gate on a record. Fails closed: no decision means deny. On deny the staging image is
// quarantined and the build stops.
//
// The decision itself (rules, CTI score, waivers) moves to the Supply Chain API. When it is wired:
//   sh "scs gate --input ${wd}/gate-input.json --report ${wd}/scan.json --sbom ${wd}/sbom.json --out ${wd}/gate-decision.json"
//   deny = groovy.json.JsonOutput.toJson(readJSON(file: "${wd}/gate-decision.json").deny ?: [])
// Until then the same input goes through opa, with the rules repo from RULES_REPO_DIR or the
// example policy bundled with this library.
def call(Map rec) {
    if (rec.skipped) { echo "gate skipped: ${rec.name}"; return rec }
    def wd = rec.workdir
    def target = rec.prodEligible ? 'release' : 'dev'
    def scan = fileExists("${wd}/scan.json") ? readJSON(file: "${wd}/scan.json") : null
    writeJSON file: "${wd}/gate-input.json", json: [
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

    def deny = '[]'
    if (rec.gateSkip) {
        echo "gate skipped: ${rec.gateSkip}"
    } else {
        sh 'command -v opa >/dev/null 2>&1 || { echo "opa is required on the agent"; exit 90; }'
        def rulesDir = env.RULES_REPO_DIR?.trim()
        if (!rulesDir) {
            rulesDir = "${wd}/policy"
            writeFile file: "${rulesDir}/gate.rego", text: libraryResource('policy/gate.rego')
        }
        deny = sh(script: "opa eval -f raw -d ${rulesDir} -i ${wd}/gate-input.json 'data.imagefactory.gate.deny'",
                  returnStdout: true).trim()
        if (!deny || deny == 'undefined') {
            error "Policy gate produced no decision (rules dir '${rulesDir}' missing or package mismatch)"
        }
    }
    if (deny && deny != '[]' && deny != 'null') {
        scProps(rec.stagingRef, 'quality.status=quarantine')
        error "Policy gate DENY for ${rec.name} [${target}]: ${deny}"
    }
    return rec + [gate: [target: target, deny: deny]]
}
