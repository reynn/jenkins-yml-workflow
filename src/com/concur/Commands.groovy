#!/usr/bin/env groovy
package com.concur

import groovy.transform.Field;
import jenkins.model.*;
import org.yaml.snakeyaml.*;
import com.cloudbees.hudson.plugins.folder.*;
import com.cloudbees.hudson.plugins.folder.properties.*;
import com.cloudbees.hudson.plugins.folder.properties.FolderCredentialsProvider.FolderCredentialsProperty;
import com.cloudbees.plugins.credentials.impl.*;
import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.domains.*;
import org.codehaus.groovy.runtime.GStringImpl;

@Field def concurUtil = new com.concur.Util()

// ########################
// Workflow Execution Methods
// ########################

// Run the workflow steps for the appropriate sections
def runSteps(Map yml, String branch=env.BRANCH_NAME) {
  assert yml : """|No YML contents provided. An example pipelines.yml file would look like this:
                  |------------------------------------------------------------------------------
                  |pipelines:
                  |  tools:
                  |    branches:
                  |      feature: .+
                  |  branches:
                  |    feature:
                  |      steps:
                  |        - script: 'ls -la'""".stripMargin()
  branch = checkBranch(yml,branch)
  assert branch : """|Unable to determine branch pattern for ${env.BRANCH_NAME}
                     |----------------------------------------
                     |Please ensure your yml file contains a section under tools.branches.patterns
                     |pipelines:
                     |  tools:
                     |    branches:
                     |      patterns:
                     |        master: master
                     |        develop: develop
                     |        feature: .+
                     |        """.stripMargin()

  debugPrint(["branch": branch, "yml": yml,])

  def workflows = yml.branches?."$branch"?.steps

  assert workflows : """|Unable to determine steps to take for branch: ${branch}
                        |----------------------------------------
                        |Please check your YML structure to ensure it is correct, example:
                        |branches:
                        |  feature:
                        |    steps:
                        |      - slack:
                        |        - send: "The {{ current_branch }} branch has a pull request available."
                        """.stripMargin()
  def workflowsList = []
  try{
    workflowsList = workflows.toList()
  } catch (e) {
    error("""|Error while trying to execute workflow steps
             |Please ensure your steps are formatted correctly.
             |A '-' at the beginning of a line indicates a list item which your steps should be
             |----------------------------------------
             |Correct:
             |branches:
             |  master:
             |    steps:
             |      - script: "echo 'Hello World'"
             |
             |Incorrect
             |branches:
             |  master:
             |    steps:
             |      script: "echo 'Hello World'"
             """.stripMargin())
  }
  workflowsList.each { workflow ->
    executeWorkflow(workflow, yml)
  }
}

private executeWorkflow(Map workflow, Map yml) {
  def stages = []
  def stageNum = 0
  workflow.each { section ->
    def workflowName = section.key
    def workflowFile = loadWorkflows("${workflowName}", yml)
    section.value.each { step ->
      def stepName = step instanceof Map ? step.keySet().first() : step
      def stageStart = System.currentTimeMillis()
      def stageName = ""
      try {
        def params = step[stepName]
        debugPrint(['workflowName': workflowName, 'stepName': stepName, 'params': params])
        stageName = getStageName(workflowFile, stages, workflowName, stepName, yml, params)
        stage(stageName) {
          executeParameterizedStep(workflowFile, workflowName, stepName, params, yml)
        }
      } catch(org.jenkinsci.plugins.workflow.steps.FlowInterruptedException | hudson.AbortException e1) {
        println "${Constants.Colors.RED}Build was cancelled${Constants.Colors.CLEAR}"
        error("""${Constants.Colors.RED}Error while executing step: ${workflowName}/${stepName}
                |------------------------
                |${e1}""".stripMargin())
      } catch(e2) {
        error("Encountered an error while executing: ${workflowName}: ${stepName}\n${e2}\n${e2.getStackTrace()}")
      } finally {
        def stageEnd = System.currentTimeMillis()
        def stageTime = (stageEnd - stageStart)/1000
        println "Stage [${stageName}] took ${stageTime} seconds"
      }
    }
  }
}

