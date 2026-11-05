# apksig (vendored)

Only the APK Signature Scheme v2 pieces of the `apk_sign_ts` library are kept
here (`V2Signer`, `SigningKey`, the signing-block writer, the zip helpers and
the digest utilities). The v1/v3 signers and the browser entry points were
removed because this project signs with v2 only.

The digest algorithm was cross-checked against an APK produced by Google's
`apksigner`: `tools/apkverify.py` verifies both that APK and the ones this
script produces.
