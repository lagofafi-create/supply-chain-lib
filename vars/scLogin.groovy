// Docker login to every repo we touch. With subdomain access each repo is its own host.
// Expects AF_USER and AF_PASS from withCredentials.
def call(List extraRepos = []) {
    def c = scConfig()
    ([c.repo] + (c.pull ?: [:]).values() + extraRepos).findAll { it }.unique().each { r ->
        sh "echo \"\$AF_PASS\" | docker login -u \"\$AF_USER\" --password-stdin ${r}.${c.registry}"
    }
}
