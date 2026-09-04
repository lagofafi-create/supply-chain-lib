// Library configuration. Bundled defaults first, then config/registry.yaml and config/defaults.yaml
// from the workspace, then the file named in SC_CONFIG, then REGISTRY and REPO from the environment.
def call() {
    if (binding.hasVariable('_scConfigCache') && _scConfigCache) return _scConfigCache
    def m = readYaml(text: libraryResource('config/supply-chain.yaml'))
    if (fileExists('config/registry.yaml')) m = deepMerge(m, readYaml(file: 'config/registry.yaml'))
    if (fileExists('config/defaults.yaml')) m.defaults = deepMerge(m.defaults ?: [:], readYaml(file: 'config/defaults.yaml'))
    def extra = env.SC_CONFIG?.trim()
    if (extra) {
        if (!fileExists(extra)) error "SC_CONFIG file not found: ${extra}"
        m = deepMerge(m, readYaml(file: extra))
    }
    if (env.REGISTRY?.trim()) m.registry = env.REGISTRY.trim()
    if (env.REPO?.trim()) m.repo = env.REPO.trim()
    if (env.SC_API_URL?.trim()) m.api = (m.api ?: [:]) + [url: env.SC_API_URL.trim()]
    binding.setVariable('_scConfigCache', m)
    return m
}

@NonCPS
Map deepMerge(Map a, Map b) {
    def out = [:] + (a ?: [:])
    (b ?: [:]).each { k, v -> out[k] = (v instanceof Map && out[k] instanceof Map) ? deepMerge(out[k], v) : v }
    return out
}

// Upstream ref to its Artifactory pull-through ref. Refs already on our registry pass through.
String pullRef(String ref) {
    def c = call()
    def reg = c.registry
    def first = ref.split('/', 2)[0]
    if (first == reg || first.endsWith(".${reg}")) return ref
    def isHost = first.contains('.') || first.contains(':') || first == 'localhost'
    def host = isHost ? first : 'docker.io'
    def path = !ref.contains('/') ? "library/${ref}" : (isHost ? ref.substring(first.length() + 1) : ref)
    def pull = c.pull ?: [:]
    def remote = pull[host] ?: pull['default']
    return remote ? "${remote}.${reg}/${path}" : ref
}