private executeParameterizedStep(workflow, sectionName, stepName, stepValues, yml) {
  debugPrint([
    'workflow'      : workflow,
    'sectionName'   : sectionName,
    'stepName'      : stepName,
    'stepValues'    : stepValues,
    'yml'           : yml
  ])
  Boolean doubleMap = workflow.metaClass.respondsTo(workflow, stepName, Map, Map)
  Boolean singleMap = workflow.metaClass.respondsTo(workflow, stepName, Map)

  if (doubleMap) {
    debugPrint("Executing ${stepName} with yml and args".center(80, '-'), 2)
    workflow."${stepName}"(yml, stepValues)
  } else if (singleMap) {
    debugPrint("Executing ${stepName} with only yml".center(80, '-'), 2)
    workflow."${stepName}"(yml)
  } else {
    error("""|Error while trying to execute workflow step: ${stepName}
             |Please ensure the ${stepName} exists in the ${sectionName}.groovy file as a public method.
             |------------------------------------------------------------------------------------------
             |When using a custom workflow please ensure the styles match.
             |Example: WorkflowLibs 3.x with parameters need to be formatted like this:
             |--------------------------------------------------------
             |custom.groovy
             |
             |public method(yml,args) {
             |}
             |--------------------------------------------------------
             |With a pipelines.yml file similar to this:
             |
             |branches:
             |  master:
             |    steps:
             |      - custom:
             |          arg1: var1
             |--------------------------------------------------------
             |Custom workflows without parameters will be formatted like this:
             |--------------------------------------------------------
             |custom.groovy
             |
             |public method() {
             |}
             |--------------------------------------------------------
             |With a pipelines.yml file similar to this:
             |
             |branches:
             |  master:
             |    steps:
             |      - custom
             |--------------------------------------------------------
             """.stripMargin())
  }
}

private getStageName(workflow, List stages, String workflowName, String stepName, Map yml, Map args) {
  def stageName
  Boolean canGenerate = workflow.metaClass.respondsTo(workflow, 'getStageName', Map, Map, String)
  if (canGenerate) {
    stageName = workflow.getStageName(yml, args, stepName)
    if (!stageName) {
      "$workflowName: $stepName"
    }
  } else {
    stageName = "$workflowName: $stepName"
  }
  def existingStageNames = stages.findAll{ it == stageName }
  if (existingStageNames.size() > 0) {
    stages.add(stageName)
    stageName = "$stageName (${existingStageNames.size()+1})"
  } else {
    stages.add(stageName)
  }
  return stageName
}

