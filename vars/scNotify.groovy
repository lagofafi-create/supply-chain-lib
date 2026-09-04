// Failure mail to the address the consumer gave. Without one the failure is only logged.
def call(String context, String to = null) {
    def subject = "[supply-chain] ${context} FAILED: ${env.JOB_NAME ?: '?'} #${env.BUILD_NUMBER ?: '?'}"
    if (to) {
        mail to: to, subject: subject, body: "${context} failed.\n\n${env.BUILD_URL ?: ''}"
    } else {
        echo "${subject} (no notifyEmail given, no mail sent)"
    }
}
