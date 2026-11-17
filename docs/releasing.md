# Publishing a release

The repository coordinates are `io.github.yidasanqian:llm-api-sdk-*`. The first public version is `0.1.0`.

All release builds run in GitHub Actions. Do not stage or deploy Maven Central artifacts from a local workstation. A `v*` tag runs the complete pipeline without a manual approval step.

## GitHub workflows

- [`ci.yml`](../.github/workflows/ci.yml) runs on pushes to `main` and pull requests. It tests Java 17, 21 and 25, installs the artifacts and runs the standalone consumer.
- [`release.yml`](../.github/workflows/release.yml) runs on a `v*` tag push or a manual dispatch that names an existing tag. It verifies the tag matches the POM version, builds, signs, publishes to Central and creates a GitHub Release.

## Before the first release

1. Confirm that the source and license are approved for public distribution, and review tracked files for private data.
2. Create the public GitHub repository and verify the `io.github.yidasanqian` namespace in Central Portal.
3. Create the `maven-central` environment without required reviewers and add `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` as environment secrets. Use a Central user token, not account credentials.
4. Publish the signing key's public half to `keyserver.ubuntu.com` or `keys.openpgp.org`. The workflow imports the private key; it does not distribute the public key.
5. Review the public API, protocol limitations and pricing semantics before publishing an immutable version.
6. Confirm that the project meets the applicable Maven Central publisher terms as community open source.

## Release flow

1. Set the root, module and example versions to the release version, then commit and push `main`.
2. Wait for the Java 17/21/25 CI matrix and the standalone consumer job to pass.
3. Create and push the matching tag: `git tag -a v0.1.0 -m "v0.1.0"` then `git push origin v0.1.0`.
4. The workflow checks `v$version`, builds, signs, publishes to Central and waits up to 60 minutes for publication to complete. The job has a 90-minute overall timeout.
5. After Central publication succeeds, the workflow creates a GitHub Release for the same tag.
6. Verify the released artifacts from a clean Maven repository and the standalone example.

## After publication

- Keep the released tag and artifacts immutable. Publish a new version for any fix.
- Update the README version and Central badges for the new release.
- Start the next development version as a new SNAPSHOT, for example `0.1.1-SNAPSHOT`.

References: [Central requirements](https://central.sonatype.org/publish/requirements/), [publisher plugin](https://central.sonatype.org/publish/publish-portal-maven/), [namespaces](https://central.sonatype.org/register/namespace/).