// Workflow loader
private loadWorkflows(String fileName, Map yml) {
  def repo                = yml.tools?.jenkins?.workflows?.repo         ?: env.WORKFLOW_REPOSITORY
  def branch              = yml.tools?.jenkins?.workflows?.branch       ?: yml.tools?.jenkins?.workflows?.tag ?: 'master'
  def credentialCriteria  = yml.tools?.jenkins?.workflows?.credentials  ?: ['description': env.WORKFLOW_GIT_CREDENTIAL_DESCRIPTION]
  def workflowDir         = yml.tools?.jenkins?.workflows?.directory    ?: 'workflows'
  def nodeLabel           = yml.tools?.jenkins?.workflows?.label        ?: 'linux'

  assert fileName : "fileName field has an invalid value."

  debugPrint(['fileName' : fileName, 'repo' : repo, 'branch' : branch, 'credentialCriteria' : credentialCriteria], 2)

  fileName = "${fileName}.groovy"
  def localFile = "$workflowDir/$fileName"
  def localFileExists = fileExists localFile
  debugPrint(['localFile' : localFile,'localFileExists' : localFileExists])

  def workflow
  if (localFileExists) {
    try {
      workflow = load localFile
      println """${'*'*80}
                |${Constants.Colors.YELLOW_ON_BLACK}Loaded Custom Workflow [${Constants.Colors.CYAN_ON_BLACK}$localFile${Constants.Colors.YELLOW_ON_BLACK}].${Constants.Colors.CLEAR}
                |${'*'*80}""".stripMargin()
    } catch (java.io.NotSerializableException nse) {
      error("""${Constants.Colors.RED}
              |Error loading workflow, this is most likely to be caused by a syntax error in the groovy file.
              |-----------------------------------------------------------------------------------------------
              |Common errors include missing/incomplete or incomplete syntax.
              |Examples
              |1. `def x =` # the var needs to be set to something
              |2. `def s = 'this is a string" # Open and close quotes should match. Single quote should be used unless you are doing interpolation.
              |-----------------------------------------------------------------------------------------------
              |Defining variable named the same as an existing variable
              |Examples
              |1. `def v = 1; def v = 3; # rename second variable if both are needed or drop the def to reassign the existing variable
              |2. `concurPipeline = new com.concur.Commands(); def concurPipeline = new com.concur.Commands(); # If def is not used variable is global.
              |-----------------------------------------------------------------------------------------------
              |Using a library function without importing. Using a function from workflowLibs without defining a variable.
              |1. `concurUtil.mustacheReplaceAll("Commit SHA - {{ commit_sha }}") # Ensure a definition for concurUtil exists eg `def concurUtil = new com.concur.Util()`
              |2. 'Yaml().load("<<string content>>")' # The Snake YAML library needs to be imported first eg `import org.yaml.snakeyaml.*;`
              ${Constants.Colors.CLEAR}
              """.stripMargin())
    }
  } else {
    assert repo : 'Repo to checkout for workflows not set under tools.jenkins.workflows.repo or as the environment variable: WORKFLOW_REPOSITORY.'
    // only search for credential if needed
    def credentialsId = getCredentialsWithCriteria(credentialCriteria).id
    assert credentialsId
    debugPrint(credentialsId)
    try {
      workflow = fileLoader.fromGit(fileName, repo, branch, credentialsId, nodeLabel)
      println """${'*'*80}
                |${Constants.Colors.WHITE_ON_BLACK}Loaded Workflow [${Constants.Colors.CYAN_ON_BLACK}${fileName}${Constants.Colors.WHITE_ON_BLACK}] from remote [${Constants.Colors.CLEAR}${repo}${Constants.Colors.WHITE_ON_BLACK}].${Constants.Colors.CLEAR}
                |${'*'*80}""".stripMargin()
    } catch (java.io.NotSerializableException nse) {
      error("Failed to load a workflow from $repo, please create an issue on the project in GitHub (https://github.com/concur/jenkins-workflow).")
    }
  }
  assert workflow : "Workflow file $fileName not found or unable to load from remote repo."

  return workflow
}

// Check branch pattern
def checkBranch(Map yml, String branch=env.BRANCH_NAME) {
  assert yml    : 'Couldn\'t find pipelines.yml.'
  assert branch : 'Branch name not set. This should typically be set by the environment as BRANCH_NAME. Please ensure this is being called within a node and that you are in a job type that provides sets the environment variable automatically such as a Multibranch pipeline.'

  def patterns = yml.tools?.branches?.patterns
  assert patterns : """|Define your branch patterns under tools.branches.patterns
                       |---------------------------------------------------------
                       |Example YML
                       |pipelines:
                       |  tools:
                       |    branches:
                       |      patterns:
                       |        feature: .+""".stripMargin()

  def branchType = patterns.find {
    branch.matches(it.value)
  }.key

  debugPrint(['branch' : branch, 'patterns' : patterns, 'branchType': branchType], 2)

  return branchType
}

// ########################
// # Jenkins Credentials
// ########################

