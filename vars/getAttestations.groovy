// All attestations attached to an image digest, from the Supply Chain API. Placeholder until the
// API is wired: returns an empty list, so a spec that requires attestations fails in acquireImage.
// Expected response: [ { "predicateType": "https://slsa.dev/provenance/v1", "digest": "sha256:...",
//                        "issuer": "...", "createdAt": "..." }, ... ]
def call(String ref, Map opts = [:]) {
    def c = scConfig()
    def wd = (opts.workdir ?: c.workRoot ?: '.supplychain').toString()
    def out = "${wd}/attestations-${ref.replaceAll('[^A-Za-z0-9._-]', '_')}.json"
    sh "mkdir -p ${wd}"
    // withCredentials([string(credentialsId: (c.credentials?.api ?: 'supply-chain-api-token'), variable: 'SC_API_TOKEN')]) {
    //     sh """curl -fsS -G '${c.api?.url}/v1/attestations' --data-urlencode 'image=${ref}' \\
    //              -H "Authorization: Bearer \$SC_API_TOKEN" -o ${out}"""
    // }
    // def res = readJSON(file: out)
    // echo "${res.size()} attestation(s) on ${ref}: ${res.collect { it.predicateType }.join(', ')}"
    // return res
    echo "getAttestations: Supply Chain API not wired, no attestations retrieved for ${ref}"
    return []
}
