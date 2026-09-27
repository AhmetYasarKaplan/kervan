# Contributing to Kervan

Open an issue before proposing a public API or module boundary change so the
contract and compatibility impact can be reviewed. For focused fixes, send a pull
request with a brief explanation and a test that exercises the behavior.

Use JDK 21. Run `./gradlew clean check` before submitting a pull request (on
Windows, use `.\gradlew.bat clean check`). The Elasticsearch integration tests
need Docker. Run `./gradlew spotlessApply` to format Java and Gradle files.

Keep `kervan-api` independent of infrastructure libraries and keep optional
adapters separate from `kervan-core`. Add Javadoc for public API changes and
update the relevant guide when behavior or configuration changes. CI runs the
same `clean check` command and publishes test reports for failed runs.