/*
* Get the credentials based on criteria defined in a map
*
* Map parameters:
*
*   @param class -  enum CredentialTypes of what kind of credential to search for
*   @param id - jenkins id of the credential
*   @param password - password of the credential
*   @param description - description of the credential
*
* Other parameters can be passed and will be evaluated if they are properties of the credential type passed in the map
*/
def getCredentialsWithCriteria(Map criteria) {
  // Make sure criteria isn't empty
  assert criteria : 'WorkflowLibs :: Commands :: getCredentialsWithCriteria :: No criteria provided to search for, please use this function with a Map like such `new com.concur.Commands().getCredentialsWithCriteria([\'description\': \'example credential description\']`.'

  debugPrint(criteria, 2)

  if (criteria.keySet().contains('class')) {
    assert criteria."class".class != java.lang.String : "java.lang.String is not a valid class for credentials"
    criteria."class" = criteria."class".class == CredentialTypes ? criteria."class"?.getValue() : criteria."class"
    assert criteria."class" in CredentialTypes.getValues() : "Credential type ${criteria.'class'} is not supported or is invalid."
  }

  // Number of properties that that are in the map
  def count = criteria.keySet().size()
  def credentials = []

  // Get all of the global credentials
  def globalCreds = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
    com.cloudbees.plugins.credentials.impl.BaseStandardCredentials.class,
    jenkins.model.Jenkins.instance)
  // Get credentials for the folder that the job is in
  java.util.ArrayList folderCreds = new java.util.ArrayList()
  def folderNames = env.JOB_NAME ?: ""
  def folders = folderNames.split('/')
  for (int i = 0; i < folders.size(); i++) {
    def folderName = folders[0..i].join('/')
    try {
      getFolderCredentials(folderName).each { n ->
        folderCreds << n
        debugPrint(folderCreds, 2)
      }
    } catch (Exception e) { }
  }
  debugPrint([
    'folderCreds': folderCreds.collect { ['description': it.description, 'id': it.id] },
    'globalCreds': globalCreds.collect { ['description': it.description, 'id': it.id] }
  ])
  // Separately loop through credentials provided by different credential providers
  for (s in [folderCreds, globalCreds].flatten()) {
    // Filter the results based on description and class
    def i = 0
    if (count == s.getProperties().keySet().intersect(criteria.keySet()).size()) {
      if (s.getProperties().keySet().intersect(criteria.keySet()).equals(criteria.keySet())) {
        for (p in s.getProperties().keySet().intersect(criteria.keySet())) {
          if (s.p != criteria.p) {
            break;
          } else {
            i++;
          }
        }
      }
    }
    if (i == count) {
      credentials << s
    }
  }
  // Fail if no credentials are found that match the criteria
  assert credentials : """No credentials found that match your criteria: ${criteria}"""
  assert credentials.size() == 1 : """
  ${
    println "Multiple credentials found for search criteria.\n"
    println "Criteria:"
    println criteria
    println ""
    println "Credentials:"
    for(l in credentials) {
      println "id: ${l.id} description: ${l.description}"
    }
  }
  """
  // Get the single credential
  def credential = credentials[0]
  assert credential.id : "Invalid credentials. The id property of your credential is blank or corrupted."
  // Return the credentials
  return credential
}

// Get credentials for a given folder name
private getFolderCredentials(String folderName) {
  return AbstractFolder.class
                       .cast(Jenkins.instance.getItemByFullName(folderName))
                       .getProperties()
                       .get(FolderCredentialsProperty.class)
                       .getCredentials()
}

// Convert to a serializable list
@NonCPS
def jenkinsMap(Map gmap){
  def safeList = []
  gmap.each {
    safeList.add(new java.util.AbstractMap.SimpleImmutableEntry(it.key, it.value))
  }
  safeList
}

// Enum for Credentials
enum CredentialTypes {

    usernamePassword (com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl),
    sshPrivateKey (com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey),
    stringCredentials (org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl),
    fileCredentials (org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl)

    final Class cValue

    CredentialTypes(Class value) {
      this.cValue = value
    }

    Class getValue() {
      return this.cValue
    }

    String getKey() {
      name()
    }

    static List getValues() {
      List l = []
      this.values().each {
        l << it.getValue()
      }
      return l
    }
}

// ########################
// # Helpers
// ########################

// get a version number for the provided plugin name
def getPluginVersion(String pluginShortName) {
  assert pluginShortName : "Plugin name must be provided."
  return Jenkins.getInstance().pluginManager.getPlugin("${pluginShortName}")?.getVersion()
}

def getPipelineDataFile(String fileName = 'pipelines.yml', String format = 'yml', String baseNode = 'pipelines') {
  def fileContents = readFile fileName
  def dataMap = null
  switch(format.toLowerCase()) {
    case "yml":
    case "yaml":
      dataMap = concurUtil.parseYAML(fileContents)
      break;
    case "json":
      dataMap = concurUtil.parseJSON(fileContents)
      break;
  }
  if (dataMap?."${baseNode}"?.general?.debug) {
    env."${Constants.Env.DEBUG}"        = true // this being set allows for a lot of debugging output to be included.
    env."${Constants.Env.DEBUG_LEVEL}"  = (dataMap?."${baseNode}"?.general.debugLevel as int) ?: 1
  }
  return dataMap[baseNode]
}

