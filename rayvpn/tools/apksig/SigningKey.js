/**
 * SigningKey - Handles private keys and certificates for APK signing
 * Designed for browser-based signing operations
 */
export class SigningKey {
    privateKey;
    certificate;
    keyPassword;
    constructor(options) {
        this.privateKey = options.privateKey;
        this.certificate = options.certificate;
        this.keyPassword = options.keyPassword;
    }
    /**
     * Get the private key in PEM format
     */
    getPrivateKey() {
        return this.privateKey;
    }
    /**
     * Get the certificate in PEM format (if available)
     */
    getCertificate() {
        return this.certificate;
    }
    /**
     * Get the key password (if available)
     */
    getKeyPassword() {
        return this.keyPassword;
    }
    /**
     * Create a SigningKey from PEM strings
     */
    static fromPEM(privateKey, certificate, keyPassword) {
        return new SigningKey({ privateKey, certificate, keyPassword });
    }
    /**
     * Validate that the private key is in valid PEM format
     */
    validatePrivateKey() {
        const pemRegex = /^-----BEGIN (RSA )?PRIVATE KEY-----[\s\S]+-----END (RSA )?PRIVATE KEY-----$/;
        return pemRegex.test(this.privateKey.trim());
    }
    /**
     * Validate that the certificate is in valid PEM format (if provided)
     */
    validateCertificate() {
        if (!this.certificate)
            return false;
        const certRegex = /^-----BEGIN CERTIFICATE-----[\s\S]+-----END CERTIFICATE-----$/;
        return certRegex.test(this.certificate.trim());
    }
}
//# sourceMappingURL=SigningKey.js.map