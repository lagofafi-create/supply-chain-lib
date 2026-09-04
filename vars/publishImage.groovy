// Apply the tag plan to the gated staging manifest, registry side, then set the maturity property.
// Returns the refs to sign.
def call(Map rec) {
    if (rec.skipped) { echo "publish skipped: ${rec.name}"; return rec }
    if (!rec.gate) error "publishImage: ${rec.name} was not gated"
    if (rec.gate.deny && rec.gate.deny != '[]' && rec.gate.deny != 'null') error "publishImage: ${rec.name} was denied"
    def tags = rec.tagPlan as List
    if (!tags) error "publishImage: empty tagPlan for ${rec.name}"

    tags.each { t -> sh "docker buildx imagetools create --tag ${t} ${rec.stagingRef}" }
    def immutable = tags[0]
    sh "docker buildx imagetools create --tag ${rec.stagingRef.replace(':_built-', ':_ok-')} ${rec.stagingRef}"
    echo "published ${tags.size()} tags (immutable=${immutable})"

    def props = (["quality.status=${rec.qualityStatus ?: 'builder'}"] +
                 (rec.catalogProps ?: [:]).collect { k, v -> "${k}=${v}" }).join(';')
    scProps(immutable, props)

    // sign the floating ref: same digest, consumer facing tag on the signature record
    def primaryFloating = tags.size() > 1 ? tags[1] : immutable
    return rec + [tags: tags, published: immutable, signRefs: [primaryFloating]]
}
