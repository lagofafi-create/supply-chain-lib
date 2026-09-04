// Ask the Supply Chain API whether an image digest carries a valid signature. Used after
// signImage on what we just published. Skipped while api.enabled is false.
// Response: { "verified": true, "signer": "...", "keyId": "...", "reason": "..." }
def call(String ref, Map opts = [:]) {
    def c = scConfig()
    if (!c.api?.enabled) {
        echo "verifySignature skipped: Supply Chain API not enabled (api.enabled)"
        return [verified: false, skipped: true]
    }
    def wd = (opts.workdir ?: c.workRoot ?: '.supplychain').toString()
    def out = "${wd}/verify-${ref.replaceAll('[^A-Za-z0-9._-]', '_')}.json"
    sh "mkdir -p ${wd}"
    withCredentials([string(credentialsId: (c.credentials?.api ?: 'supply-chain-api-token'), variable: 'SC_API_TOKEN')]) {
        sh """curl -fsS -X POST '${c.api.url}/v1/signatures/verify' \\
                 -H "Authorization: Bearer \$SC_API_TOKEN" -H 'Content-Type: application/json' \\
                 -d '{"image": "${ref}"}' -o ${out}"""
    }
    def res = readJSON(file: out)
    if (!res.verified) error "signature verification failed for ${ref}: ${res.reason ?: 'not signed'}"
    echo "signature verified for ${ref} (signer ${res.signer ?: '?'})"
    return res
}
