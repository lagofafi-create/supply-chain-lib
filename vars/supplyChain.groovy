// The whole chain for one imported image. Every import is rebuilt once, either hardened or
// label only, so the governance labels are in the image; provenance is written after that so it
// carries the digest that gets published; verification runs last, on the published digest.
def call(Object spec) {
    def s
    if (spec instanceof CharSequence) {
        s = [file: spec.toString(), doc: readYaml(file: spec.toString())]
    } else if (spec instanceof Map && spec.doc) {
        s = spec
    } else {
        s = [file: null, doc: spec]
    }
    def doc = s.doc
    def key = (doc.spec?.destination?.path ?: "vendor/${doc.metadata?.name}").toString().replaceAll('[^A-Za-z0-9._-]', '-')
    def out = null
    lock("sc-${key}") {
        out = verifyPublished(signImage(publishImage(gateImage(scanImage(writeProvenance(labelImage(hardenImage(acquireImage(s)))))))))
    }
    return out
}
