# Versioning and releases

Kervan's eleven library modules share the version in `gradle.properties` and use the
Maven group `io.github.ahmetyasarkaplan.kervan`. The runnable example is not a
library publication. Use a new immutable version for each public release.
Incompatible public API changes require a new major version, compatible features
a minor version, and fixes a patch version.

Sonatype provides a free path for ordinary community open-source publishing
within its publishing limits. Its commercial-publishing policy is independent
of volume and open-source licensing; review the current
[publishing conditions](https://central.sonatype.org/publish/maven-central-publishing-limits/)
for the publishing organization's use before selecting an account plan.

The Gradle `maven-publish` setup creates main, sources, and Javadoc JARs for each
library, with Apache-2.0, developer, and SCM metadata in the POM. Library JARs
include the license text. When `KERVAN_SIGNING_KEY` and
`KERVAN_SIGNING_PASSWORD` are set, Gradle signs the publications with an
in-memory PGP key. These values must stay outside the repository.

Run `./gradlew verifyPublicationArtifacts` to check the generated JARs, license
files, POM coordinates, required metadata, and dependency versions locally.
This check does not need signing credentials or publish anything.

Review resolved runtime dependencies against current upstream security advisories
before each release. The backend and server modules declare patched Jackson,
Guava, OpenTelemetry, and Tomcat dependencies directly so these versions also
appear in the published Maven POMs. Keep these declarations until the respective
client or Spring Boot dependency baseline includes the fixes; a local Gradle
resolution rule alone would not protect applications consuming the Maven POMs.

## Prepare a Central Portal bundle

1. Verify the `io.github.ahmetyasarkaplan.kervan` namespace in the Maven Central
   Portal, and confirm the account can publish it.
2. Review the public API, changelog/release notes, example, documentation, and
   dependency versions. Set the intended release version in `gradle.properties`.
   Never reuse a previously published version.
3. Set `KERVAN_SIGNING_KEY` to the ASCII-armored private PGP key and
   `KERVAN_SIGNING_PASSWORD` to its passphrase in the release environment.
   Distribute the corresponding public key to a supported key server and confirm
   that it can be retrieved before uploading. Check the key's expiry and keep its
   revocation certificate safe; see Sonatype's
   [PGP signing instructions](https://central.sonatype.org/publish/requirements/gpg/).
4. Run `./gradlew clean check`. This includes warning-free Javadoc generation and
   the Elasticsearch, OpenSearch, Qdrant and pgvector Testcontainers tests, which
   need Docker. Then run
   `./gradlew centralBundle`. This clears the previous local staging directory,
   publishes all eleven modules to it, verifies the staged files, and creates
   `build/distributions/kervan-<version>-central.zip`.
5. Inspect the ZIP before uploading it. Its root must be the `io` directory,
   with a Maven version directory for each of the eleven library modules. Each
   directory contains the main, sources, and Javadoc JARs and POM, with a `.asc`
   signature and `.md5` and `.sha1` checksums for each artifact. The Gradle task
   checks that the required files are present and the checksums match. Review
   the POM dependencies and metadata as part of the release review. The local
   signature check confirms ASCII-armored signature files are present; it does
   not verify their cryptographic validity or public-key availability. Use GPG
   to verify a staged signature against its artifact and the intended public key
   (for example, `gpg --verify <artifact>.asc <artifact>`).
6. Upload the ZIP in the [Central Portal](https://central.sonatype.com/).
   Keep publication user-managed, inspect validation results, then release it
   after review. Do not automate publication from a normal `check` run.

The [Central requirements](https://central.sonatype.org/publish/requirements/)
specify POM metadata, sources, Javadoc, signatures, and `.md5` and `.sha1`
checksums. The [bundle upload guide](https://central.sonatype.org/publish/publish-portal-upload/)
describes the Maven repository layout for the ZIP. The Portal may require
additional account setup; this repository does not hold publishing credentials.

For a local consumer test without a Portal upload, run
`./gradlew publishToMavenLocal`, then
resolve the desired module from `mavenLocal()` in a separate application.

CI also resolves the starter and optional adapters in independent applications
using one isolated Maven directory. See [starter consumer](../smoke/consumer/README.md)
and [adapter consumer](../smoke/adapters-consumer/README.md). This verifies generated
POM dependencies without using the repository's project classpath.