// check the environment to see if we are in debug mode
def isDebug() {
  return env."${Constants.Env.DEBUG}"?.toBoolean() ?: false
}

public debugPrint(String title, Map msgdata, int debugLevelToPrint=1) {
  def str = "### ${Constants.Colors.MAGENTA}Debug output for [${Constants.Colors.BLUE}${title}${Constants.Colors.MAGENTA}]${Constants.Colors.CLEAR} ###"
  msgdata.each { data ->
    str += "\n### ${Constants.Colors.MAGENTA}Debug >>> ${Constants.Colors.CYAN}${data.key}: ${data.value}${Constants.Colors.CLEAR}"
  }
  str += "\n### ${Constants.Colors.MAGENTA}End Debug${Constants.Colors.CLEAR} ###"
  debugPrintMessage(str, debugLevelToPrint)
}

/* usage examples
  new com.concur.Commands().debugPrint('building docker image ...')
  new com.concur.Commands().debugPrint(['docker image name: ${dockerImageName}'])
  new com.concur.Commands().debugPrint(['docker image name': dockerImageName])
 */
public debugPrint(Map msgdata, int debugLevelToPrint=1) {
  def str = "### ${Constants.Colors.MAGENTA}Debug output for [${Constants.Colors.BLUE}${getDebugMessageTitle()}${Constants.Colors.MAGENTA}]${Constants.Colors.CLEAR} ###"
  msgdata.each { data ->
    str += "\n### ${Constants.Colors.MAGENTA}Debug >>> ${Constants.Colors.CYAN}${data.key}: ${data.value}${Constants.Colors.CLEAR}"
  }
  str += "\n### ${Constants.Colors.MAGENTA}End Debug${Constants.Colors.CLEAR} ###"
  debugPrintMessage(str, debugLevelToPrint)
}

public debugPrint(List msgdata, int debugLevelToPrint=1) {
  def str = "### ${Constants.Colors.MAGENTA}Debug output for [${Constants.Colors.BLUE}${getDebugMessageTitle()}${Constants.Colors.MAGENTA}]${Constants.Colors.CLEAR} ###"
  msgdata.each { data ->
    str += "\n### ${Constants.Colors.MAGENTA}Debug >>> ${Constants.Colors.CYAN}${data}${Constants.Colors.CLEAR}"
  }
  str += "\n### ${Constants.Colors.MAGENTA}End Debug${Constants.Colors.CLEAR} ###"
  debugPrintMessage(str, debugLevelToPrint)
}

public debugPrint(String msgdata, int debugLevelToPrint=1) {
  debugPrintMessage("""### ${Constants.Colors.MAGENTA}Debug output for [${Constants.Colors.BLUE}${getDebugMessageTitle()}${Constants.Colors.MAGENTA}]${Constants.Colors.CLEAR} ###
                      |### ${Constants.Colors.MAGENTA}Debug >>> ${Constants.Colors.CYAN}${msgdata}${Constants.Colors.CLEAR}
                      |### ${Constants.Colors.MAGENTA}End Debug${Constants.Colors.CLEAR} ###""".stripMargin(), debugLevelToPrint)
}

private getDebugMessageTitle() {
  def cMethod = org.codehaus.groovy.runtime.StackTraceUtils.sanitize(new Throwable()).stackTrace[2]
  return "WorkflowLibs :: ${cMethod.declaringClass} :: ${cMethod.methodName} :: Line ${cMethod.lineNumber}"
}

private debugPrintMessage(String msg, int debugLevelToPrint=1) {
  if (isDebug()) {
    if (debugShouldPrint(debugLevelToPrint)) {
      println msg
    }
  }
}

private debugShouldPrint(int debugLevelToPrint) {
  int currentRequestedLevel = env."${Constants.Env.DEBUG_LEVEL}" as int
  return (debugLevelToPrint <= currentRequestedLevel)
}
