// Consumer entry point. A team's Jenkinsfile is:
//     @Library('supply-chain-lib@v1') _
//     supplyChainPipeline(spec: 'supplychain/jboss-eap.yaml', credentialsId: 'payments-artifactory',
//                         notifyEmail: 'payments-team@acme.example')
// spec is one YAML file or a directory of them. credentialsId names a Jenkins username/password
// credential that reads the source and writes the destination repo. notifyEmail is optional.
def call(Map args = [:]) {
    def specPath = (args.spec ?: 'supplychain.yaml').toString()
    def credId = (args.credentialsId ?: '').toString()
    if (!credId) error "supplyChainPipeline: credentialsId is required (a Jenkins username/password credential for Artifactory)"
    def agentLabel = (args.agentLabel ?: 'buildkit').toString()
    def notifyEmail = args.notifyEmail ? args.notifyEmail.toString() : null
    def context = (args.context ?: "Supply chain (${specPath})").toString()
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
        post { failure { scNotify(context, notifyEmail) } }
    }
}
