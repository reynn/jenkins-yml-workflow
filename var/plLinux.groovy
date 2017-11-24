#!/usr/bin/groovy
// vars/bhLinux.groovy
def call(duration = 1, unit = "HOURS", Closure body) {

  def concurPipeline = new com.concur.ConcurCommands()

  node('linux') {
    duration  = duration  ?: 1
    unit      = unit      ?: 'HOURS'
    assert (duration instanceof Integer) : "duration must be a Integer"
    assert (unit instanceof String) : "unit must be a String"
    
    timeout(time: duration, unit: unit.toUpperCase()) {
      ansiColor('xterm') {
        concurPipeline.debugPrint("bhLinux", ['duration' : duration, 'unit' : unit])
        String linuxWS = pwd()
        try {
          body()
        } catch (e) {
          println "Execution error"
          throw e
        } finally {
          // Mount the workspace in the docker container.
          assert linuxWS.startsWith('/var/lib/jenkins/workspace') : "Can't delete workspace. ${linuxWS} is an invalid workspace path"
          docker.image('alpine:3.5').inside('-u 0:0'){
            sh "find ${linuxWS} -and -not -path ${linuxWS} -delete"
          }
        }
      }
    }
  }
}