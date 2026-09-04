// Artifactory properties on an image ref. Optional: skipped when jf is not on the agent.
def call(String ref, String props) {
    def registry = scConfig().registry
    def artPath = ref.replaceFirst("[.]${registry}/", '/').replaceFirst(/:([^:\/]+)$/, '/$1')
    sh "command -v jf >/dev/null 2>&1 && jf rt sp '${artPath}' '${props}' || echo '[skip] jf not on agent, not setting: ${props}'"
}
