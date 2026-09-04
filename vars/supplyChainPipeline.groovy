// Consumer entry point. A team's Jenkinsfile is:
//     @Library('supply-chain-lib@v1') _
//     supplyChainPipeline(spec: 'supplychain/jboss-eap.yaml', credentialsId: 'payments-artifactory')
// spec is one YAML file or a directory of them. credentialsId names a Jenkins username/password
// credential with write access to the destination repo; it defaults to credentials.docker in the config.
def call(Map args = [:]) {
    def specPath = (args.spec ?: 'supplychain.yaml').toString()
    def agentLabel = (args.agentLabel ?: 'buildkit').toString()
    def context = (args.context ?: "Supply chain (${specPath})").toString()
    def credId = (args.credentialsId ?: scConfig().credentials?.docker ?: 'artifactory-docker').toString()
    pipeline {
        agent { label agentLabel }
        options { disableConcurrentBuilds(); timestamps() }
        stages {
            stage('supply-chain') {
                steps {
                    withCredentials([usernamePassword(credentialsId: credId, usernameVariable: 'AF_USER', passwordVariable: 'AF_PASS')]) {
                        script {
                            env.REGISTRY = scConfig().registry
                            def specs = scSpecs(specPath)
                            if (!specs) error "no enabled ImageImport spec found at ${specPath}"
                            scLogin(specs.collect { it.doc.spec?.destination?.repo }.findAll { it })
                            def branches = [:]
                            specs.each { s -> branches[s.doc.metadata.name] = { supplyChain(s) } }
                            parallel branches
                        }
                    }
                }
            }
        }
        post { failure { scNotify(context) } }
    }
}
