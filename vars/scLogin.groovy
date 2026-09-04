// Docker login to the pull-through remotes and to the repos passed in (the specs' destinations).
// With subdomain access each repo is its own host. Expects AF_USER and AF_PASS from withCredentials.
def call(List repos = []) {
    def c = scConfig()
    ((c.pull ?: [:]).values() + repos).findAll { it }.unique().each { r ->
        sh "echo \"\$AF_PASS\" | docker login -u \"\$AF_USER\" --password-stdin ${r}.${c.registry}"
    }
}
