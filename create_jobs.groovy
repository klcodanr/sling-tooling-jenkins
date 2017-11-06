def repoUrl = "https://raw.githubusercontent.com/apache/sling-aggregator/master/default.xml"
def repoBase = "https://github.com/apache/"
// Repositories are read from the repo definition file. Not all keys are currently
// read from the repo xml file.
// keys:
//   - location ( required ) : the GitHub project name
//   - jdks (optional) : override the default jdks to use for build
//   - downstream (optional): list of downstream projects
//   - archive (optional): list of archive patterns
//   - extraGoalsParams (optional): additional string for the Maven goals to execute
//   - rebuildDaily (optional): boolean, when enabled configures the build to run once every
//                                24 hours,even if no changes are found in source control

def modules = []
// TODO - move blacklist out of this file
def blacklist = ['sling-tooling-jenkins', 'sling-tooling-scm']

println "Hello"

def manifest = new XmlParser().parse(repoUrl)
manifest.project.each { project ->
    jobName = project.@name.toString().replace(".git","")
    if ( !blacklist.contains(jobName) ) {
        modules += [ location: project.@name.toString().replace(".git", "")]
    }
}

// should be sorted from the oldest to the latest version
// so that artifacts built using the oldest version are
// deployed for maximum compatibility
def defaultJdks = ["1.8"]
def defaultMvn = "Maven 3.3.9"
def defaultSlave = "ubuntu"

def jdkMapping = [
    "1.7": "JDK 1.7 (latest)",
    "1.8": "JDK 1.8 (latest)",
    "9"  : "JDK 9 b181"
]

modules.each { module ->

    def jdks = module.jdks ?: defaultJdks
    def deploy = true

    def downstreamProjects = module.downstream?: []
    def downstreamEntries = modules.findAll { downstreamProjects.contains(it.location) }
    def downstreamJobs = []

    downstreamEntries.each { downstreamEntry ->
        def downstreamJdks = downstreamEntry.jdks?: defaultJdks
        def downstreamLocation = downstreamEntry.location
        downstreamJdks.each { downstreamJdk ->
            downstreamJobs.add(jobName(downstreamLocation,downstreamJdk))
        }
    }

    jdks.each { jdkKey ->
        mavenJob(jobName(module.location, jdkKey)) {

            description('''
<p>This build was automatically generated and any manual edits will be lost.</p>
<p>See <a href="https://cwiki.apache.org/confluence/display/SLING/Sling+Jenkins+Setup">Sling Jenkins Setup</a>
for more details</p>''')

            logRotator {
                numToKeep(15)
            }

            scm {
                github('apache/' + module.location)
            }

            blockOnUpstreamProjects()

            triggers {
                snapshotDependencies(true)
                scm('H/15 * * * *')
                def rebuildFrequency = module.rebuildDaily ? '@daily' : '@weekly'
                cron(rebuildFrequency)
            }

            // timeout if the job takes 4 times longer than the average
            // duration of the last 3 jobs. Defaults to 30 minutes if
            // no previous job executions are found
            wrappers {
                timeout {
                    elastic(400, 3, 30)
                }
            }

            blockOnUpstreamProjects()

            jdk(jdkMapping.get(jdkKey))

            mavenInstallation(defaultMvn)

            // we have no use for archived artifacts since they are deployed on
            // repository.apache.org so speed up the build a bit (and probably
            // save on disk space)
            archivingDisabled(true)

            label(defaultSlave)

            // ensure that only one job deploys artifacts
            // besides being less efficient, it's not sure which
            // job is triggered first and we may end up with a
            // mix of Java 7 and Java 8 artifacts for projects which
            // use these 2 versions
            def extraGoalsParams = module.extraGoalsParams ?: ""
            goals( (deploy ? "-U clean deploy" : "-U clean verify") + " " + extraGoalsParams)

            publishers {
                if ( deploy && downstreamJobs ) {
                    downstream(downstreamJobs)
                }

                if (module.archive) {
                    archiveArtifacts() {
                        module.archive.each { archiveEntry ->
                            pattern(archiveEntry)
                        }
                    }
                }

                // TODO - can we remove the glob and rely on the defaults?
                archiveJunit('**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml') {
                    allowEmptyResults()
                }
                // send emails for each broken build, notify individuals as well
                mailer('commits@sling.apache.org', false, true)
            }

            deploy = false
        }
    }
}

String jobName(String location, String jdk) {
    return location + '-' + jdk;
}
