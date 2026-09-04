// Load ImageImport specs from one YAML file or a directory. Disabled specs are dropped.
def call(String path) {
    def files
    if (path.endsWith('.yaml') || path.endsWith('.yml')) {
        files = [path]
    } else {
        def out = sh(script: "ls ${path}/*.yaml ${path}/*.yml 2>/dev/null || true", returnStdout: true).trim()
        files = out ? out.split('\n') as List : []
    }
    return files.collect { f -> [file: f, doc: readYaml(file: f)] }.findAll { it.doc?.spec?.enabled != false }
}
