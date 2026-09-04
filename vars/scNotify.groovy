// Failure mail to defaults.notify.email. Degrades to an echo when config or address is missing.
def call(String context) {
    def to = null
    try { to = scConfig().defaults?.notify?.email } catch (ignored) { }
    def subject = "[supply-chain] ${context} FAILED: ${env.JOB_NAME ?: '?'} #${env.BUILD_NUMBER ?: '?'}"
    if (to) {
        mail to: to, subject: subject, body: "${context} failed.\n\n${env.BUILD_URL ?: ''}"
    } else {
        echo "${subject} (no notify.email configured)"
    }
}
