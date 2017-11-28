#!/usr/bin/groovy
// vars/bhNotify.groovy
import groovy.json.internal.Exceptions

// Send Slack notifications to the interested channel
// This is uses the configuration from the plugin on the Jenkins master if nothing is provided
def call(buildStatus, useAttachments, channel = '', token = '', domain = env.DEFAULT_SLACK_DOMAIN, org = '', repo = '') {

  def concurGithub    = new com.concur.GitHubApi()
  def concurPipeline  = new com.concur.Commands()
  def concurHttp      = new com.concur.Http()

  def orgAndRepo  = concurGithub.getGitHubOrgAndRepo()
  org             = org   ?: orgAndRepo.org
  repo            = repo  ?: orgAndRepo.repo

  // No need to proceed if there is no org or repo
  if( !org || !repo) {
    println "\u001B[31mNot able to send a slack notification. No org or repo defined.\\u001B[0m"
    return
  }

  // build status of null means successful
  buildStatus = buildStatus ?: 'SUCCESS'
  channel     = channel     ?: null
  token       = token       ?: null
  domain      = domain      ?: null

  // Default values for parameters
  def colorCode = '#DC143C'
  def subject   = "${buildStatus}"
  def summary   = "${subject}"
  def details   = [
    "Job"     : env.JOB_NAME.replaceAll('%2F', '/'),
    "Branch"  : env.BRANCH_NAME,
    "Build"   : "<${env.BUILD_URL}|${env.BUILD_NUMBER}>",
    "Commit"  : "<http://github.concur.com/${org}/${repo}/commit/${env.GIT_SHORT_COMMIT}|${env.GIT_SHORT_COMMIT}>",
    "Author"  : env.GIT_AUTHOR
  ]

  // Override default values based on build status
  if (buildStatus == 'STARTED') {
    colorCode = '#87CEEB'
    subject   = "${buildStatus}\n"
    summary   = "${subject}*Build Number* <${env.BUILD_URL}|${env.BUILD_NUMBER}> started on ${env.JENKINS_URL.replaceAll('/$', "")} for branch *${env.BRANCH_NAME}*"
  } else if (buildStatus == 'SUCCESS') {
    colorCode = '#3CB371'
  } else {
    colorCode = '#DC143C'
  }

  // Send notifications
  try {
    def slackData = [:]
    if (channel != null && token != null && domain != null) {
      slackData = [color: colorCode, message: summary, token: token, teamDomain: domain, channel: channel]
    } else if (channel != null && domain != null) {
      def cred = concurPipeline.getCredentialsWithCriteria(['description': env.DEFAULT_SLACK_TOKEN_DESC]).id
      slackData = [color: colorCode, message: summary, tokenCredentialId: cred, teamDomain: domain, channel: channel]
    } else {
      // NOTE: without a channel set, it will send using default channel for default token.
      slackData = [color: colorCode, message: summary]
    }
    if (buildStatus != 'STARTED' && useAttachments) {
      def attachments = [
        ['text', summary],
        ['color', colorCode],
        ['fallback', "${summary}\n${details.collect { "*${it.key}*: ${it.value}" }.join('\n')}"],
        ['fields', details.collect { ["title": it.key, "value": it.value, "short": true] }]
      ]
      slackData.remove('message')
      slackData.put('attachments', new com.concur.Util().toJSON(attachments))
    }
    concurHttp.sendSlackMessage(slackData)
  } catch (java.lang.NoSuchMethodError | Exception e) {
    println "Not able to send slack notification. Please make sure that the plugin is installed and configured correctly."
    println "Error: $e"
  }
}
