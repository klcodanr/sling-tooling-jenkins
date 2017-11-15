def rawUrlPrefix = "https://raw.githubusercontent.com/apache"
def repoUrl = rawUrlPrefix + "/sling-aggregator/master/default.xml"
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

def manifest = new XmlParser().parse(repoUrl)
manifest.project.each { project ->
    jobName = project.@name.toString().replace(".git","")
    def slingMod = []
    def createJob = true
    try {
        slingMod = new XmlParser().parse(rawUrlPrefix + "/" + jobName + "/master/.sling-module.xml")
    } catch ( FileNotFoundException e) {
        println "${jobName}: no .sling-module.xml found, using defaults"
    }
    def module = [ location: jobName ]
    if ( slingMod?.jenkins?.jdks ) {
        def jdks = []
        slingMod.jenkins.jdks.jdk.each { jdks.add it.text() }
        module.jdks = jdks

        println "${jobName}: overriding JDKs list to be ${jdks}"
    }

    if ( slingMod?.jenkins?.enabled ) {
        createJob = Boolean.valueOf(slingMod.jenkins.enabled.text())
        println "${jobName}: overriding job creation with value ${createJob}"
    }

    if ( slingMod?.jenkins?.enableXvfb ) {
        module.enableXvfb = Boolean.valueOf(slingMod.jenkins.enableXvfb.text())
        println "${jobName}: overriding xvfb support with value ${module.enableXvfb}"
    }

    if ( slingMod?.jenkins?.mavenGoal ) {
        module.mavenGoal = slingMod.jenkins.mavenGoal.text()
        println "${jobName}: overriding default maven goal with value ${module.mavenGoal}"
    }

    if ( createJob ) {
        modules += module
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
                git {
                    remote {
                        github('apache/' + module.location)
                    }
                    branches('master')
                }
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

                if ( module.enableXvfb ) {
                    xvfb('Xvfb')
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
            def goal = module.mavenGoal ? module.mavenGoal : ( deploy ? "deploy" : "verify" )
            goals( "-U clean ${goal} ${extraGoalsParams}")

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
