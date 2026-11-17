## Summary

Describe the behavior changed and the protocol or pricing contract affected.

## Verification

- [ ] `mvn verify` passes on the Java 17 baseline.
- [ ] New behavior has a focused regression test.
- [ ] Protocol fixtures contain no credentials, private endpoints or customer data.
- [ ] Public API, documentation and conversion limitations are updated when needed.

## Security

- [ ] The change does not log or retain API keys, authorization headers or sensitive response bodies.
