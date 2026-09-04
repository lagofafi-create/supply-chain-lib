// Write the governance labels into an imported image that is not being hardened: a config only
// rebuild, FROM the pinned digest plus LABEL lines, no RUN. The layers stay the source's, only the
// image config and therefore the manifest digest change. Hardened imports get their labels from
// hardenImage and skip this.
def call(Map rec) {
    if (rec.skipped || rec.hardened || rec.harden) return rec
    def ctx = "${rec.workdir}/label"
    sh "rm -rf ${ctx} && mkdir -p ${ctx}"
    def pinned = "${rec.stagingRef.replaceFirst(/:[^:\/]+$/, '')}@${rec.imageDigest}"
    def lines = ["# syntax=docker/dockerfile:1.7", "FROM ${pinned}"]
    (rec.labels ?: [:]).sort().each { k, v -> lines << "LABEL ${q(k)}=${q(v)}" }
    writeFile file: "${ctx}/Dockerfile", text: lines.join('\n') + '\n'

    def labelledRef = "${rec.stagingRef}-labelled"
    def platforms = (rec.platforms ?: ['linux/amd64']).join(',')
    sh "docker buildx build --platform ${platforms} -f ${ctx}/Dockerfile -t ${labelledRef} --push ${ctx}"
    def digest = scDigest(labelledRef)
    echo "labelled ${rec.name}: ${rec.imageDigest} -> ${digest} (${rec.labels.size()} labels)"
    return rec + [stagingRef: labelledRef, imageDigest: digest,
                  importInfo: (rec.importInfo ?: [:]) + [labelledDigest: digest]]
}

private static String q(Object s) {
    return '"' + s.toString().replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$') + '"'
}
