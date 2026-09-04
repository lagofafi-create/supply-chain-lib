// The whole chain for one imported image. Provenance is written after hardening so it carries
// the digest that gets published. Locked per destination path.
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
        out = signImage(publishImage(gateImage(scanImage(writeProvenance(hardenImage(acquireImage(s)))))))
    }
    return out
}
