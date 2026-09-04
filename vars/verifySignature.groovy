// Verify an image signature through the Supply Chain API. Placeholder until the API is wired:
// until then a requested verification fails, an unverified image is never treated as verified.
// Expected response: { "verified": true, "signer": "...", "keyId": "...", "reason": "..." }
def call(String ref, Map opts = [:]) {
    def c = scConfig()
    def wd = (opts.workdir ?: c.workRoot ?: '.supplychain').toString()
    def out = "${wd}/verify-${ref.replaceAll('[^A-Za-z0-9._-]', '_')}.json"
    sh "mkdir -p ${wd}"
    // withCredentials([string(credentialsId: (c.credentials?.api ?: 'supply-chain-api-token'), variable: 'SC_API_TOKEN')]) {
    //     sh """curl -fsS -X POST '${c.api?.url}/v1/signatures/verify' \\
    //              -H "Authorization: Bearer \$SC_API_TOKEN" -H 'Content-Type: application/json' \\
    //              -d '{"image": "${ref}"}' -o ${out}"""
    // }
    // def res = readJSON(file: out)
    // if (!res.verified) error "signature verification failed for ${ref}: ${res.reason ?: 'not signed'}"
    // echo "signature verified for ${ref} (signer ${res.signer})"
    // return res
    error "verifySignature: Supply Chain API not wired (api.url and credentials.api), refusing to treat ${ref} as verified"
}
