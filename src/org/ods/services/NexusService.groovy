package org.ods.services

@Grab(group='com.konghq', module='unirest-java', version='2.4.03', classifier='standalone')

import com.cloudbees.groovy.cps.NonCPS
import kong.unirest.Unirest
import org.ods.util.PipelineSteps

import org.apache.http.client.utils.URIBuilder

class NexusService {

    final URI baseURL

    final String username
    final String password

    NexusService(String baseURL, String username, String password) {
        if (!baseURL?.trim()) {
            throw new IllegalArgumentException("Error: unable to connect to Nexus. 'baseURL' is undefined.")
        }

        if (!username?.trim()) {
            throw new IllegalArgumentException("Error: unable to connect to Nexus. 'username' is undefined.")
        }

        if (!password?.trim()) {
            throw new IllegalArgumentException("Error: unable to connect to Nexus. 'password' is undefined.")
        }

        try {
            this.baseURL = new URIBuilder(baseURL).build()
        } catch (e) {
            throw new IllegalArgumentException(
                "Error: unable to connect to Nexus. '${baseURL}' is not a valid URI."
            ).initCause(e)
        }

        this.username = username
        this.password = password
    }

    @SuppressWarnings('SpaceAroundMapEntryColon')
    @NonCPS
    URI storeArtifact(String repository, String directory, String name, byte[] artifact, String contentType) {
        Map nexusParams = [
            'raw.directory':directory,
            'raw.asset1.filename':name,
        ]

        return storeComplextArtifact(repository, artifact, contentType, 'raw', nexusParams)
    }

    @Deprecated
    URI storeArtifactFromFile(
        String repository,
        String directory,
        String name,
        File artifact,
        String contentType) {
        return storeArtifact(repository, directory, name, artifact.getBytes(), contentType)
    }

    URI storeArtifactFromFile(
        String repository,
        String directory,
        String name,
        String artifact,
        String contentType) {
        def steps = ServiceRegistry.instance.get(PipelineSteps)
        def url = this.baseURL.toString() + '/service/rest/v1/components?repository=' + repository
        steps.sh('curl -X POST -H "Content-Type: ' + contentType +
            '" -u \'' + this.username + ':' + this.password +'\'' +
            ' -F raw.directory=' + directory +
            ' -F raw.asset1.filename=' + name +
            ' -F raw.asset1=@' + artifact.trim() + url)
        return this.baseURL.resolve("/repository/${repository}/${directory}/${name}")
    }

    @SuppressWarnings('LineLength')
    @NonCPS
    URI storeComplextArtifact(String repository, byte[] artifact, String contentType, String repositoryType, Map nexusParams = [ : ]) {
        def restCall = Unirest.post("${this.baseURL}/service/rest/v1/components?repository={repository}")
            .routeParam('repository', repository)
            .basicAuth(this.username, this.password)

        nexusParams.each { key, value ->
            restCall = restCall.field(key, value)
        }

        restCall = restCall.field(
            repositoryType == 'raw' || repositoryType == 'maven2' ? "${repositoryType}.asset1" : "${repositoryType}.asset",
            new ByteArrayInputStream(artifact), contentType)

        def response = restCall.asString()
        response.ifSuccess {
            if (response.getStatus() != 204) {
                throw new RuntimeException(
                    'Error: unable to store artifact. ' +
                        "Nexus responded with code: '${response.getStatus()}' and message: '${response.getBody()}'."
                )
            }
        }

        response.ifFailure {
            def message = 'Error: unable to store artifact. ' +
                "Nexus responded with code: '${response.getStatus()}' and message: '${response.getBody()}'."

            if (response.getStatus() == 404) {
                message = "Error: unable to store artifact. Nexus could not be found at: '${this.baseURL}'."
            }

            throw new RuntimeException(message)
        }

        if (repositoryType == 'raw') {
            return this.baseURL.resolve("/repository/${repository}/${nexusParams['raw.directory']}/" +
                "${nexusParams['raw.asset1.filename']}")
        }
        return this.baseURL.resolve("/repository/${repository}")
    }

    @SuppressWarnings(['LineLength','JavaIoPackageAccess'])
    @NonCPS
    @Deprecated
    Map<URI, File> retrieveArtifact(String nexuseRepository, String nexusDirectory, String name, String extractionPath) {
        // https://nexus3-cd....../repository/leva-documentation/odsst-WIP/DTP-odsst-WIP-108.zip
        String urlToDownload = "${this.baseURL}/repository/${nexuseRepository}/${nexusDirectory}/${name}"
        def restCall = Unirest.get("${urlToDownload}")
            .basicAuth(this.username, this.password)

        // hurray - unirest, in case file exists - don't do anything.
        File artifactExists = new File("${extractionPath}/${name}")
        if (artifactExists) {
            artifactExists.delete()
        }
        def response = restCall.asFile("${extractionPath}/${name}")

        response.ifFailure {
            def message = 'Error: unable to get artifact. ' +
                "Nexus responded with code: '${response.getStatus()}' and message: '${response.getBody()}'." +
                " The url called was: ${urlToDownload}"

            if (response.getStatus() == 404) {
                message = "Error: unable to get artifact. Nexus could not be found at: '${urlToDownload}'."
            }
            // very weird, we get a 200 as failure with a good artifact, wtf.
            if (response.getStatus() != 200) {
                throw new RuntimeException(message)
            }
        }

        return [
            uri: this.baseURL.resolve("/repository/${nexuseRepository}/${nexusDirectory}/${name}"),
            content: response.getBody(),
        ]
    }

    @SuppressWarnings(['LineLength','JavaIoPackageAccess'])
    @NonCPS
    Map<URI, String> retrieveArtifactToFile(String nexuseRepository, String nexusDirectory, String name, String extractionPath) {
        // https://nexus3-cd....../repository/leva-documentation/odsst-WIP/DTP-odsst-WIP-108.zip
        String urlToDownload = "${this.baseURL}/repository/${nexuseRepository}/${nexusDirectory}/${name}"

        def steps = ServiceRegistry.instance.get(PipelineSteps)
        def artifact = "${extractionPath}/${name}"
        steps.sh("rm -f \"${artifact}\" && " +
            "curl -u '${this.username}:${this.password}' -o ${artifact} ${urlToDownload}")

         return [
            uri: this.baseURL.resolve("/repository/${nexuseRepository}/${nexusDirectory}/${name}"),
            file: artifact,
        ]
    }
}
