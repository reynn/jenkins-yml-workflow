# com.concur.Slack

## send(Map)

> Make a request to the Slack API, attempts are made to ensure the request will be valid.

| Type   | Name      | Default   |
|:-------|:----------|:----------|
| Map    | slackData |           |

### Example

```groovy
def concurSlack = new com.concur.Slack()
def credential = new com.concur.Commands().getCredentialsWithCriteria(['description': 'example credential description'])
def slackData = ['tokenCredentialId': tokenCredential.id,'channel': 'auto-workflow-libs','message': 'Hello from custom com.concur.Slack.send','color': 'good']
println concurSlack.send(slackData)
// Ok assuming successful send
```