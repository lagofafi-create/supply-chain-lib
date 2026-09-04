// Manifest digest of an image ref. Older buildx versions ignore --format and print the full
// inspect report, so the first Digest line of that report is used when no bare digest comes back.
def call(String ref) {
    def out = sh(script: "docker buildx imagetools inspect ${ref} --format '{{.Manifest.Digest}}'", returnStdout: true).trim()
    if (out ==~ /sha256:[0-9a-f]{64}/) return out
    def line = out.readLines().find { it.trim().startsWith('Digest:') }
    def digest = line ? line.replaceFirst(/^\s*Digest:\s*/, '').trim() : ''
    if (!(digest ==~ /sha256:[0-9a-f]{64}/)) error "could not resolve a digest for ${ref}: ${out.take(300)}"
    return digest
}
